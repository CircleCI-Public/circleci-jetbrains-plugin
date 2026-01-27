package com.circleci.idea.settings

import com.circleci.idea.auth.CircleCIAuthService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.*

/**
 * Configurable for CircleCI settings page.
 * Provides comprehensive settings UI for the CircleCI plugin.
 */
class CircleCIConfigurable : Configurable {
    private var settingsPanel: JPanel? = null

    // Authentication settings
    private val apiTokenField = JBPasswordField()
    private val authStatusLabel = JBLabel()

    // General settings
    private val hostUrlField = JBTextField()

    // Filter settings
    private val branchFilterCombo = ComboBox(arrayOf("Current Branch", "All Branches", "Default Branch"))
    private val myPipelinesOnlyCheck = JBCheckBox("Show only my pipelines")

    // Notification settings
    private val notificationsEnabledCheck = JBCheckBox("Enable notifications")
    private val notifyMyPipelinesOnlyCheck = JBCheckBox("Notify only for my pipelines")

    // SSH settings
    private val githubSshKeyField = JBTextField()
    private val bitbucketSshKeyField = JBTextField()
    private val windowsShellCombo = ComboBox(arrayOf("Default", "Bash (WSL)", "PowerShell", "CMD"))

    // Advanced settings
    private val logLevelCombo = ComboBox(arrayOf("ERROR", "WARN", "INFO", "DEBUG"))
    private val telemetryEnabledCheck = JBCheckBox("Enable anonymous telemetry")

    override fun getDisplayName(): String {
        return "CircleCI"
    }

    override fun createComponent(): JComponent {
        val settings = CircleCISettings.getInstance()

        // Load current settings
        loadSettings(settings)

        // Build UI
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = JBUI.Borders.empty(10)

        val formBuilder = FormBuilder.createFormBuilder()

        // Authentication section
        formBuilder.addSeparator(5)
        formBuilder.addComponent(JBLabel("<html><b>Authentication</b></html>"))
        formBuilder.addLabeledComponent(JBLabel("API Token:"), apiTokenField)
        formBuilder.addComponent(authStatusLabel)

        // General section
        formBuilder.addSeparator(5)
        formBuilder.addComponent(JBLabel("<html><b>General</b></html>"))
        formBuilder.addLabeledComponent(JBLabel("Host URL:"), hostUrlField)

        // Filters section
        formBuilder.addSeparator(5)
        formBuilder.addComponent(JBLabel("<html><b>Filters</b></html>"))
        formBuilder.addLabeledComponent(JBLabel("Branch filter:"), branchFilterCombo)
        formBuilder.addComponent(myPipelinesOnlyCheck)

        // Notifications section
        formBuilder.addSeparator(5)
        formBuilder.addComponent(JBLabel("<html><b>Notifications</b></html>"))
        formBuilder.addComponent(notificationsEnabledCheck)
        formBuilder.addComponent(notifyMyPipelinesOnlyCheck)

        // SSH Configuration section
        formBuilder.addSeparator(5)
        formBuilder.addComponent(JBLabel("<html><b>SSH Configuration</b></html>"))
        formBuilder.addLabeledComponent(JBLabel("GitHub SSH key path:"), githubSshKeyField)
        formBuilder.addLabeledComponent(JBLabel("Bitbucket SSH key path:"), bitbucketSshKeyField)
        formBuilder.addLabeledComponent(JBLabel("Windows shell:"), windowsShellCombo)

        // Advanced section
        formBuilder.addSeparator(5)
        formBuilder.addComponent(JBLabel("<html><b>Advanced</b></html>"))
        formBuilder.addLabeledComponent(JBLabel("Log level:"), logLevelCombo)
        formBuilder.addComponent(telemetryEnabledCheck)

        // Add vertical spacing at bottom
        formBuilder.addComponentFillVertically(JPanel(), 0)

        settingsPanel = formBuilder.panel
        mainPanel.add(settingsPanel!!, BorderLayout.NORTH)

        return mainPanel
    }

    override fun isModified(): Boolean {
        val settings = CircleCISettings.getInstance()
        val token = String(apiTokenField.password)

        // Check if token changed (only if not empty)
        val tokenChanged = token.isNotEmpty() && getAuthService()?.getToken() != token

        return tokenChanged ||
            hostUrlField.text != settings.hostUrl ||
            getBranchFilterValue() != settings.branchFilter ||
            myPipelinesOnlyCheck.isSelected != settings.myPipelinesOnly ||
            notificationsEnabledCheck.isSelected != settings.notificationsEnabled ||
            logLevelCombo.selectedItem?.toString()?.lowercase() != settings.logLevel
    }

    override fun apply() {
        val settings = CircleCISettings.getInstance()
        val token = String(apiTokenField.password)

        // Save token if provided
        if (token.isNotEmpty()) {
            val authService = getAuthService()
            if (authService != null) {
                val result = authService.login(token, hostUrlField.text)
                result.fold(
                    onSuccess = {
                        authStatusLabel.text = "<html><font color='green'>✓ Authenticated</font></html>"
                    },
                    onFailure = { error ->
                        authStatusLabel.text = "<html><font color='red'>✗ Authentication failed: ${error.message}</font></html>"
                    },
                )
            }
        }

        settings.hostUrl = hostUrlField.text
        settings.branchFilter = getBranchFilterValue()
        settings.myPipelinesOnly = myPipelinesOnlyCheck.isSelected
        settings.notificationsEnabled = notificationsEnabledCheck.isSelected
        settings.logLevel = logLevelCombo.selectedItem?.toString()?.lowercase() ?: "info"
    }

    override fun reset() {
        val settings = CircleCISettings.getInstance()
        loadSettings(settings)
    }

    /**
     * Load settings into UI components.
     */
    private fun loadSettings(settings: CircleCISettings) {
        // Clear API token field for security (don't pre-fill passwords)
        apiTokenField.text = ""

        // Update auth status
        val authService = getAuthService()
        if (authService?.isAuthenticated() == true) {
            authStatusLabel.text = "<html><font color='green'>✓ Authenticated</font></html>"
        } else {
            authStatusLabel.text = "<html><font color='gray'>Not authenticated</font></html>"
        }

        hostUrlField.text = settings.hostUrl
        myPipelinesOnlyCheck.isSelected = settings.myPipelinesOnly
        notificationsEnabledCheck.isSelected = settings.notificationsEnabled

        // Set branch filter
        branchFilterCombo.selectedIndex =
            when (settings.branchFilter) {
                "current" -> 0
                "all" -> 1
                "default" -> 2
                else -> 0
            }

        // Set log level
        logLevelCombo.selectedIndex =
            when (settings.logLevel.lowercase()) {
                "error" -> 0
                "warn" -> 1
                "info" -> 2
                "debug" -> 3
                else -> 2
            }
    }

    /**
     * Get branch filter value from combo box.
     */
    private fun getBranchFilterValue(): String {
        return when (branchFilterCombo.selectedIndex) {
            0 -> "current"
            1 -> "all"
            2 -> "default"
            else -> "current"
        }
    }

    /**
     * Get auth service from the default project.
     */
    private fun getAuthService(): CircleCIAuthService? {
        return try {
            val project = ProjectManager.getInstance().defaultProject
            CircleCIAuthService.getInstance(project)
        } catch (e: Exception) {
            null
        }
    }
}
