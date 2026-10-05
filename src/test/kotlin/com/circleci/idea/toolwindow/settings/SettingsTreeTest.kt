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

    private val slug = "gh/org/repo"

    @Test
    fun testNoProjectSelected() {
        val message = listOf("No CircleCI project selected")
        assertEquals("only a message, for the project", message, render(projectSettingsTree(SettingsState())))
        assertEquals("only a message, for the org", message, render(orgSettingsTree(SettingsState())))
    }

    @Test
    fun testListsNotYetLoadedShowLoading() {
        val state = SettingsState(projectSlug = slug)
        assertEquals("the project's variables loading", listOf("Loading..."), render(projectSettingsTree(state)))
        assertEquals("the contexts loading", listOf("Loading..."), render(orgSettingsTree(state)))
    }

    @Test
    fun testProjectSectionListsItsVariables() {
        val state =
            SettingsState(projectSlug = slug, projectEnvVars = Loadable.Loaded(listOf(EnvVar("API_URL", "xxxx.com"))))

        assertEquals(
            "the variables, with their masked values",
            listOf("API_URL xxxx.com"),
            render(projectSettingsTree(state)),
        )
    }

    @Test
    fun testOrgSectionListsContextsWithTheirVariables() {
        val contextEnvVars =
            mapOf(
                deploy.id to Loadable.Loaded(listOf(EnvVar("TOKEN", "****abcd"))),
                release.id to Loadable.Loaded(emptyList()),
            )
        val state =
            SettingsState(
                projectSlug = slug,
                contexts = Loadable.Loaded(listOf(deploy, release)),
                contextEnvVars = contextEnvVars,
            )

        assertEquals(
            "each context's variables under it",
            listOf("deploy", "  TOKEN ****abcd", "release", "  No environment variables"),
            render(orgSettingsTree(state)),
        )
    }

    @Test
    fun testMoreContextsRowWhileThereAreMore() {
        val loading = SettingsState(projectSlug = slug, contexts = Loadable.Loaded(listOf(deploy)), moreContexts = true)
        val failed = loading.copy(moreContextsError = "Forbidden")

        assertEquals(
            "the next page loading, after those listed",
            listOf("deploy", "  Loading...", "Loading more contexts..."),
            render(orgSettingsTree(loading)),
        )
        assertEquals(
            "why the next page failed",
            "Couldn't load more contexts: Forbidden. Click to retry",
            orgSettingsTree(failed).roots.last().data.label,
        )
        assertEquals(
            "none once the last page is listed",
            listOf("deploy", "  Loading..."),
            render(orgSettingsTree(loading.copy(moreContexts = false))),
        )
    }

    @Test
    fun testFailuresShowAsErrorRows() {
        val state =
            SettingsState(
                projectSlug = slug,
                projectEnvVars = Loadable.Failed("Forbidden"),
                contexts = Loadable.Loaded(emptyList()),
            )
        val tree = projectSettingsTree(state)

        assertEquals("the error in place of the list", listOf("Forbidden"), render(tree))
        assertEquals("marked as an error", true, (tree.roots.single().data as SettingsNode.Message).error)
        assertEquals("no contexts", listOf("No contexts"), render(orgSettingsTree(state)))
    }

    @Test
    fun testContextsOpenToTheirVariables() {
        val roots = orgSettingsTree(SettingsState(projectSlug = slug, contexts = Loadable.Loaded(listOf(deploy)))).roots

        assertTrue("a node, to open", roots.single() is Tree.Element.Node)
    }

    @Test
    fun testKeysFollowWhatTheRowsShow() {
        val project = EnvVarOwner.Project(slug)
        val context = EnvVarOwner.OrgContext(deploy)

        assertEquals(
            "a project variable, by its name",
            "project-env-vars/var:API_URL",
            SettingsNode.Variable(project, EnvVar("API_URL", "xxxx.com")).key,
        )
        assertEquals("a context's list, by its id", "context:c-1", SettingsNode.EnvVars(context).key)
        assertEquals(
            "a context variable, by its list and name",
            "context:c-1/var:TOKEN",
            SettingsNode.Variable(context, EnvVar("TOKEN", "****abcd")).key,
        )
    }
}
