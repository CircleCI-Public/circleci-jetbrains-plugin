package com.circleci.idea.lsp

import com.intellij.openapi.util.SystemInfo
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class LanguageServerInstallerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val target = ReleaseTarget("linux", "amd64", isZip = false)
    private lateinit var root: Path
    private lateinit var assets: Path
    private lateinit var source: FakeReleaseSource
    private lateinit var installer: LanguageServerInstaller

    @Before
    fun setUp() {
        // The fake binary is a shell script
        assumeFalse(SystemInfo.isWindows)
        root = tempFolder.newFolder("install").toPath()
        assets = tempFolder.newFolder("assets").toPath()
        source = FakeReleaseSource()
        installer = LanguageServerInstaller(root, source, target)
    }

    @Test
    fun testNothingInstalled() {
        assertNull("no version installed", installer.installed())
    }

    @Test
    fun testInstallsVerifiedRelease() {
        val installed = installer.install(release("0.48.0"))

        assertEquals("installed version", "0.48.0", installed.version)
        assertEquals("binary location", root.resolve("0.48.0").resolve("circleci-yaml-lsp"), installed.binary)
        assertTrue("binary is executable", Files.isExecutable(installed.binary))
        assertEquals("installed() finds it", installed, installer.installed())
        assertFalse("no download left behind", hasDownloadDirectories())
    }

    @Test
    fun testInstalledPicksNewestVersion() {
        installer.install(release("0.9.0"))
        installer.install(release("0.10.0"))

        assertEquals("newest version wins", "0.10.0", installer.installed()?.version)
    }

    @Test
    fun testRejectsChecksumMismatch() {
        val release = release("0.48.0", checksum = "0".repeat(64))

        assertInstallFails(release, "Checksum mismatch")
    }

    @Test
    fun testRejectsMissingChecksum() {
        val release = release("0.48.0", checksumFor = "some-other-file.tar.gz")

        assertInstallFails(release, "No checksum published")
    }

    @Test
    fun testRejectsBinaryReportingWrongVersion() {
        val release = release("0.48.0", reportedVersion = "0.47.0")

        assertInstallFails(release, "Binary version mismatch")
    }

    @Test
    fun testRejectsReleaseWithoutArchiveForPlatform() {
        val release = release("0.48.0").copy(assets = emptyList())

        assertInstallFails(release, "has no archive")
    }

    @Test
    fun testRejectsUnsafeVersion() {
        val release = release("0.48.0").copy(version = "../0.48.0")

        assertInstallFails(release, "Unexpected language server version")
    }

    @Test
    fun testRemoveAllExcept() {
        installer.install(release("0.47.0"))
        installer.install(release("0.48.0"))
        Files.createDirectory(root.resolve(".download-abandoned"))
        Files.createDirectory(root.resolve("unrelated"))

        installer.removeAllExcept("0.48.0")

        assertFalse("old version removed", Files.exists(root.resolve("0.47.0")))
        assertTrue("kept version remains", Files.exists(root.resolve("0.48.0")))
        assertFalse("abandoned download removed", hasDownloadDirectories())
        assertTrue("unrelated directories are left alone", Files.exists(root.resolve("unrelated")))
    }

    private fun assertInstallFails(
        release: LanguageServerRelease,
        expectedMessage: String,
    ) {
        try {
            installer.install(release)
            fail("install should have failed")
        } catch (e: IOException) {
            assertTrue("unexpected error: ${e.message}", e.message.orEmpty().contains(expectedMessage))
        }
        assertNull("nothing installed", installer.installed())
        assertFalse("no download left behind", hasDownloadDirectories())
    }

    private fun hasDownloadDirectories(): Boolean =
        Files.list(root).use {
            it.anyMatch { p -> p.fileName.toString().startsWith(".download-") }
        }

    /**
     * Builds a release whose archive contains a script that prints [reportedVersion].
     */
    private fun release(
        version: String,
        reportedVersion: String = version,
        checksum: String? = null,
        checksumFor: String? = null,
    ): LanguageServerRelease {
        val archiveName = "circleci-yaml-language-server_${version}_linux_amd64.tar.gz"
        val archive = assets.resolve(archiveName)
        writeTarGz(archive, mapOf("circleci-yaml-lsp" to "#!/bin/sh\necho $reportedVersion\n", "schema.json" to "{}"))

        val checksums = assets.resolve("checksums-$version.txt")
        Files.writeString(checksums, "${checksum ?: sha256(archive)}  ${checksumFor ?: archiveName}\n")

        return LanguageServerRelease(
            version,
            listOf(
                LanguageServerRelease.Asset(archiveName, archive.toUri().toString()),
                LanguageServerRelease.Asset(CHECKSUMS_ASSET, checksums.toUri().toString()),
            ),
        )
    }

    private fun writeTarGz(
        archive: Path,
        files: Map<String, String>,
    ) {
        TarArchiveOutputStream(GzipCompressorOutputStream(Files.newOutputStream(archive))).use { tar ->
            for ((name, content) in files) {
                val bytes = content.toByteArray()
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                entry.mode = "755".toInt(radix = 8)
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    private class FakeReleaseSource : LanguageServerReleaseSource {
        override fun latestRelease(): LanguageServerRelease = throw UnsupportedOperationException()

        override fun download(
            url: String,
            target: Path,
        ) {
            Files.copy(Path.of(java.net.URI(url)), target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
