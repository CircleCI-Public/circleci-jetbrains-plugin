package com.circleci.idea.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectLinkFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val linked =
        ProjectLinkFile.Info(
            slug = "gh/CircleCI-Public/circleci-cli",
            projectId = "7097f60c-74d1-4936-8d1a-268d4042a493",
            projectName = "circleci-cli",
            orgId = "ec6887ec-7d44-4b31-b468-7e552408ee32",
            orgName = "CircleCI-Public",
        )

    // As the CLI's yaml.v3 marshals it.
    private val cliFile =
        """
        organization:
            id: ec6887ec-7d44-4b31-b468-7e552408ee32
            name: CircleCI-Public
        project:
            id: 7097f60c-74d1-4936-8d1a-268d4042a493
            slug: gh/CircleCI-Public/circleci-cli
            name: circleci-cli

        """.trimIndent()

    @Test
    fun testReadsWhatTheCliWrites() {
        assertEquals("parsed", linked, ProjectLinkFile.parse(cliFile))
    }

    @Test
    fun testWritesWhatTheCliWrites() {
        assertEquals("formatted", cliFile, ProjectLinkFile.format(linked))
    }

    @Test
    fun testLeavesOutWhatIsNotKnown() {
        val info = ProjectLinkFile.Info(slug = "gh/org/repo")
        assertEquals("slug only", "organization: {}\nproject:\n    slug: gh/org/repo\n", ProjectLinkFile.format(info))
        assertEquals("round trip", info, ProjectLinkFile.parse(ProjectLinkFile.format(info)))
    }

    @Test
    fun testReadsHandWrittenYaml() {
        val text =
            """
            # linked by hand
            project:
              slug: "gh/org/repo"
              name: repo
            """.trimIndent()
        assertEquals(
            "quotes, comments and other indents",
            ProjectLinkFile.Info(slug = "gh/org/repo", projectName = "repo"),
            ProjectLinkFile.parse(text),
        )
    }

    @Test(expected = ProjectLinkFile.InvalidLinkException::class)
    fun testSlugIsRequired() {
        ProjectLinkFile.parse("project:\n    name: repo\n")
    }

    @Test(expected = ProjectLinkFile.InvalidLinkException::class)
    fun testRejectsWhatIsNotYaml() {
        ProjectLinkFile.parse("project: [unclosed\n")
    }

    @Test
    fun testReadAndWriteInARoot() {
        val root = folder.root.toPath()
        assertNull("no link yet", ProjectLinkFile.read(root))

        ProjectLinkFile.write(root, linked)
        assertTrue("in .circleci", root.resolve(".circleci/info.yml").toFile().isFile)
        assertEquals("read back", linked, ProjectLinkFile.read(root))

        val relinked = ProjectLinkFile.Info(slug = "gh/org/other")
        ProjectLinkFile.write(root, relinked)
        assertEquals("replaced", relinked, ProjectLinkFile.read(root))
    }

    @Test
    fun testEffectiveSlug() {
        assertEquals("vcs slugs as written", "gh/CircleCI-Public/circleci-cli", linked.effectiveSlug)
        assertEquals(
            "standalone slugs from the IDs",
            "circleci/org-id/project-id",
            ProjectLinkFile.Info(
                "circleci/old-org/old-project",
                projectId = "project-id",
                orgId = "org-id",
            ).effectiveSlug,
        )
        assertEquals(
            "standalone slugs as written without both IDs",
            "circleci/old-org/old-project",
            ProjectLinkFile.Info("circleci/old-org/old-project", projectId = "project-id").effectiveSlug,
        )
    }

    @Test
    fun testLinkedProject() {
        val project = linkedProject(linked, "/work/cli")
        assertEquals("slug", "gh/CircleCI-Public/circleci-cli", project?.slug)
        assertEquals("path", "/work/cli", project?.localPath)
        assertEquals("named", "CircleCI-Public/circleci-cli", project?.getDisplayName())
        assertTrue("linked", project?.linked == true)

        val standalone = linkedProject(ProjectLinkFile.Info("circleci/org-id/project-id"), null)
        assertEquals("unnamed standalone shows its slug", "org-id/project-id", standalone?.getDisplayName())

        assertNull("not a slug", linkedProject(ProjectLinkFile.Info("nonsense"), null))
    }

    @Test
    fun testIsLinkFile() {
        assertTrue("unix", isLinkFile("/work/cli/.circleci/info.yml"))
        assertTrue("windows", isLinkFile("C:\\work\\cli\\.circleci\\info.yml"))
        assertFalse("config", isLinkFile("/work/cli/.circleci/config.yml"))
        assertFalse("elsewhere", isLinkFile("/work/cli/info.yml"))
    }
}
