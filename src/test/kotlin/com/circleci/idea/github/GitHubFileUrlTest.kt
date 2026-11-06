package com.circleci.idea.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubFileUrlTest {
    @Test
    fun `reads a raw githubusercontent URL`() {
        val file = GitHubFileUrl.parse("https://raw.githubusercontent.com/acme/orbs/main/src/go.yml")

        assertEquals("file", GitHubFileUrl("acme", "orbs", "main", "src/go.yml"), file)
    }

    @Test
    fun `reads a ref spelled out as a branch or a tag`() {
        val branch = GitHubFileUrl.parse("https://raw.githubusercontent.com/acme/orbs/refs/heads/release/go.yml")
        val tag = GitHubFileUrl.parse("https://raw.githubusercontent.com/acme/orbs/refs/tags/v1.2.0/go.yml")

        assertEquals("branch", GitHubFileUrl("acme", "orbs", "refs/heads/release", "go.yml"), branch)
        assertEquals("tag", GitHubFileUrl("acme", "orbs", "refs/tags/v1.2.0", "go.yml"), tag)
    }

    @Test
    fun `reads a github com raw URL`() {
        val file = GitHubFileUrl.parse("https://github.com/acme/orbs/raw/main/go.yml")

        assertEquals("file", GitHubFileUrl("acme", "orbs", "main", "go.yml"), file)
    }

    @Test
    fun `reads nothing from other URLs`() {
        val urls =
            listOf(
                "https://github.com/acme/orbs/blob/main/go.yml",
                "https://example.com/acme/orbs/main/go.yml",
                "http://raw.githubusercontent.com/acme/orbs/main/go.yml",
                "https://raw.githubusercontent.com/acme/orbs/main",
                "not a url",
            )

        assertEquals("files", urls.map { null }, urls.map { GitHubFileUrl.parse(it) })
    }

    @Test
    fun `decodes the path`() {
        val file = GitHubFileUrl.parse("https://raw.githubusercontent.com/acme/orbs/main/my%20orbs/a+b.yml")

        assertEquals("path", "my orbs/a+b.yml", file?.path)
    }

    @Test
    fun `names the contents API URL of the file at its ref`() {
        val url =
            GitHubFileUrl(
                "acme",
                "orbs",
                "refs/heads/release",
                "my orbs/go.yml",
            ).contentsUrl("https://api.github.com/")

        assertEquals(
            "url",
            "https://api.github.com/repos/acme/orbs/contents/my%20orbs/go.yml?ref=refs%2Fheads%2Frelease",
            url,
        )
    }

    @Test
    fun `reads no file from a URL that names no path`() {
        assertNull("file", GitHubFileUrl.parse("https://raw.githubusercontent.com/acme/orbs/refs/heads/main"))
    }
}
