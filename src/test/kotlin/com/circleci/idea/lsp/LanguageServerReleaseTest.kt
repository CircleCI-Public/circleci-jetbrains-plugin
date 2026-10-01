package com.circleci.idea.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LanguageServerReleaseTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testParseChecksums() {
        val checksums =
            parseChecksums(
                """
                5910CA1C2B9D41B5D9AF0C3584CD4CC031361AAB147DC430CA211C68486C2484  circleci-yaml-language-server_0.48.0_darwin_amd64.tar.gz
                3e3717ed7d71604fcfedad02e9cf8fcea41406aa373cb677b4f9a2a006dea002 *circleci-yaml-language-server_0.48.0_darwin_arm64.tar.gz

                not a checksum line
                """.trimIndent(),
            )

        assertEquals("only valid lines are parsed", 2, checksums.size)
        assertEquals(
            "checksums are lowercased",
            "5910ca1c2b9d41b5d9af0c3584cd4cc031361aab147dc430ca211c68486c2484",
            checksums["circleci-yaml-language-server_0.48.0_darwin_amd64.tar.gz"],
        )
        assertEquals(
            "binary-mode marker is stripped",
            "3e3717ed7d71604fcfedad02e9cf8fcea41406aa373cb677b4f9a2a006dea002",
            checksums["circleci-yaml-language-server_0.48.0_darwin_arm64.tar.gz"],
        )
    }

    @Test
    fun testSha256() {
        val file = tempFolder.newFile().toPath()
        Files.writeString(file, "hello")
        assertEquals(
            "sha256 of 'hello'",
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha256(file),
        )
    }

    @Test
    fun testIsValidVersion() {
        assertTrue("plain version", isValidVersion("0.48.0"))
        assertTrue("v-prefixed version", isValidVersion("v1.2.3"))
        assertTrue("pre-release", isValidVersion("1.2.3-rc.1"))
        assertFalse("path traversal", isValidVersion("../0.48.0"))
        assertFalse("partial version", isValidVersion("1.2"))
        assertFalse("empty", isValidVersion(""))
    }

    @Test
    fun testCompareVersions() {
        assertEquals("equal", 0, compareVersions("0.48.0", "0.48.0"))
        assertEquals("v prefix is ignored", 0, compareVersions("v0.48.0", "0.48.0"))
        assertTrue("numeric, not string, ordering", compareVersions("0.10.0", "0.9.0") > 0)
        assertTrue("older patch", compareVersions("0.48.0", "0.48.1") < 0)
        assertTrue("release after its pre-release", compareVersions("1.0.0", "1.0.0-rc.1") > 0)
        assertTrue("pre-release before its release", compareVersions("1.0.0-rc.1", "1.0.0") < 0)
    }

    @Test
    fun testReleaseTargetArchiveNames() {
        val mac = ReleaseTarget("darwin", "arm64", isZip = false)
        assertTrue("matches mac archive", mac.isArchiveFor("circleci-yaml-language-server_0.48.0_darwin_arm64.tar.gz"))
        assertFalse("ignores other arch", mac.isArchiveFor("circleci-yaml-language-server_0.48.0_darwin_amd64.tar.gz"))
        assertFalse("ignores raw binaries", mac.isArchiveFor("darwin-arm64-lsp"))
        assertEquals("unix binary name", "circleci-yaml-lsp", mac.binaryName)

        val windows = ReleaseTarget("windows", "amd64", isZip = true)
        assertTrue(
            "matches windows archive",
            windows.isArchiveFor("circleci-yaml-language-server_0.48.0_windows_amd64.zip"),
        )
        assertEquals("windows binary name", "circleci-yaml-lsp.exe", windows.binaryName)
    }
}
