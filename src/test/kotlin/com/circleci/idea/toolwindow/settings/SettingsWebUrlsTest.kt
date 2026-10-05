package com.circleci.idea.toolwindow.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsWebUrlsTest {
    @Test
    fun testClassicProjectSpellsOutItsVcs() {
        val project = SettingsWebUrls.project("gh/CircleCI-Public/circleci-cli")
        val org = SettingsWebUrls.organization("bb/acme/app")

        assertEquals(
            "project",
            "https://app.circleci.com/settings/project/github/CircleCI-Public/circleci-cli",
            project,
        )
        assertEquals("organization", "https://app.circleci.com/settings/organization/bitbucket/acme", org)
    }

    @Test
    fun testStandaloneProjectByItsIds() {
        val slug = "circleci/0a1b2c3d-0000-0000-0000-000000000001/0a1b2c3d-0000-0000-0000-000000000002"

        val project = SettingsWebUrls.project(slug)
        val org = SettingsWebUrls.organization(slug)

        assertEquals("project", "https://app.circleci.com/settings/project/$slug", project)
        assertEquals(
            "organization",
            "https://app.circleci.com/settings/organization/circleci/0a1b2c3d-0000-0000-0000-000000000001",
            org,
        )
    }

    @Test
    fun testNotAProjectSlug() {
        val unknownVcs = SettingsWebUrls.project("gl/org/repo")
        val tooShort = SettingsWebUrls.organization("gh/org")

        assertNull("an unknown VCS", unknownVcs)
        assertNull("too few parts", tooShort)
    }
}
