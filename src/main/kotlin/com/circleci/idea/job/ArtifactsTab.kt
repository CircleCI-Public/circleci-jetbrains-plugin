package com.circleci.idea.job

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circleci.idea.state.Artifact
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.BinaryLightVirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.icon.fromPlatformIcon
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.component.SpeedSearchArea
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.search.SpeedSearchableTree
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.StringSelection
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

/**
 * A job's artifacts as a file tree: open one in the IDE, download a file or
 * folder, or open it in the browser.
 */
class ArtifactsTab(
    private val project: Project,
    private val ref: JobRef,
    private val scope: CoroutineScope,
) {
    private val service = JobDetailsService.getInstance(project)

    private val _content =
        MutableStateFlow<ArtifactsContent>(ArtifactsContent.Waiting("Artifacts load once the job ends"))
    val content: StateFlow<ArtifactsContent> = _content.asStateFlow()

    private val _selected = MutableStateFlow<ArtifactEntry?>(null)
    val selected: StateFlow<ArtifactEntry?> = _selected.asStateFlow()

    /** Which directories are open, and what's selected, kept while the view is off screen. */
    private var openIds: Set<Any> = emptySet()

    // The files open in editors, by URL, to show again rather than read again. Touched on the EDT.
    private val opened = mutableMapOf<String, VirtualFile>()

    private val fileIcons = FileIcons()

    init {
        // Let an artifact's file go as its editor tab closes, rather than hold what was read of it.
        project.messageBus.connect(scope).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) {
                    if (!source.isFileOpen(file)) opened.values.remove(file)
                }
            },
        )
    }

    /** List the job's artifacts. Call on the EDT. */
    fun load() {
        _content.value = ArtifactsContent.Waiting("Loading artifacts...")
        scope.launch {
            _content.value =
                service.fetchArtifacts(ref.jobId).fold(
                    onSuccess = { artifacts ->
                        if (artifacts.isEmpty()) {
                            ArtifactsContent.Waiting("This job stored no artifacts")
                        } else {
                            val entries = ArtifactTree.build(artifacts)
                            openIds = initiallyOpen(entries)
                            ArtifactsContent.Loaded(
                                entries,
                                files = ArtifactTree.files(entries),
                                parallel = artifacts.map { it.execution }.distinct().size > 1,
                            )
                        }
                    },
                    onFailure = { ArtifactsContent.Waiting("Failed to load artifacts: ${it.message}") },
                )
            _selected.value = null
        }
    }

    @Composable
    fun View() {
        val content by content.collectAsState()
        val selected by selected.collectAsState()
        Column(Modifier.fillMaxSize()) {
            val loaded = content as? ArtifactsContent.Loaded
            Toolbar(selected, loaded)
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            when (val current = content) {
                is ArtifactsContent.Waiting -> Placeholder(current.text)
                is ArtifactsContent.Loaded -> ArtifactTreeView(current)
            }
        }
    }

    @Composable
    private fun Toolbar(
        selected: ArtifactEntry?,
        loaded: ArtifactsContent.Loaded?,
    ) {
        val file = (selected?.node as? ArtifactNode.File)?.artifact
        // Download takes what's selected, or everything.
        val toDownload = selected?.files ?: loaded?.files.orEmpty()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolbarButton(AllIconsKeys.Actions.MenuOpen, "Open in the IDE", file != null) { file?.let(::open) }
            ToolbarButton(
                AllIconsKeys.Actions.Download,
                if (selected == null) "Download all..." else "Download...",
                toDownload.isNotEmpty(),
            ) { download(toDownload, loaded?.parallel ?: false) }
            ToolbarButton(AllIconsKeys.Ide.External_link_arrow, "Open in Browser", file != null) {
                file?.let { BrowserUtil.browse(it.url) }
            }
            ToolbarButton(AllIconsKeys.Actions.Copy, "Copy URL", file != null) { file?.let(::copyUrl) }
            Spacer(Modifier.weight(1f))
            loaded?.let {
                val count = it.files.size
                Text(
                    "$count artifact${if (count == 1) "" else "s"}",
                    color = JewelTheme.globalColors.text.info,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }

    @OptIn(ExperimentalJewelApi::class)
    @Composable
    private fun ArtifactTreeView(loaded: ArtifactsContent.Loaded) {
        val tree = remember(loaded) { buildTree<ArtifactEntry> { addEntries(loaded.entries) } }
        // Shared with the scrollbar, which Jewel's tree doesn't draw itself.
        val scroll = rememberLazyListState()
        val treeState = rememberTreeState(scroll)
        LaunchedEffect(treeState, loaded) {
            treeState.openNodes = openIds
            treeState.selectedKeys = setOfNotNull(_selected.value?.id)
        }
        LaunchedEffect(treeState) {
            snapshotFlow { treeState.openNodes }.collect { openIds = it }
        }
        SpeedSearchArea(Modifier.fillMaxSize()) {
            val search = this
            VerticallyScrollableContainer(scroll, Modifier.fillMaxSize()) {
                search.SpeedSearchableTree(
                    tree = tree,
                    nodeText = { it.data.node.name },
                    modifier = Modifier.fillMaxSize().focusable(),
                    treeState = treeState,
                    onElementDoubleClick = { element ->
                        (element.data.node as? ArtifactNode.File)?.let { open(it.artifact) }
                    },
                    onSelectionChange = { elements -> _selected.value = elements.firstOrNull()?.data },
                ) { element ->
                    EntryRow(element.data, loaded.parallel)
                }
            }
        }
    }

    @Composable
    private fun EntryRow(
        entry: ArtifactEntry,
        parallel: Boolean,
    ) {
        val file = (entry.node as? ArtifactNode.File)?.artifact
        ContextMenuArea(items = { menuItems(entry, file, parallel) }) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(fileIcons.of(entry.node), null, Modifier.size(ICON.dp))
                Text(entry.node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    private fun menuItems(
        entry: ArtifactEntry,
        file: Artifact?,
        parallel: Boolean,
    ): List<ContextMenuItem> =
        buildList {
            if (file != null) add(ContextMenuItem("Open") { open(file) })
            add(ContextMenuItem("Download...") { download(entry.files, parallel) })
            if (file != null) {
                add(ContextMenuItem("Open in Browser") { BrowserUtil.browse(file.url) })
                add(ContextMenuItem("Copy URL") { copyUrl(file) })
            }
        }

    private fun copyUrl(artifact: Artifact) {
        CopyPasteManager.getInstance().setContents(StringSelection(artifact.url))
    }

    /**
     * Open an artifact in an editor tab: as text when it is text, otherwise
     * for the IDE to show as best it can (an image, say). One still open
     * from before is shown as it was read then.
     */
    private fun open(artifact: Artifact) {
        val editors = FileEditorManager.getInstance(project)
        opened[artifact.url]?.let {
            editors.openFile(it, true)
            return
        }
        scope.launch {
            val read = service.readArtifact(artifact)
            withContext(Dispatchers.Default) { read.map { virtualFile(artifact, it) } }.fold(
                onSuccess = { file ->
                    opened[artifact.url] = file
                    editors.openFile(file, true)
                },
                onFailure = { Messages.showErrorDialog(project, it.message ?: "Unknown error", "Can't Open Artifact") },
            )
        }
    }

    private fun download(
        artifacts: List<Artifact>,
        parallel: Boolean,
    ) {
        if (artifacts.isEmpty()) return
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Download Artifacts To")
        val dir = FileChooser.chooseFile(descriptor, project, null)?.toNioPath() ?: return

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
                        target?.let { runBlockingCancellable { service.downloadArtifact(artifact, it) } }
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

    private companion object {
        const val ICON = 16

        /** Open the top two levels, as the Swing tree did: executions and what's at the top of each. */
        fun initiallyOpen(entries: List<ArtifactEntry>): Set<Any> =
            entries.flatMap {
                    top ->
                listOf(top) + top.children
            }.filter { it.children.isNotEmpty() }.map { it.id }.toSet()

        /** An artifact's [bytes], read-only: as text when they are text. */
        fun virtualFile(
            artifact: Artifact,
            bytes: ByteArray,
        ): VirtualFile {
            val name = artifact.path.substringAfterLast('/')
            val text = decodeText(bytes)
            val file =
                if (text != null) {
                    LightVirtualFile(name, FileTypeManager.getInstance().getFileTypeByFileName(name), text)
                } else {
                    BinaryLightVirtualFile(name, bytes)
                }
            return file.apply { isWritable = false }
        }

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

/** What the artifacts tab has to show. */
sealed interface ArtifactsContent {
    /** Nothing yet, nothing at all, or a failure to load: why, in words. */
    data class Waiting(val text: String) : ArtifactsContent

    /** @property files Every artifact, in the tree's order */
    data class Loaded(
        val entries: List<ArtifactEntry>,
        val files: List<Artifact>,
        val parallel: Boolean,
    ) : ArtifactsContent
}

@OptIn(ExperimentalFoundationApi::class) // Jewel's tooltips are built on TooltipArea
@Composable
private fun ToolbarButton(
    key: IconKey,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconActionButton(key, text, onClick, enabled = enabled) { Text(text) }
}

private fun TreeGeneratorScope<ArtifactEntry>.addEntries(entries: List<ArtifactEntry>) {
    for (entry in entries) {
        if (entry.children.isEmpty() && entry.node is ArtifactNode.File) {
            addLeaf(entry, entry.id)
        } else {
            addNode(entry, entry.id) { addEntries(entry.children) }
        }
    }
}

/**
 * A file's icon is its file type's, as the Project view shows it. Each file
 * type's is converted once, rather than for every row drawn, and kept with
 * the job's page: an icon key holds the class of the plugin whose file type
 * it is, which kept for the session would stop that plugin unloading.
 */
private class FileIcons {
    // By file type name, read and written in composition, on the EDT.
    private val byFileType = HashMap<String, IconKey>()

    fun of(node: ArtifactNode): IconKey =
        when (node) {
            is ArtifactNode.Execution -> AllIconsKeys.Nodes.Module
            is ArtifactNode.Directory -> AllIconsKeys.Nodes.Folder
            is ArtifactNode.File -> ofFile(node.name)
        }

    private fun ofFile(name: String): IconKey {
        val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
        return byFileType.getOrPut(type.name) {
            val icon = type.icon ?: return@getOrPut AllIconsKeys.FileTypes.Any_type
            // Only icons loaded from a resource path convert; a plugin's file type may draw its own.
            runCatching { IntelliJIconKey.fromPlatformIcon(icon) }.getOrDefault(AllIconsKeys.FileTypes.Any_type)
        }
    }
}
