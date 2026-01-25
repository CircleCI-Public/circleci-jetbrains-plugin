package com.circleci.idea.project

import com.circleci.idea.project.models.VcsType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GitRemoteParserTest {

    @Test
    fun `should parse HTTPS GitHub URL`() {
        val url = "https://github.com/circleci/circleci.git"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNotNull(project)
        assertEquals(VcsType.GITHUB, project?.vcsType)
        assertEquals("circleci", project?.organization)
        assertEquals("circleci", project?.repository)
        assertEquals("gh/circleci/circleci", project?.slug)
    }

    @Test
    fun `should parse HTTPS GitHub URL without git extension`() {
        val url = "https://github.com/circleci/circleci-docs"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNotNull(project)
        assertEquals(VcsType.GITHUB, project?.vcsType)
        assertEquals("circleci", project?.organization)
        assertEquals("circleci-docs", project?.repository)
    }

    @Test
    fun `should parse SSH GitHub URL`() {
        val url = "git@github.com:circleci/circleci.git"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNotNull(project)
        assertEquals(VcsType.GITHUB, project?.vcsType)
        assertEquals("circleci", project?.organization)
        assertEquals("circleci", project?.repository)
    }

    @Test
    fun `should parse SSH with protocol GitHub URL`() {
        val url = "ssh://git@github.com/circleci/circleci.git"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNotNull(project)
        assertEquals(VcsType.GITHUB, project?.vcsType)
        assertEquals("circleci", project?.organization)
        assertEquals("circleci", project?.repository)
    }

    @Test
    fun `should parse Bitbucket URL`() {
        val url = "https://bitbucket.org/myorg/myrepo.git"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNotNull(project)
        assertEquals(VcsType.BITBUCKET, project?.vcsType)
        assertEquals("myorg", project?.organization)
        assertEquals("myrepo", project?.repository)
        assertEquals("bb/myorg/myrepo", project?.slug)
    }

    @Test
    fun `should parse GitLab URL`() {
        val url = "https://gitlab.com/mygroup/myproject.git"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNotNull(project)
        assertEquals(VcsType.GITLAB, project?.vcsType)
        assertEquals("mygroup", project?.organization)
        assertEquals("myproject", project?.repository)
        assertEquals("gl/mygroup/myproject", project?.slug)
    }

    @Test
    fun `should handle repository names with dashes and underscores`() {
        val url = "https://github.com/my-org/my_awesome-repo.git"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNotNull(project)
        assertEquals("my-org", project?.organization)
        assertEquals("my_awesome-repo", project?.repository)
    }

    @Test
    fun `should return null for invalid URL`() {
        val url = "not-a-valid-url"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNull(project)
    }

    @Test
    fun `should return null for unsupported VCS provider`() {
        val url = "https://gitea.com/org/repo.git"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertNull(project)
    }

    @Test
    fun `isValidGitUrl should return true for valid URLs`() {
        assertTrue(GitRemoteParser.isValidGitUrl("https://github.com/org/repo.git"))
        assertTrue(GitRemoteParser.isValidGitUrl("git@github.com:org/repo.git"))
        assertTrue(GitRemoteParser.isValidGitUrl("https://bitbucket.org/org/repo.git"))
    }

    @Test
    fun `isValidGitUrl should return false for invalid URLs`() {
        assertFalse(GitRemoteParser.isValidGitUrl("not-a-url"))
        assertFalse(GitRemoteParser.isValidGitUrl("https://unknown.com/org/repo.git"))
    }

    @Test
    fun `extractOrgAndRepo should return correct pair`() {
        val url = "https://github.com/circleci/circleci-docs.git"
        val (org, repo) = GitRemoteParser.extractOrgAndRepo(url)!!

        assertEquals("circleci", org)
        assertEquals("circleci-docs", repo)
    }

    @Test
    fun `extractOrgAndRepo should return null for invalid URL`() {
        val result = GitRemoteParser.extractOrgAndRepo("invalid-url")
        assertNull(result)
    }

    @Test
    fun `getVcsType should return correct VCS type`() {
        assertEquals(VcsType.GITHUB, GitRemoteParser.getVcsType("https://github.com/org/repo.git"))
        assertEquals(VcsType.BITBUCKET, GitRemoteParser.getVcsType("https://bitbucket.org/org/repo.git"))
        assertEquals(VcsType.GITLAB, GitRemoteParser.getVcsType("https://gitlab.com/org/repo.git"))
    }

    @Test
    fun `should preserve VCS URL in project`() {
        val url = "https://github.com/circleci/circleci.git"
        val project = GitRemoteParser.parseRemoteUrl(url)

        assertEquals(url, project?.vcsUrl)
    }
}
