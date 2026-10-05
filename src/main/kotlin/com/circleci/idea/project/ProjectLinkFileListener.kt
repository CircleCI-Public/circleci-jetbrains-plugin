package com.circleci.idea.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Re-detects CircleCI projects when a `.circleci/info.yml` changes, e.g. when
 * `circleci project link` is run in the IDE's terminal, so the link is followed.
 */
class ProjectLinkFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.any(::isLinkFileEvent)) {
            project.getService(CircleCIProjectService::class.java).detectProjectsInBackground()
        }
    }
}

private val LINK_FILE_NAME = ProjectLinkFile.PATH.substringAfterLast('/')

/**
 * Whether [event] is for a `.circleci/info.yml`: by name first, without
 * building a path for each of the thousands of events a refresh can bring.
 */
internal fun isLinkFileEvent(event: VFileEvent): Boolean = nameOf(event) == LINK_FILE_NAME && isLinkFile(event.path)

// The name of the file the event leaves.
private fun nameOf(event: VFileEvent): String? =
    when (event) {
        is VFileCreateEvent -> event.childName
        is VFileCopyEvent -> event.newChildName
        else -> event.file?.name
    }

internal fun isLinkFile(path: String): Boolean = path.replace('\\', '/').endsWith("/${ProjectLinkFile.PATH}")
