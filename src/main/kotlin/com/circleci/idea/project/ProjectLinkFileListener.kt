package com.circleci.idea.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Re-detects CircleCI projects when a `.circleci/info.yml` changes, e.g. when
 * `circleci project link` is run in the IDE's terminal, so the link is followed.
 */
class ProjectLinkFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.any { isLinkFile(it.path) }) {
            project.getService(CircleCIProjectService::class.java).detectProjectsInBackground()
        }
    }
}

internal fun isLinkFile(path: String): Boolean = path.replace('\\', '/').endsWith("/${ProjectLinkFile.PATH}")
