package com.circleci.idea.toolwindow.settings

import com.circleci.idea.api.models.Context
import com.circleci.idea.api.models.EnvVar
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTreeTest {
    private val deploy = Context("c-1", "deploy")
    private val release = Context("c-2", "release")

    /** Every element of a Jewel tree, opened and depth first, as "label" lines indented by depth. */
    private fun render(tree: Tree<SettingsNode>): List<String> {
        val lines = mutableListOf<String>()

        fun walk(elements: List<Tree.Element<SettingsNode>>) {
            for (element in elements) {
                val node = element.data
                val detail = (node as? SettingsNode.Variable)?.envVar?.maskedValue?.let { " $it" }.orEmpty()
                lines.add("  ".repeat(element.depth) + node.label + detail)
                if (element is Tree.Element.Node) {
                    // A node's children are generated as it's opened.
                    element.open()
                    walk(element.children.orEmpty())
                }
            }
        }
        walk(tree.roots)
        return lines
    }

    @Test
    fun testNoProjectSelected() {
        assertEquals("only a message", listOf("No CircleCI project selected"), render(settingsTree(SettingsState())))
    }

    @Test
    fun testListsNotYetLoadedShowLoading() {
        assertEquals(
            "both headings, their lists loading",
            listOf(
                "Project settings",
                "Environment variables",
                "  Loading...",
                "Org settings",
                "Contexts",
                "  Loading...",
            ),
            render(settingsTree(SettingsState(projectSlug = "gh/org/repo"))),
        )
    }

    @Test
    fun testLoadedLists() {
        val contextEnvVars =
            mapOf(
                deploy.id to Loadable.Loaded(listOf(EnvVar("TOKEN", "****abcd"))),
                release.id to Loadable.Loaded(emptyList()),
            )
        val state =
            SettingsState(
                projectSlug = "gh/org/repo",
                projectEnvVars = Loadable.Loaded(listOf(EnvVar("API_URL", "xxxx.com"))),
                contexts = Loadable.Loaded(listOf(deploy, release)),
                contextEnvVars = contextEnvVars,
            )

        assertEquals(
            "variables under their project or context, with their masked values",
            listOf(
                "Project settings",
                "Environment variables",
                "  API_URL xxxx.com",
                "Org settings",
                "Contexts",
                "  deploy",
                "    TOKEN ****abcd",
                "  release",
                "    No environment variables",
            ),
            render(settingsTree(state)),
        )
    }

    @Test
    fun testFailuresShowAsErrorRows() {
        val state =
            SettingsState(
                projectSlug = "gh/org/repo",
                projectEnvVars = Loadable.Failed("Forbidden"),
                contexts = Loadable.Loaded(emptyList()),
            )
        val tree = settingsTree(state)

        assertEquals(
            "the error in place of the list",
            listOf(
                "Project settings",
                "Environment variables",
                "  Forbidden",
                "Org settings",
                "Contexts",
                "  No contexts",
            ),
            render(tree),
        )
        val envVars = (tree.roots[1] as Tree.Element.Node).also { it.open() }
        assertEquals("marked as an error", true, (envVars.children!![0].data as SettingsNode.Message).error)
    }

    @Test
    fun testHeadingsDontOpenOrClose() {
        val headings =
            settingsTree(
                SettingsState(projectSlug = "gh/org/repo"),
            ).roots.filter { it.data is SettingsNode.Group }

        assertEquals("both headings", listOf("Project settings", "Org settings"), headings.map { it.data.label })
        assertTrue("leaves, which have nothing to collapse", headings.all { it is Tree.Element.Leaf })
    }

    @Test
    fun testKeysFollowWhatTheRowsShow() {
        val project = EnvVarOwner.Project("gh/org/repo")
        val context = EnvVarOwner.OrgContext(deploy)

        assertEquals("the project's list", PROJECT_ENV_VARS_KEY, SettingsNode.EnvVars(project, "x").key)
        assertEquals("a context's list, by its id", "context:c-1", SettingsNode.EnvVars(context, "deploy").key)
        assertEquals(
            "a variable, by its list and name",
            "context:c-1/var:TOKEN",
            SettingsNode.Variable(context, EnvVar("TOKEN", "****abcd")).key,
        )
    }
}
