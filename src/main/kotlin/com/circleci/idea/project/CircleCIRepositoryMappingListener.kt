package com.circleci.idea.project

import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.project.Project

/**
 * Re-detects CircleCI projects when the IDE's VCS repositories are mapped or
 * remapped. On startup the tool window can open before the repositories are
 * known, so the first scan finds none; this catches them once they are.
 */
class CircleCIRepositoryMappingListener(private val project: Project) : VcsRepositoryMappingListener {
    override fun mappingChanged() {
        project.getService(CircleCIProjectService::class.java).detectProjectsInBackground()
    }
}
