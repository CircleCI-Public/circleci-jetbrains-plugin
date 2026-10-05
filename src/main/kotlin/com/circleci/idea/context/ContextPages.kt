package com.circleci.idea.context

import com.circleci.idea.icons.CircleCIIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent

/**
 * The context a page is for, as the tool window listed it.
 *
 * @property projectSlug The project it was listed for, whose organization it's in
 */
data class ContextRef(
    val id: String,
    val name: String,
    val projectSlug: String,
) {
    /** Whether it's a standalone organization's, which can't restrict contexts to groups. */
    val isStandaloneOrg: Boolean get() = projectSlug.startsWith("circleci/")
}

/** A change to a context's environment variables, which [source] made and needn't hear about. */
data class EnvVarsChanged(val contextId: String, val source: Any)

/** A context deleted, which [source] did and needn't hear about. */
data class ContextDeleted(val contextId: String, val source: Any)

/**
 * Opens context pages, one editor tab per context, and tells those showing
 * a context's environment variables when another changes them. A deleted
 * context's page closes.
 */
@Service(Service.Level.PROJECT)
class ContextPages(private val project: Project) {
    // One file per context, so opening a context that's already open selects its tab.
    private val files = ConcurrentHashMap<String, ContextVirtualFile>()

    private val _envVarChanges = MutableSharedFlow<EnvVarsChanged>(extraBufferCapacity = CHANGES_BUFFER)
    val envVarChanges: SharedFlow<EnvVarsChanged> = _envVarChanges.asSharedFlow()

    private val _deletions = MutableSharedFlow<ContextDeleted>(extraBufferCapacity = CHANGES_BUFFER)
    val deletions: SharedFlow<ContextDeleted> = _deletions.asSharedFlow()

    /** Open a context's page, or select it if it's open. Must be called on the EDT. */
    fun open(ref: ContextRef) {
        val file = files.computeIfAbsent(ref.id) { ContextVirtualFile(ref) }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun envVarsChanged(change: EnvVarsChanged) {
        _envVarChanges.tryEmit(change)
    }

    /** Close a deleted context's page, if it's open, and say it's gone. Must be called on the EDT. */
    fun contextDeleted(deletion: ContextDeleted) {
        files.remove(deletion.contextId)?.let { FileEditorManager.getInstance(project).closeFile(it) }
        _deletions.tryEmit(deletion)
    }

    companion object {
        private const val CHANGES_BUFFER = 16

        fun getInstance(project: Project): ContextPages = project.service()
    }
}

/** The in-memory file a context page is opened on, named for the context. */
class ContextVirtualFile(val ref: ContextRef) : LightVirtualFile(ref.name) {
    init {
        isWritable = false
    }

    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    override fun getPath(): String = "circleci-context/${ref.id}"
}

/** Opens a [ContextVirtualFile] as a context page. */
class ContextEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = file is ContextVirtualFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor = ContextEditor(project, file as ContextVirtualFile)

    override fun getEditorTypeId(): String = "circleci-context"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** A context page in an editor tab. */
class ContextEditor(
    project: Project,
    private val file: ContextVirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val panel = ContextPanel(project, file.ref)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusedComponent

    override fun getName(): String = "Context"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {
        // A context page has no state to restore.
    }

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        // Its properties never change.
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        // Nothing was added.
    }

    override fun dispose() {
        panel.dispose()
    }
}

/** Shows a context's tab with the CircleCI icon. */
class ContextFileIconProvider : FileIconProvider {
    override fun getIcon(
        file: VirtualFile,
        @Iconable.IconFlags flags: Int,
        project: Project?,
    ): Icon? = if (file is ContextVirtualFile) CircleCIIcons.PLUGIN_ICON else null
}
