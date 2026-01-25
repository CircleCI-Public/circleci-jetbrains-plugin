package com.circleci.idea.toolwindow

import com.circleci.idea.icons.CircleCIIcons
import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.state.CircleCIStateStore
import com.circleci.idea.state.JobAction
import com.circleci.idea.state.JobDetails
import com.circleci.idea.state.JobStep
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
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
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Panel for displaying job details including metadata, steps, and output.
 */
class JobDetailsPanel(private val project: Project) : JBPanel<JobDetailsPanel>(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateStore = CircleCIStateStore.getInstance(project)
    private val jobDetailsService = project.getService(JobDetailsService::class.java)

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

    // Action buttons
    private val rerunButton = JButton("Rerun")
    private val rerunWithSshButton = JButton("Rerun with SSH")
    private val cancelButton = JButton("Cancel")
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
        buttonPanel.add(copySshButton)
        buttonPanel.add(openInBrowserButton)
        headerPanel.add(buttonPanel, gbc)

        setupActionButtons()
    }

    private fun setupContent() {
        // Configure steps tree
        stepsTree.isRootVisible = true
        stepsTree.showsRootHandles = true
        stepsTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        stepsTree.cellRenderer = JobStepTreeCellRenderer()

        val scrollPane = JBScrollPane(stepsTree)
        scrollPane.preferredSize = Dimension(400, 300)

        contentPanel.add(scrollPane, BorderLayout.CENTER)
    }

    private fun setupActionButtons() {
        rerunButton.addActionListener {
            // TODO: Implement rerun action
        }

        rerunWithSshButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                val jobDetails = state.jobDetails
                if (jobDetails?.id != null) {
                    // TODO: Need workflow ID to rerun with SSH
                    // jobDetailsService.rerunJobWithSsh(workflowId, jobDetails.id)
                }
            }
        }

        cancelButton.addActionListener {
            scope.launch {
                val state = stateStore.jobDetails.value
                val jobDetails = state.jobDetails
                if (jobDetails != null && jobDetails.jobNumber != null && jobDetails.projectSlug != null) {
                    jobDetailsService.cancelJob(jobDetails.projectSlug, jobDetails.jobNumber)
                    jobDetailsService.refreshJobDetails()
                }
            }
        }

        copySshButton.addActionListener {
            val state = stateStore.jobDetails.value
            val jobDetails = state.jobDetails
            if (jobDetails != null) {
                val sshCommand = jobDetailsService.getSshCommand(jobDetails)
                if (sshCommand != null) {
                    CopyPasteManager.getInstance().setContents(StringSelection(sshCommand))
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
        val root = DefaultMutableTreeNode("Steps (${steps.size})")

        for ((stepIndex, step) in steps.withIndex()) {
            val stepNode = DefaultMutableTreeNode(StepNodeData(step.name, stepIndex + 1))

            for (action in step.actions) {
                val actionNode = DefaultMutableTreeNode(ActionNodeData(action))
                stepNode.add(actionNode)
            }

            root.add(stepNode)
        }

        stepsTree.model = DefaultTreeModel(root)

        // Expand all nodes
        for (i in 0 until stepsTree.rowCount) {
            stepsTree.expandRow(i)
        }
    }

    private fun updateActionButtons(jobDetails: JobDetails) {
        val status = jobDetails.status?.lowercase() ?: ""
        val isRunning = status in setOf("running", "queued")
        val isComplete = status in setOf("success", "failed", "canceled")

        rerunButton.isEnabled = isComplete
        rerunWithSshButton.isEnabled = isComplete
        cancelButton.isEnabled = isRunning
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

    override fun dispose() {
        scope.cancel()
    }
}
