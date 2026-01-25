package com.circleci.idea.project.models

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CircleCIProjectTest {

    @Test
    fun `fromSlug should parse valid GitHub slug`() {
        val project = CircleCIProject.fromSlug("gh/circleci/circleci")

        assertNotNull(project)
        assertEquals("gh/circleci/circleci", project?.slug)
        assertEquals(VcsType.GITHUB, project?.vcsType)
        assertEquals("circleci", project?.organization)
        assertEquals("circleci", project?.repository)
    }

    @Test
    fun `fromSlug should parse valid Bitbucket slug`() {
        val project = CircleCIProject.fromSlug("bb/myorg/myrepo")

        assertNotNull(project)
        assertEquals(VcsType.BITBUCKET, project?.vcsType)
        assertEquals("myorg", project?.organization)
        assertEquals("myrepo", project?.repository)
    }

    @Test
    fun `fromSlug should parse valid GitLab slug`() {
        val project = CircleCIProject.fromSlug("gl/mygroup/myproject")

        assertNotNull(project)
        assertEquals(VcsType.GITLAB, project?.vcsType)
        assertEquals("mygroup", project?.organization)
        assertEquals("myproject", project?.repository)
    }

    @Test
    fun `fromSlug should return null for invalid slug format`() {
        assertNull(CircleCIProject.fromSlug("invalid"))
        assertNull(CircleCIProject.fromSlug("gh/org"))
        assertNull(CircleCIProject.fromSlug("gh/org/repo/extra"))
    }

    @Test
    fun `fromSlug should return null for invalid VCS type`() {
        val project = CircleCIProject.fromSlug("invalid/org/repo")
        assertNull(project)
    }

    @Test
    fun `createSlug should create correct slug`() {
        val slug = CircleCIProject.createSlug(VcsType.GITHUB, "circleci", "circleci")
        assertEquals("gh/circleci/circleci", slug)
    }

    @Test
    fun `getDisplayName should return org and repo`() {
        val project = CircleCIProject(
            slug = "gh/circleci/circleci",
            vcsType = VcsType.GITHUB,
            organization = "circleci",
            repository = "circleci"
        )

        assertEquals("circleci/circleci", project.getDisplayName())
    }

    @Test
    fun `getFullDisplayName should include VCS type`() {
        val project = CircleCIProject(
            slug = "gh/circleci/circleci",
            vcsType = VcsType.GITHUB,
            organization = "circleci",
            repository = "circleci"
        )

        assertEquals("GitHub: circleci/circleci", project.getFullDisplayName())
    }

    @Test
    fun `copy should preserve data correctly`() {
        val original = CircleCIProject(
            slug = "gh/org/repo",
            vcsType = VcsType.GITHUB,
            organization = "org",
            repository = "repo"
        )

        val updated = original.copy(
            defaultBranch = "main",
            followed = true
        )

        assertEquals("main", updated.defaultBranch)
        assertTrue(updated.followed)
        assertEquals(original.slug, updated.slug)
        assertEquals(original.vcsType, updated.vcsType)
    }
}
