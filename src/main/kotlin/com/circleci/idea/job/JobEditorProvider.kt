package com.circleci.idea.job

import com.circleci.idea.icons.statusIcon
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Opens a [JobVirtualFile] as a job page.
 */
class JobEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = file is JobVirtualFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor = JobEditor(project, file as JobVirtualFile)

    override fun getEditorTypeId(): String = "circleci-job"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * A job page in an editor tab.
 */
class JobEditor(
    project: Project,
    private val file: JobVirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val panel = JobPanel(project, file)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusedComponent

    override fun getName(): String = "Job"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {
        // A job page has no state to restore.
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

/**
 * Shows a job's status as its tab icon.
 */
class JobFileIconProvider : FileIconProvider {
    override fun getIcon(
        file: VirtualFile,
        @Iconable.IconFlags flags: Int,
        project: Project?,
    ): Icon? = (file as? JobVirtualFile)?.let { statusIcon(it.status) }
}
