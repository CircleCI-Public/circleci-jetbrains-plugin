package com.circleci.idea.toolwindow

import com.circleci.idea.job.JobDetailsService
import com.circleci.idea.state.Artifact
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.swing.*

/**
 * Panel for displaying and managing job artifacts.
 */
class ArtifactsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobDetailsService = project.getService(JobDetailsService::class.java)

    private val artifactListModel = DefaultListModel<Artifact>()
    private val artifactList = JBList(artifactListModel)
    private val scrollPane = JBScrollPane(artifactList)

    private val emptyLabel = JLabel("No artifacts available", SwingConstants.CENTER)
    private val loadingLabel = JLabel("Loading artifacts...", SwingConstants.CENTER)

    init {
        setupUI()
    }

    private fun setupUI() {
        // Configure artifact list
        artifactList.cellRenderer = ArtifactCellRenderer()
        artifactList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Add double-click to download
        artifactList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedArtifact = artifactList.selectedValue
                    if (selectedArtifact != null) {
                        downloadArtifact(selectedArtifact)
                    }
                }
            }
        })

        // Add right-click context menu
        artifactList.componentPopupMenu = createContextMenu()

        // Show empty state initially
        showEmptyState()
    }

    /**
     * Load artifacts for a job.
     */
    fun loadArtifacts(projectSlug: String, jobNumber: Long) {
        showLoadingState()

        scope.launch {
            try {
                val result = jobDetailsService.fetchArtifacts(projectSlug, jobNumber)
                result.fold(
                    onSuccess = { artifacts ->
                        if (artifacts.isEmpty()) {
                            showEmptyState()
                        } else {
                            showArtifacts(artifacts)
                        }
                    },
                    onFailure = { error ->
                        showErrorState("Failed to load artifacts: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                showErrorState("Error loading artifacts: ${e.message}")
            }
        }
    }

    /**
     * Clear artifacts list.
     */
    fun clear() {
        artifactListModel.clear()
        showEmptyState()
    }

    private fun showEmptyState() {
        removeAll()
        emptyLabel.text = "No artifacts available"
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun showLoadingState() {
        removeAll()
        add(loadingLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun showErrorState(message: String) {
        removeAll()
        emptyLabel.text = message
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun showArtifacts(artifacts: List<Artifact>) {
        artifactListModel.clear()
        artifacts.forEach { artifactListModel.addElement(it) }

        removeAll()
        add(scrollPane, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Create context menu for artifacts.
     */
    private fun createContextMenu(): JPopupMenu {
        val menu = JPopupMenu()

        val downloadAction = JMenuItem("Download", AllIcons.Actions.Download)
        downloadAction.addActionListener {
            val selectedArtifact = artifactList.selectedValue
            if (selectedArtifact != null) {
                downloadArtifact(selectedArtifact)
            }
        }
        menu.add(downloadAction)

        val openInBrowserAction = JMenuItem("Open in Browser", AllIcons.General.Web)
        openInBrowserAction.addActionListener {
            val selectedArtifact = artifactList.selectedValue
            if (selectedArtifact?.url != null) {
                com.intellij.ide.BrowserUtil.browse(selectedArtifact.url)
            }
        }
        menu.add(openInBrowserAction)

        val copyUrlAction = JMenuItem("Copy URL", AllIcons.Actions.Copy)
        copyUrlAction.addActionListener {
            val selectedArtifact = artifactList.selectedValue
            if (selectedArtifact?.url != null) {
                CopyPasteManager.getInstance().setContents(StringSelection(selectedArtifact.url))
            }
        }
        menu.add(copyUrlAction)

        return menu
    }

    /**
     * Download an artifact to local machine.
     */
    private fun downloadArtifact(artifact: Artifact) {
        if (artifact.url == null) {
            Messages.showErrorDialog(project, "Artifact URL not available", "Download Failed")
            return
        }

        // Show file chooser
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = "Save Artifact"
        fileChooser.selectedFile = File(artifact.prettyPath ?: artifact.path ?: "artifact")

        val result = fileChooser.showSaveDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            val targetFile = fileChooser.selectedFile

            // Download with progress indicator
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading ${artifact.prettyPath ?: "artifact"}", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Downloading ${artifact.prettyPath ?: "artifact"}..."
                        indicator.isIndeterminate = false

                        val url = URL(artifact.url)
                        val connection = url.openConnection()
                        val contentLength = connection.contentLengthLong

                        connection.getInputStream().use { input ->
                            FileOutputStream(targetFile).use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalBytesRead = 0L

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (indicator.isCanceled) {
                                        targetFile.delete()
                                        return
                                    }

                                    output.write(buffer, 0, bytesRead)
                                    totalBytesRead += bytesRead

                                    if (contentLength > 0) {
                                        indicator.fraction = totalBytesRead.toDouble() / contentLength
                                        indicator.text = "Downloaded ${formatBytes(totalBytesRead)} of ${formatBytes(contentLength)}"
                                    }
                                }
                            }
                        }

                        SwingUtilities.invokeLater {
                            Messages.showInfoMessage(
                                project,
                                "Artifact downloaded successfully to:\n${targetFile.absolutePath}",
                                "Download Complete"
                            )
                        }

                    } catch (e: Exception) {
                        if (targetFile.exists()) {
                            targetFile.delete()
                        }

                        SwingUtilities.invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "Failed to download artifact: ${e.message}",
                                "Download Failed"
                            )
                        }
                    }
                }
            })
        }
    }

    /**
     * Format bytes to human-readable string.
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * Custom cell renderer for artifacts.
     */
    private class ArtifactCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            if (value is Artifact) {
                text = value.prettyPath ?: value.path ?: "Unknown artifact"
                icon = getArtifactIcon(value.path)
                border = JBUI.Borders.empty(4, 8)
            }

            return this
        }

        private fun getArtifactIcon(path: String?): Icon {
            return when {
                path == null -> AllIcons.FileTypes.Any_type
                path.endsWith(".jar") || path.endsWith(".war") -> AllIcons.FileTypes.Archive
                path.endsWith(".log") || path.endsWith(".txt") -> AllIcons.FileTypes.Text
                path.endsWith(".json") -> AllIcons.FileTypes.Json
                path.endsWith(".xml") -> AllIcons.FileTypes.Xml
                path.endsWith(".html") -> AllIcons.FileTypes.Html
                path.endsWith(".pdf") -> AllIcons.FileTypes.Any_type
                path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".gif") -> AllIcons.FileTypes.Any_type
                else -> AllIcons.FileTypes.Any_type
            }
        }
    }
}
