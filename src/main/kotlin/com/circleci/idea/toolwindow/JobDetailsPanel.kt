package com.circleci.idea.toolwindow

import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.JobDetails
import com.circleci.idea.state.JobStep
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JSplitPane
import javax.swing.SwingConstants
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

    // UI Components
    private val mainPanel = JBPanel<JBPanel<*>>(BorderLayout())
    private val headerPanel = JBPanel<JBPanel<*>>()
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

    // Polling for job updates
    private var pollingJob: Job? = null

    init {
        Disposer.register(project, this)
        setupUI()
        observeState()
        setupPeriodicRefresh()
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
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)

        // Title row: Job name and status
        val titlePanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 10, 5))
        titlePanel.add(jobNameLabel)
        titlePanel.add(jobStatusLabel)
        headerPanel.add(titlePanel)

        // Metadata row: Job number, duration, resource class
        val metadataPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 20, 5))
        metadataPanel.add(JBLabel("Job #:"))
        metadataPanel.add(jobNumberLabel)
        metadataPanel.add(JBLabel("•"))
        metadataPanel.add(JBLabel("Duration:"))
        metadataPanel.add(jobDurationLabel)
        metadataPanel.add(JBLabel("•"))
        metadataPanel.add(JBLabel("Resource:"))
        metadataPanel.add(resourceClassLabel)
        headerPanel.add(metadataPanel)

        // Action buttons row
        val buttonPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 5))
        buttonPanel.add(rerunButton)
        buttonPanel.add(rerunWithSshButton)
        buttonPanel.add(cancelButton)
        buttonPanel.add(connectSshButton)
        buttonPanel.add(copySshButton)
        buttonPanel.add(openInBrowserButton)
        headerPanel.add(buttonPanel)

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
            TreeSelectionListener {
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
        // Create action handlers
        val rerunHandler = com.circleci.idea.toolwindow.actions.RerunJobHandler(project)
        val rerunSshHandler = com.circleci.idea.toolwindow.actions.RerunWithSshHandler(project)
        val cancelHandler = com.circleci.idea.toolwindow.actions.CancelJobHandler(project)
        val connectSshHandler = com.circleci.idea.toolwindow.actions.ConnectSshHandler(project)
        val copySshHandler = com.circleci.idea.toolwindow.actions.CopySshHandler(project)
        val openBrowserHandler = com.circleci.idea.toolwindow.actions.OpenInBrowserHandler(project)

        // Wire up button listeners
        rerunButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                handleAction(rerunHandler, state)
            }
        }

        rerunWithSshButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                handleAction(rerunSshHandler, state)
            }
        }

        cancelButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                handleAction(cancelHandler, state)
            }
        }

        connectSshButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                handleAction(connectSshHandler, state)
            }
        }

        copySshButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                handleAction(copySshHandler, state)
            }
        }

        openInBrowserButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                handleAction(openBrowserHandler, state)
            }
        }
    }

    /**
     * Handle an action by checking availability and executing if available.
     */
    private suspend fun handleAction(
        handler: com.circleci.idea.toolwindow.actions.JobActionHandler,
        state: com.circleci.idea.state.JobDetailsState,
    ) {
        when (val availability = handler.isAvailable(state)) {
            is com.circleci.idea.toolwindow.actions.ActionAvailability.Available -> {
                handler.execute(state)
            }
            is com.circleci.idea.toolwindow.actions.ActionAvailability.Unavailable -> {
                Messages.showWarningDialog(
                    project,
                    availability.reason,
                    availability.title,
                )
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

    /**
     * Sets up periodic refresh of job details while a job is running.
     * Polls every 10 seconds to update SSH status, step output, and job state.
     */
    private fun setupPeriodicRefresh() {
        pollingJob?.cancel()
        pollingJob =
            scope.launch {
                while (isActive) {
                    val state = stateStore.jobDetails.value
                    val jobDetails = state.jobDetails

                    // Only poll if we have a selected job
                    if (state.selectedJobId != null) {
                        val status = jobDetails?.status?.lowercase() ?: ""
                        val isRunning = status in setOf("running", "queued")

                        if (isRunning) {
                            // Job is running - poll frequently to catch SSH availability and step updates
                            try {
                                logger.debug("Polling job details for running job: ${state.selectedJobId}")
                                jobDetailsService.refreshJobDetails()
                            } catch (e: Exception) {
                                logger.error("Failed to refresh job details during polling", e)
                            }
                            delay(10_000L) // Poll every 10 seconds for running jobs
                        } else {
                            // Job is not running - check less frequently
                            delay(30_000L) // Check every 30 seconds
                        }
                    } else {
                        // No job selected - check infrequently
                        delay(5_000L) // Check every 5 seconds for job selection
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

        // Force refresh of header panel to ensure button states are visible
        headerPanel.revalidate()
        headerPanel.repaint()

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
        // Job name with larger font
        val jobName = jobDetails.name ?: "Unknown Job"
        jobNameLabel.text = jobName
        jobNameLabel.font = jobNameLabel.font.deriveFont(16f).deriveFont(java.awt.Font.BOLD)

        // Status with icon
        val status = (jobDetails.status ?: "unknown").uppercase()
        jobStatusLabel.text = status
        jobStatusLabel.icon = getStatusIcon(jobDetails.status ?: "unknown")
        jobStatusLabel.foreground = getStatusColor(jobDetails.status ?: "unknown")

        // Metadata
        jobNumberLabel.text = jobDetails.jobNumber?.toString() ?: "N/A"
        jobDurationLabel.text = jobDetailsService.formatDuration(jobDetails.duration)
        resourceClassLabel.text = jobDetails.resourceClass ?: "N/A"
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

                            val duration =
                                test.runTime?.let {
                                    String.format(
                                        java.util.Locale.ROOT,
                                        "%.2fs",
                                        it,
                                    )
                                } ?: "N/A"
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
        pollingJob?.cancel()
        scope.cancel()
    }
}
