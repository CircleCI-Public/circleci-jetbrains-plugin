package com.circleci.idea.job

import com.circleci.idea.state.Artifact
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.BinaryLightVirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * A job's artifacts as a file tree: open one in the IDE, download a file or
 * folder, or open it in the browser.
 */
class ArtifactsPanel(
    private val project: Project,
    private val ref: JobRef,
    private val scope: CoroutineScope,
) : JBPanel<ArtifactsPanel>(BorderLayout()) {
    private val service = JobDetailsService.getInstance(project)

    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(model)

    // Set when the job ran in parallel, so downloads keep executions apart.
    private var parallel = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = ArtifactCellRenderer()
        tree.emptyText.text = "Artifacts load once the job ends"
        TreeSpeedSearch.installOn(tree)
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) selectedFile()?.let { open(it) }
                }
            },
        )

        val actions =
            DefaultActionGroup().apply {
                add(OpenArtifactAction())
                add(DownloadArtifactsAction())
                add(OpenArtifactInBrowserAction())
                add(CopyArtifactUrlAction())
            }
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actions, true)
        toolbar.targetComponent = tree
        add(toolbar.component, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    /** List the job's artifacts. Call on the EDT. */
    fun load() {
        tree.emptyText.text = "Loading artifacts..."
        scope.launch {
            service.fetchArtifacts(ref.jobId).fold(
                onSuccess = { artifacts ->
                    parallel = artifacts.map { it.execution }.distinct().size > 1
                    model.setRoot(ArtifactTree.build(artifacts))
                    TreeUtil.expand(tree, 2)
                    tree.emptyText.text = "This job stored no artifacts"
                },
                onFailure = { tree.emptyText.text = "Failed to load artifacts: ${it.message}" },
            )
        }
    }

    private fun selectedNode(): DefaultMutableTreeNode? = tree.lastSelectedPathComponent as? DefaultMutableTreeNode

    private fun selectedFile(): Artifact? = (selectedNode()?.userObject as? ArtifactNode.File)?.artifact

    /**
     * Open an artifact in an editor tab: as text when it is text, otherwise
     * for the IDE to show as best it can (an image, say).
     */
    private fun open(artifact: Artifact) {
        scope.launch {
            service.readArtifact(artifact).fold(
                onSuccess = { bytes ->
                    val name = artifact.path.substringAfterLast('/')
                    val text = decodeText(bytes)
                    val file =
                        if (text != null) {
                            LightVirtualFile(name, FileTypeManager.getInstance().getFileTypeByFileName(name), text)
                        } else {
                            BinaryLightVirtualFile(name, bytes)
                        }
                    file.isWritable = false
                    FileEditorManager.getInstance(project).openFile(file, true)
                },
                onFailure = { Messages.showErrorDialog(project, it.message ?: "Unknown error", "Can't Open Artifact") },
            )
        }
    }

    private fun download(artifacts: List<Artifact>) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Download Artifacts To")
        val dir = FileChooser.chooseFile(descriptor, project, null)?.toNioPath() ?: return
        val parallel = parallel

        object : Task.Backgroundable(project, "Downloading artifacts", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val failures = mutableListOf<String>()
                artifacts.forEachIndexed { index, artifact ->
                    indicator.checkCanceled()
                    indicator.text2 = artifact.path
                    indicator.fraction = index.toDouble() / artifacts.size
                    val target = ArtifactTree.downloadTarget(dir, artifact, parallel)
                    val result =
                        target?.let { service.downloadArtifact(artifact, it) }
                            ?: Result.failure(IllegalArgumentException("its path leaves the download folder"))
                    result.exceptionOrNull()?.let { failures.add("${artifact.path}: ${it.message}") }
                }
                notifyDownloaded(dir, artifacts.size - failures.size, failures)
            }
        }.queue()
    }

    private fun notifyDownloaded(
        dir: Path,
        downloaded: Int,
        failures: List<String>,
    ) {
        val content =
            buildString {
                append("Downloaded $downloaded artifact${if (downloaded == 1) "" else "s"} to $dir")
                if (failures.isNotEmpty()) append("<br/>Failed: ${failures.joinToString("<br/>")}")
            }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CircleCI Notifications")
            .createNotification(
                content,
                if (failures.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimple(
                    RevealFileAction.getActionName(),
                ) { RevealFileAction.openDirectory(dir) },
            )
            .notify(project)
    }

    private abstract inner class ArtifactAction(
        text: String,
        description: String,
        icon: javax.swing.Icon,
    ) : AnAction(text, description, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenArtifactAction :
        ArtifactAction("Open", "Open the selected artifact in the IDE", AllIcons.Actions.MenuOpen) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedFile() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedFile()?.let { open(it) }
        }
    }

    private inner class DownloadArtifactsAction :
        ArtifactAction(
            "Download...",
            "Download the selected artifact or folder, or all of them",
            AllIcons.Actions.Download,
        ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled =
                ArtifactTree.files(
                    selectedNode() ?: model.root as DefaultMutableTreeNode,
                ).isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            download(ArtifactTree.files(selectedNode() ?: model.root as DefaultMutableTreeNode))
        }
    }

    private inner class OpenArtifactInBrowserAction :
        ArtifactAction(
            "Open in Browser",
            "Open the selected artifact in the browser",
            AllIcons.Ide.External_link_arrow,
        ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedFile() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedFile()?.let { BrowserUtil.browse(it.url) }
        }
    }

    private inner class CopyArtifactUrlAction :
        ArtifactAction("Copy URL", "Copy the selected artifact's URL", AllIcons.Actions.Copy) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedFile() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedFile()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.url)) }
        }
    }

    private class ArtifactCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? ArtifactNode ?: return
            icon =
                when (node) {
                    is ArtifactNode.Execution -> AllIcons.Nodes.Module
                    is ArtifactNode.Directory -> AllIcons.Nodes.Folder
                    is ArtifactNode.File -> FileTypeManager.getInstance().getFileTypeByFileName(node.name).icon
                }
            append(node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    private companion object {
        /** The bytes as text if they're valid UTF-8 without NULs, as the CLI decides what it can page. */
        fun decodeText(bytes: ByteArray): String? {
            if (bytes.contains(0)) return null
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
