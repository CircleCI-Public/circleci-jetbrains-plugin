package com.circleci.idea.toolwindow

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.polling.RunPollingService
import com.circleci.idea.project.CircleCIProjectService
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.toolwindow.settings.SettingsSection
import com.circleci.idea.toolwindow.settings.SettingsTreeModel
import com.circleci.idea.toolwindow.settings.SettingsTreeView
import com.circleci.idea.toolwindow.tree.CircleCITreeModel
import com.circleci.idea.toolwindow.tree.RunTreeView
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.JewelComposePanel
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JComponent

/**
 * Content for the CircleCI tool window: the run filters above a tree of
 * runs, workflows and jobs, with the project's settings and then the
 * organization's below them; or the signed-out view in their place.
 */
class CircleCIToolWindowContent(private val project: Project) : Disposable {
    private val panel = JBPanel<JBPanel<*>>(BorderLayout())

    // The runs above the settings, which are split between the project's and the organization's.
    private val splitter = OnePixelSplitter(true, SPLITTER_PROPORTION_KEY, HALF)
    private val settingsSplitter = OnePixelSplitter(true, SETTINGS_SPLITTER_PROPORTION_KEY, HALF)

    // The runs, or the signed-out view in their place until you log in.
    private val cards = CardLayout()
    private val root = JBPanel<JBPanel<*>>(cards)
    private val signedOutView = SignedOutView(project)
    private var signedIn: Boolean? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val treeModel = CircleCITreeModel(project, scope)
    private val treeView = RunTreeView(project, treeModel)
    private val settingsModel = SettingsTreeModel(project, scope)
    private val projectSettingsView = SettingsTreeView(project, settingsModel, scope, SettingsSection.PROJECT)
    private val orgSettingsView = SettingsTreeView(project, settingsModel, scope, SettingsSection.ORG)
    private val projectService = project.getService(CircleCIProjectService::class.java)
    private val authService = CircleCIAuthService.getInstance(project)
    private val pollingService = project.getService(RunPollingService::class.java)
    private val stateStore = CircleCIStateStore.getInstance(project)

    init {
        setupTree()
        setupToolbar()

        // Register the tree model with the service, for actions to read the selection
        project.getService(CircleCIToolWindowService::class.java).setTreeModel(treeModel)

        splitter.firstComponent = panel
        settingsSplitter.firstComponent = projectSettingsView.component()
        settingsSplitter.secondComponent = orgSettingsView.component()
        splitter.secondComponent = settingsSplitter
        root.add(splitter, RUNS_CARD)
        root.add(signedOutView.component, SIGNED_OUT_CARD)

        scope.launch(Dispatchers.IO) { authService.restoreAuthentication() }

        // Auto-detect projects if authenticated
        autoDetectProjects(reload = false)

        observeAuth()

        // Note: Tree will auto-reload via StateFlow listeners in CircleCITreeModel
        // when projects are detected and selectedProject is updated
    }

    /** Find the workspace's projects, which lists the selected one's runs, or with [reload] lists them again. */
    private fun autoDetectProjects(reload: Boolean) {
        scope.launch {
            // Auto-detect projects from git repositories (doesn't require auth)
            projectService.detectProjects()

            if (reload) treeModel.reloadRoot()

            // Start polling for run updates
            pollingService.startPolling()
        }
    }

    /**
     * Show the runs when there's a token that hasn't been rejected, and the
     * signed-out view otherwise; reload the runs and settings on logging in.
     */
    private fun observeAuth() {
        scope.launch {
            stateStore.auth.collect { auth ->
                // Reading the stored token goes to the credential store: keep it off the EDT.
                val hasToken = withContext(Dispatchers.IO) { authService.getToken() != null }
                val nowSignedIn = auth.isAuthenticated || (hasToken && auth.error == null)
                signedOutView.showError(auth.error.takeIf { !nowSignedIn })
                if (nowSignedIn == signedIn) return@collect

                val wasSignedOut = signedIn == false
                signedIn = nowSignedIn
                cards.show(root, if (nowSignedIn) RUNS_CARD else SIGNED_OUT_CARD)
                if (nowSignedIn && wasSignedOut) {
                    autoDetectProjects(reload = true)
                    settingsModel.refresh()
                }
            }
        }
    }

    /**
     * Get the tree model for testing/debugging.
     */
    fun getTreeModel(): CircleCITreeModel = treeModel

    private fun setupTree() {
        panel.add(JewelComposePanel { treeView.View() }, BorderLayout.CENTER)
    }

    private fun setupToolbar() {
        val filterBar = RunFilterBar(project, scope) { treeModel.reloadRoot() }
        panel.add(filterBar, BorderLayout.NORTH)
    }

    /**
     * Actions for the tool window's title bar: the project whose runs to list,
     * then refreshing, as the Pull Requests list keeps its refresh there.
     */
    fun titleActions(): List<AnAction> =
        listOf(
            com.circleci.idea.toolwindow.actions.ProjectChooserAction(),
            com.circleci.idea.toolwindow.actions.RefreshAction(scope),
            com.circleci.idea.toolwindow.actions.ToggleAutoRefreshAction(),
        )

    fun getContent(): JComponent {
        return root
    }

    override fun dispose() {
        pollingService.stopPolling()
        project.getService(CircleCIToolWindowService::class.java).clearTreeModel(treeModel)
        scope.cancel()
    }

    private companion object {
        const val RUNS_CARD = "runs"
        const val SIGNED_OUT_CARD = "signed-out"
        const val SPLITTER_PROPORTION_KEY = "CircleCI.ToolWindow.SettingsSplitter"
        const val SETTINGS_SPLITTER_PROPORTION_KEY = "CircleCI.ToolWindow.OrgSettingsSplitter"
        const val HALF = 0.5f
    }
}
