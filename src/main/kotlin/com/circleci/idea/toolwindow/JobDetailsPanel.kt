package com.circleci.idea.toolwindow

import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.ssh.CircleCISshService
import com.circleci.idea.ssh.SshValidationResult
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.JobDetails
import com.circleci.idea.state.JobStep
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.event.TreeSelectionListener
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Panel for displaying job details including metadata, steps, and output.
 */
class JobDetailsPanel(private val project: Project) : JBPanel<JobDetailsPanel>(BorderLayout()), Disposable {
    private val logger = CircleCILogger.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val jobDetailsService = project.getService(JobDetailsService::class.java)
    private val sshService = CircleCISshService.getInstance(project)

    // UI Components
    private val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())
    private val headerPanel = JBPanel<JBPanel<*>>(GridBagLayout())
    private val contentPanel = JBPanel<JBPanel<*>>(BorderLayout())
    private val loadingLabel = JBLabel("Loading job details...", SwingConstants.CENTER)
    private val errorLabel = JBLabel("", SwingConstants.CENTER)
    private val emptyLabel = JBLabel("Select a job to view details", SwingConstants.CENTER)

    // Job metadata labels
    private val jobNameLabel = JBLabel()
    private val jobStatusLabel = JBLabel()
    private val jobNumberLabel = JBLabel()
    private val jobDurationLabel = JBLabel()
    private val resourceClassLabel = JBLabel()

    // Steps tree
    private val stepsTree = Tree(DefaultTreeModel(DefaultMutableTreeNode("Steps")))

    // Output viewer
    private val outputTextArea = JBTextArea()
    private val outputScrollPane = JBScrollPane(outputTextArea)

    // Test results table
    private val testResultsTableModel =
        DefaultTableModel(
            arrayOf("Name", "Status", "Duration", "File"),
            0,
        )
    private val testResultsTable = JBTable(testResultsTableModel)
    private val testResultsScrollPane = JBScrollPane(testResultsTable)

    // Artifacts panel
    private val artifactsPanel = ArtifactsPanel(project)

    // Tabs for output, tests, and artifacts
    private val rightTabbedPane = JBTabbedPane()

    // Split pane for steps and output/tests
    private val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

    // Action buttons
    private val rerunButton = JButton("Rerun")
    private val rerunWithSshButton = JButton("Rerun with SSH")
    private val cancelButton = JButton("Cancel")
    private val connectSshButton = JButton("Connect SSH")
    private val copySshButton = JButton("Copy SSH Command")
    private val openInBrowserButton = JButton("Open in Browser")

    init {
        Disposer.register(project, this)
        setupUI()
        observeState()
    }

    private fun setupUI() {
        errorLabel.foreground = JBColor.RED
        errorLabel.isVisible = false

        // Setup header with job metadata
        setupHeader()

        // Setup content area with steps tree
        setupContent()

        // Add components to main panel
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        mainPanel.add(contentPanel, BorderLayout.CENTER)

        // Show empty state initially
        showEmptyState()

        add(mainPanel, BorderLayout.CENTER)
    }

    private fun setupHeader() {
        headerPanel.border = JBUI.Borders.empty(10)

        val gbc = GridBagConstraints()
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(5)

        // Job name and status row
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        headerPanel.add(jobNameLabel, gbc)

        gbc.gridx = 2
        gbc.gridwidth = 1
        headerPanel.add(jobStatusLabel, gbc)

        // Job number and duration row
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 1
        headerPanel.add(JBLabel("Job #:"), gbc)

        gbc.gridx = 1
        headerPanel.add(jobNumberLabel, gbc)

        gbc.gridx = 2
        headerPanel.add(JBLabel("Duration:"), gbc)

        gbc.gridx = 3
        headerPanel.add(jobDurationLabel, gbc)

        // Resource class row
        gbc.gridx = 0
        gbc.gridy = 2
        headerPanel.add(JBLabel("Resource Class:"), gbc)

        gbc.gridx = 1
        gbc.gridwidth = 2
        headerPanel.add(resourceClassLabel, gbc)

        // Action buttons row
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.gridwidth = 4
        val buttonPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(rerunButton)
        buttonPanel.add(rerunWithSshButton)
        buttonPanel.add(cancelButton)
        buttonPanel.add(connectSshButton)
        buttonPanel.add(copySshButton)
        buttonPanel.add(openInBrowserButton)
        headerPanel.add(buttonPanel, gbc)

        setupActionButtons()
    }

    private fun setupContent() {
        // Configure steps tree
        stepsTree.isRootVisible = false
        stepsTree.showsRootHandles = false
        stepsTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        stepsTree.cellRenderer = JobStepTreeCellRenderer()

        // Add tree selection listener to load step output
        stepsTree.addTreeSelectionListener(
            TreeSelectionListener { e ->
                val selectedNode = stepsTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                selectedNode?.let { handleStepSelection(it) }
            },
        )

        val stepsScrollPane = JBScrollPane(stepsTree)
        stepsScrollPane.preferredSize = Dimension(400, 300)

        // Configure output text area
        outputTextArea.isEditable = false
        outputTextArea.lineWrap = false
        outputTextArea.wrapStyleWord = false
        outputTextArea.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
        outputTextArea.border = JBUI.Borders.empty(5)

        // Configure test results table
        testResultsTable.fillsViewportHeight = true
        testResultsTable.autoCreateRowSorter = true

        // Setup tabbed pane
        rightTabbedPane.addTab("Output", outputScrollPane)
        rightTabbedPane.addTab("Tests", testResultsScrollPane)
        rightTabbedPane.addTab("Artifacts", artifactsPanel)

        // Setup split pane
        splitPane.leftComponent = stepsScrollPane
        splitPane.rightComponent = rightTabbedPane
        splitPane.dividerLocation = 400
        splitPane.resizeWeight = 0.4

        contentPanel.add(splitPane, BorderLayout.CENTER)
    }

    private fun setupActionButtons() {
        rerunButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                val jobDetails = state.jobDetails
                if (jobDetails != null) {
                    // Note: Rerun requires workflow ID which we don't have in JobDetails
                    // For now, show a message to use the workflow rerun action
                    Messages.showInfoMessage(
                        project,
                        "To rerun a job, use the 'Rerun Workflow' action from the pipelines tree",
                        "Rerun Job",
                    )
                }
            }
        }

        rerunWithSshButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                val jobDetails = state.jobDetails
                val workflowId = state.selectedWorkflowId

                if (jobDetails != null && jobDetails.id != null && workflowId != null) {
                    // Validate SSH availability
                    val validation = sshService.validateSshDetails(jobDetails)
                    when (validation) {
                        is SshValidationResult.NotSupported -> {
                            Messages.showWarningDialog(
                                project,
                                "SSH is not available for GitHub App or GitLab projects",
                                "SSH Not Supported",
                            )
                        }
                        else -> {
                            // Confirm action
                            val result = Messages.showYesNoDialog(
                                project,
                                "Rerun job '${jobDetails.name}' with SSH enabled?\n\n" +
                                    "This will rerun the entire workflow with SSH access enabled for this specific job.",
                                "Confirm Rerun with SSH",
                                Messages.getQuestionIcon(),
                            )

                            if (result == Messages.YES) {
                                withContext(Dispatchers.IO) {
                                    val apiResult = com.circleci.idea.api.CircleCIApiService.getInstance().rerunWorkflow(
                                        workflowId = workflowId,
                                        fromFailed = false,
                                        enableSsh = true,
                                        jobs = listOf(jobDetails.id),
                                    )

                                    withContext(Dispatchers.Main) {
                                        if (apiResult.isSuccess) {
                                            Messages.showInfoMessage(
                                                project,
                                                "Workflow rerun with SSH initiated successfully.\n\n" +
                                                    "Once the job starts running, use the 'Connect SSH' button to open a terminal session.",
                                                "Rerun Successful",
                                            )
                                        } else {
                                            val error = apiResult.exceptionOrNull()?.message ?: "Unknown error"
                                            Messages.showErrorDialog(
                                                project,
                                                "Failed to rerun workflow with SSH: $error",
                                                "Rerun Failed",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Messages.showWarningDialog(
                        project,
                        "Cannot rerun job: workflow information not available.\n\n" +
                            "Try opening the job from the pipelines tree.",
                        "Cannot Rerun",
                    )
                }
            }
        }

        cancelButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                val jobDetails = state.jobDetails

                if (jobDetails != null && jobDetails.jobNumber != null && jobDetails.projectSlug != null) {
                    // Confirm action
                    val result = Messages.showYesNoDialog(
                        project,
                        "Cancel job '${jobDetails.name}'?",
                        "Confirm Cancel",
                        Messages.getQuestionIcon(),
                    )

                    if (result == Messages.YES) {
                        withContext(Dispatchers.IO) {
                            val apiResult = jobDetailsService.cancelJob(jobDetails.projectSlug, jobDetails.jobNumber)

                            withContext(Dispatchers.Main) {
                                if (apiResult.isSuccess) {
                                    Messages.showInfoMessage(
                                        project,
                                        "Job cancelled successfully",
                                        "Cancel Successful",
                                    )
                                    jobDetailsService.refreshJobDetails()
                                } else {
                                    val error = apiResult.exceptionOrNull()?.message ?: "Unknown error"
                                    Messages.showErrorDialog(
                                        project,
                                        "Failed to cancel job: $error",
                                        "Cancel Failed",
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Messages.showWarningDialog(
                        project,
                        "Cannot cancel job: job information not available",
                        "Cannot Cancel",
                    )
                }
            }
        }

        connectSshButton.addActionListener {
            val state = stateStore.jobDetails.value
            val jobDetails = state.jobDetails
            if (jobDetails != null) {
                // Validate SSH details
                val validation = sshService.validateSshDetails(jobDetails)
                when (validation) {
                    is SshValidationResult.Valid -> {
                        val sshCommand = sshService.openSshSession(jobDetails)
                        if (sshCommand != null) {
                            Messages.showInfoMessage(
                                project,
                                "Opening terminal with SSH command:\n\n$sshCommand\n\n" +
                                    "If the terminal doesn't open automatically, copy the SSH command using the 'Copy SSH Command' button.",
                                "SSH Connection",
                            )
                        } else {
                            Messages.showErrorDialog(
                                project,
                                "Failed to build SSH command. Check that SSH is enabled for this job.",
                                "SSH Connection Error",
                            )
                        }
                    }
                    is SshValidationResult.NotEnabled -> {
                        Messages.showWarningDialog(
                            project,
                            "SSH is not enabled for this job. Use 'Rerun with SSH' to enable it.",
                            "SSH Not Enabled",
                        )
                    }
                    is SshValidationResult.MissingHost -> {
                        Messages.showErrorDialog(
                            project,
                            "SSH host information is not available for this job",
                            "SSH Connection Error",
                        )
                    }
                    is SshValidationResult.NotSupported -> {
                        Messages.showWarningDialog(
                            project,
                            "SSH is not available for GitHub App or GitLab projects",
                            "SSH Not Supported",
                        )
                    }
                }
            }
        }

        copySshButton.addActionListener {
            val state = stateStore.jobDetails.value
            val jobDetails = state.jobDetails
            if (jobDetails != null) {
                val sshCommand = sshService.buildSshCommand(jobDetails)
                if (sshCommand != null) {
                    CopyPasteManager.getInstance().setContents(StringSelection(sshCommand))
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("CircleCI Notifications")
                        .createNotification(
                            "SSH Command Copied",
                            "SSH command copied to clipboard",
                            NotificationType.INFORMATION,
                        )
                        .notify(project)
                } else {
                    Messages.showWarningDialog(
                        project,
                        "SSH is not enabled for this job",
                        "SSH Not Available",
                    )
                }
            }
        }

        openInBrowserButton.addActionListener {
            val state = stateStore.jobDetails.value
            val jobDetails = state.jobDetails
            if (jobDetails?.webUrl != null) {
                com.intellij.ide.BrowserUtil.browse(jobDetails.webUrl)
            }
        }
    }

    private fun observeState() {
        scope.launch {
            stateStore.jobDetails.collectLatest { state ->
                when {
                    state.selectedJobId == null -> showEmptyState()
                    state.isLoading -> showLoadingState()
                    state.error != null -> showErrorState(state.error)
                    state.jobDetails != null -> showJobDetails(state.jobDetails)
                }
            }
        }
    }

    private fun showEmptyState() {
        headerPanel.isVisible = false
        contentPanel.isVisible = false
        errorLabel.isVisible = false
        loadingLabel.isVisible = false
        emptyLabel.isVisible = true

        // Clear artifacts
        artifactsPanel.clear()

        mainPanel.removeAll()
        mainPanel.add(emptyLabel, BorderLayout.CENTER)
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun showLoadingState() {
        headerPanel.isVisible = false
        contentPanel.isVisible = false
        errorLabel.isVisible = false
        emptyLabel.isVisible = false
        loadingLabel.isVisible = true

        mainPanel.removeAll()
        mainPanel.add(loadingLabel, BorderLayout.CENTER)
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun showErrorState(error: String) {
        headerPanel.isVisible = false
        contentPanel.isVisible = false
        loadingLabel.isVisible = false
        emptyLabel.isVisible = false
        errorLabel.text = "Error: $error"
        errorLabel.isVisible = true

        mainPanel.removeAll()
        mainPanel.add(errorLabel, BorderLayout.CENTER)
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun showJobDetails(jobDetails: JobDetails) {
        logger.info(
            "showJobDetails called for job ${jobDetails.jobNumber}: ${jobDetails.name}, steps=${jobDetails.steps.size}",
        )

        loadingLabel.isVisible = false
        errorLabel.isVisible = false
        emptyLabel.isVisible = false
        headerPanel.isVisible = true
        contentPanel.isVisible = true

        mainPanel.removeAll()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        mainPanel.add(contentPanel, BorderLayout.CENTER)

        updateJobMetadata(jobDetails)
        updateStepsTree(jobDetails.steps)
        updateActionButtons(jobDetails)

        // Load test results if available
        if (jobDetails.projectSlug != null && jobDetails.jobNumber != null) {
            loadTestResults(jobDetails.projectSlug, jobDetails.jobNumber)
            artifactsPanel.loadArtifacts(jobDetails.projectSlug, jobDetails.jobNumber)
        }

        // Clear output when switching jobs
        outputTextArea.text = "Select a step action to view output"

        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun updateJobMetadata(jobDetails: JobDetails) {
        jobNameLabel.text = "<html><b>${jobDetails.name ?: "Unknown Job"}</b></html>"
        jobStatusLabel.text = (jobDetails.status ?: "unknown").uppercase()
        jobStatusLabel.icon = getStatusIcon(jobDetails.status ?: "unknown")
        jobNumberLabel.text = jobDetails.jobNumber?.toString() ?: "N/A"
        jobDurationLabel.text = jobDetailsService.formatDuration(jobDetails.duration)
        resourceClassLabel.text = jobDetails.resourceClass ?: "N/A"

        // Set status color
        jobStatusLabel.foreground = getStatusColor(jobDetails.status ?: "unknown")
    }

    private fun updateStepsTree(steps: List<JobStep>) {
        logger.info("updateStepsTree called with ${steps.size} steps")
        steps.forEachIndexed { index, step ->
            logger.info("  Step $index: name=${step.name}, actions=${step.actions.size}")
        }

        // Count total actions across all steps
        val totalActions = steps.sumOf { it.actions.size }
        val root = DefaultMutableTreeNode("Steps ($totalActions)")

        // Flatten the tree - add actions directly to root
        for (step in steps) {
            for (action in step.actions) {
                val actionNode = DefaultMutableTreeNode(ActionNodeData(action))
                root.add(actionNode)
            }
        }

        stepsTree.model = DefaultTreeModel(root)
        logger.info("Steps tree updated with ${root.childCount} action nodes")
    }

    private fun updateActionButtons(jobDetails: JobDetails) {
        val status = jobDetails.status?.lowercase() ?: ""
        val isRunning = status in setOf("running", "queued")
        val isComplete = status in setOf("success", "failed", "canceled")

        rerunButton.isEnabled = isComplete
        rerunWithSshButton.isEnabled = isComplete
        cancelButton.isEnabled = isRunning
        connectSshButton.isEnabled = jobDetails.sshEnabled && isRunning
        copySshButton.isEnabled = jobDetails.sshEnabled
        openInBrowserButton.isEnabled = jobDetails.webUrl != null
    }

    private fun getStatusIcon(status: String): Icon? {
        return when (status.lowercase()) {
            "success" -> CircleCIIcons.Status.SUCCESS
            "failed" -> CircleCIIcons.Status.FAILED
            "running" -> CircleCIIcons.Status.RUNNING
            "canceled" -> CircleCIIcons.Status.CANCELED
            else -> null
        }
    }

    private fun getStatusColor(status: String): JBColor {
        return when (status.lowercase()) {
            "success" -> JBColor.GREEN
            "failed" -> JBColor.RED
            "running" -> JBColor.BLUE
            "canceled" -> JBColor.GRAY
            else -> JBColor.BLACK
        }
    }

    private fun handleStepSelection(node: DefaultMutableTreeNode) {
        val userObject = node.userObject

        when (userObject) {
            is ActionNodeData -> {
                val action = userObject.action
                if (action.outputUrl != null) {
                    loadStepOutput(action.outputUrl)
                } else {
                    outputTextArea.text = "No output available for this step"
                }
            }
            else -> {
                outputTextArea.text = "Select a step action to view output"
            }
        }
    }

    private fun loadStepOutput(outputUrl: String) {
        outputTextArea.text = "Loading output..."

        scope.launch {
            try {
                val result = jobDetailsService.fetchStepOutput(outputUrl)
                result.fold(
                    onSuccess = { outputLines ->
                        val outputText = outputLines.joinToString("\n") { it.message ?: "" }
                        outputTextArea.text = outputText.ifEmpty { "No output" }
                    },
                    onFailure = { error ->
                        outputTextArea.text = "Failed to load output: ${error.message}"
                    },
                )
            } catch (e: Exception) {
                outputTextArea.text = "Error loading output: ${e.message}"
            }
        }
    }

    private fun loadTestResults(
        projectSlug: String,
        jobNumber: Long,
    ) {
        scope.launch {
            try {
                val result = jobDetailsService.fetchTestResults(projectSlug, jobNumber)
                result.fold(
                    onSuccess = { tests ->
                        // Clear existing rows
                        testResultsTableModel.setRowCount(0)

                        // Add test results to table
                        tests.forEach { test ->
                            val status =
                                when (test.result?.lowercase()) {
                                    "success" -> "✓ PASSED"
                                    "failure" -> "✗ FAILED"
                                    "skipped" -> "⊘ SKIPPED"
                                    else -> test.result ?: "UNKNOWN"
                                }

                            val statusWithFlaky =
                                if (test.flaky == true) {
                                    "$status [FLAKY]"
                                } else {
                                    status
                                }

                            val duration = test.runTime?.let { String.format("%.2fs", it) } ?: "N/A"
                            val file = test.file ?: test.classname ?: "N/A"

                            testResultsTableModel.addRow(
                                arrayOf(
                                    test.name ?: "Unknown Test",
                                    statusWithFlaky,
                                    duration,
                                    file,
                                ),
                            )
                        }

                        if (tests.isEmpty()) {
                            testResultsTableModel.addRow(arrayOf("No tests found", "", "", ""))
                        }
                    },
                    onFailure = { error ->
                        testResultsTableModel.setRowCount(0)
                        testResultsTableModel.addRow(arrayOf("Failed to load tests: ${error.message}", "", "", ""))
                    },
                )
            } catch (e: Exception) {
                testResultsTableModel.setRowCount(0)
                testResultsTableModel.addRow(arrayOf("Error loading tests: ${e.message}", "", "", ""))
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
