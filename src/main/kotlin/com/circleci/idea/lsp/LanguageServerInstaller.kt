package com.circleci.idea.lsp

import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.io.Decompressor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Where language server releases come from.
 */
interface LanguageServerReleaseSource {
    fun latestRelease(): LanguageServerRelease

    fun download(
        url: String,
        target: Path,
    )
}

data class InstalledLanguageServer(val version: String, val binary: Path)

/**
 * Installs language server releases under [root], one directory per version.
 *
 * A release is downloaded and checked against the release's checksums, then the
 * binary is extracted and asked for its version before the directory is moved
 * into place. A version directory therefore only exists once it's complete, and
 * a binary is never overwritten while it might be running.
 */
class LanguageServerInstaller(
    private val root: Path,
    private val source: LanguageServerReleaseSource,
    private val target: ReleaseTarget,
) {
    /**
     * The newest installed version, or null if none is installed.
     */
    fun installed(): InstalledLanguageServer? =
        versionDirectories()
            .filter { Files.isRegularFile(it.resolve(target.binaryName)) }
            .maxWithOrNull { a, b -> compareVersions(a.fileName.toString(), b.fileName.toString()) }
            ?.let { InstalledLanguageServer(it.fileName.toString(), it.resolve(target.binaryName)) }

    /**
     * Downloads, verifies and installs [release].
     * @throws IOException if the release can't be downloaded or fails verification
     */
    fun install(release: LanguageServerRelease): InstalledLanguageServer {
        if (!isValidVersion(release.version)) {
            throw IOException("Unexpected language server version: ${release.version}")
        }
        val destination = root.resolve(release.version)
        val installedBinary = destination.resolve(target.binaryName)
        if (Files.isRegularFile(installedBinary)) {
            return InstalledLanguageServer(release.version, installedBinary)
        }

        val (archiveAsset, checksumsAsset) = findAssets(release)
        Files.createDirectories(root)
        val work = Files.createTempDirectory(root, DOWNLOAD_PREFIX)
        try {
            val checksums = work.resolve(CHECKSUMS_ASSET)
            val archive = work.resolve(archiveAsset.name)
            source.download(checksumsAsset.downloadUrl, checksums)
            source.download(archiveAsset.downloadUrl, archive)
            verifyChecksum(archive, checksums)

            val staging = work.resolve("extracted")
            verifyVersion(extractBinary(archive, staging), release.version)

            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            return InstalledLanguageServer(release.version, installedBinary)
        } finally {
            NioFiles.deleteRecursively(work)
        }
    }

    /**
     * Deletes every installed version except [version], and any abandoned downloads.
     * Best effort: a binary that's still running (e.g. on Windows) is left for next time.
     */
    fun removeAllExcept(version: String) {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { entries ->
            entries
                .filter { it.fileName.toString() != version }
                .filter { isValidVersion(it.fileName.toString()) || it.fileName.toString().startsWith(DOWNLOAD_PREFIX) }
                .forEach { runCatching { NioFiles.deleteRecursively(it) } }
        }
    }

    /**
     * @return This platform's archive and the release's checksums
     */
    private fun findAssets(
        release: LanguageServerRelease,
    ): Pair<LanguageServerRelease.Asset, LanguageServerRelease.Asset> {
        val archive =
            release.findAsset(target::isArchiveFor)
                ?: throw IOException("Release ${release.version} has no archive for ${target.os}/${target.arch}")
        val checksums =
            release.findAsset { it == CHECKSUMS_ASSET }
                ?: throw IOException("Release ${release.version} has no $CHECKSUMS_ASSET")
        return archive to checksums
    }

    private fun verifyChecksum(
        archive: Path,
        checksums: Path,
    ) {
        val name = archive.fileName.toString()
        val expected =
            parseChecksums(Files.readString(checksums))[name]
                ?: throw IOException("No checksum published for $name")
        val actual = sha256(archive)
        if (actual != expected) {
            throw IOException("Checksum mismatch for $name: expected $expected, got $actual")
        }
    }

    /**
     * Extracts the binary from [archive] into [staging].
     * @return The extracted binary
     */
    private fun extractBinary(
        archive: Path,
        staging: Path,
    ): Path {
        val decompressor = if (target.isZip) Decompressor.Zip(archive) else Decompressor.Tar(archive)
        decompressor.filter { it == target.binaryName }.extract(staging)
        val binary = staging.resolve(target.binaryName)
        if (!Files.isRegularFile(binary)) {
            throw IOException("${archive.fileName} does not contain ${target.binaryName}")
        }
        binary.toFile().setExecutable(true)
        return binary
    }

    private fun verifyVersion(
        binary: Path,
        expected: String,
    ) {
        val reported = probeVersion(binary)
        if (reported == null || !isValidVersion(reported) || compareVersions(reported, expected) != 0) {
            throw IOException("Binary version mismatch: expected $expected, got $reported")
        }
    }

    private fun versionDirectories(): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { entries ->
            entries.filter { Files.isDirectory(it) && isValidVersion(it.fileName.toString()) }.toList()
        }
    }

    /**
     * Runs `binary -version`, or returns null if it fails or doesn't answer in time.
     */
    private fun probeVersion(binary: Path): String? {
        val process = ProcessBuilder(binary.toString(), "-version").redirectErrorStream(true).start()
        if (!process.waitFor(VERSION_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return output.takeIf { process.exitValue() == 0 && it.isNotEmpty() }
    }

    private companion object {
        const val DOWNLOAD_PREFIX = ".download-"
        const val VERSION_PROBE_TIMEOUT_SECONDS = 5L
    }
}
