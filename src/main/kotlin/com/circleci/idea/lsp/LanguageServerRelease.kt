package com.circleci.idea.lsp

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A release of the CircleCI YAML Language Server, as published on GitHub.
 */
data class LanguageServerRelease(
    val version: String,
    val assets: List<Asset>,
) {
    data class Asset(val name: String, val downloadUrl: String)

    fun findAsset(predicate: (String) -> Boolean): Asset? = assets.find { predicate(it.name) }
}

/**
 * What to download from a release for one platform.
 *
 * Release archives are named `circleci-yaml-language-server_<version>_<os>_<arch>.<ext>`
 * and contain the binary alongside the `schema.json` it was built with.
 */
data class ReleaseTarget(
    val os: String,
    val arch: String,
    val isZip: Boolean,
) {
    val archiveSuffix: String get() = "_${os}_$arch.${if (isZip) "zip" else "tar.gz"}"
    val binaryName: String get() = if (os == "windows") "$ARCHIVE_BINARY.exe" else ARCHIVE_BINARY

    fun isArchiveFor(assetName: String): Boolean =
        assetName.startsWith("${ARCHIVE_PREFIX}_") && assetName.endsWith(archiveSuffix)

    companion object {
        const val ARCHIVE_PREFIX = "circleci-yaml-language-server"
        const val ARCHIVE_BINARY = "circleci-yaml-lsp"

        /**
         * The target for the running IDE, or null if the server isn't built for it.
         */
        fun current(): ReleaseTarget? {
            val arch =
                when (CpuArch.CURRENT) {
                    CpuArch.X86_64 -> "amd64"
                    CpuArch.ARM64 -> "arm64"
                    else -> return null
                }
            return when {
                SystemInfo.isMac -> ReleaseTarget("darwin", arch, isZip = false)
                SystemInfo.isLinux -> ReleaseTarget("linux", arch, isZip = false)
                SystemInfo.isWindows -> ReleaseTarget("windows", arch, isZip = true)
                else -> null
            }
        }
    }
}

const val CHECKSUMS_ASSET = "checksums.txt"
const val SCHEMA_FILE = "schema.json"

/**
 * Parses a goreleaser `checksums.txt`: one `<sha256>  <filename>` per line.
 * @return The lowercase checksum for each filename
 */
fun parseChecksums(content: String): Map<String, String> =
    content.lineSequence()
        .mapNotNull { CHECKSUM_LINE.matchEntire(it.trim()) }
        .associate { it.groupValues[2] to it.groupValues[1].lowercase() }

private val CHECKSUM_LINE = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Versions are used as directory names, so only accept plain `x.y.z` releases
 * (optionally `v`-prefixed or with a pre-release suffix).
 */
fun isValidVersion(version: String): Boolean = VERSION.matches(version)

private val VERSION = Regex("""^v?\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$""")

/**
 * Compares two versions accepted by [isValidVersion]. A release sorts after its
 * pre-releases; pre-releases of the same version compare as strings.
 */
fun compareVersions(
    a: String,
    b: String,
): Int {
    fun parts(version: String): Pair<List<Int>, String?> {
        val core = version.removePrefix("v").substringBefore('-')
        val preRelease = version.substringAfter('-', "").ifEmpty { null }
        return core.split('.').map { it.toInt() } to preRelease
    }
    val (aCore, aPre) = parts(a)
    val (bCore, bPre) = parts(b)
    for (i in 0 until maxOf(aCore.size, bCore.size)) {
        val diff = aCore.getOrElse(i) { 0 }.compareTo(bCore.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return when {
        aPre == bPre -> 0
        aPre == null -> 1
        bPre == null -> -1
        else -> aPre.compareTo(bPre)
    }
}
