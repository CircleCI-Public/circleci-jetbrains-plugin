package com.circleci.idea.project

import com.circleci.idea.api.models.ProjectInfo
import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.project.models.VcsType

/**
 * Converts between API ProjectInfo and domain CircleCIProject models.
 */
object ProjectConverter {
    /**
     * Convert API ProjectInfo to CircleCIProject.
     */
    fun fromApiModel(projectInfo: ProjectInfo): CircleCIProject? {
        // Try to extract from slug first
        projectInfo.slug?.let { slug ->
            CircleCIProject.fromSlug(slug)?.let { project ->
                return project.copy(
                    defaultBranch = projectInfo.defaultBranch,
                    followed = projectInfo.followed,
                    vcsUrl = projectInfo.vcsUrl,
                )
            }
        }

        // Fallback to extracting from reponame/username
        return buildFromRepoInfo(projectInfo)
    }

    private fun buildFromRepoInfo(projectInfo: ProjectInfo): CircleCIProject? {
        val reponame = projectInfo.reponame
        val username = projectInfo.username
        if (reponame == null || username == null) {
            return null
        }

        val vcsType =
            when (projectInfo.vcsType) {
                "github" -> VcsType.GITHUB
                "bitbucket" -> VcsType.BITBUCKET
                "gitlab" -> VcsType.GITLAB
                "circleci" -> VcsType.CIRCLECI
                else -> VcsType.fromUrl(projectInfo.vcsUrl ?: "") ?: return null
            }

        val slug = CircleCIProject.createSlug(vcsType, username, reponame)

        return CircleCIProject(
            slug = slug,
            vcsType = vcsType,
            organization = username,
            repository = reponame,
            defaultBranch = projectInfo.defaultBranch,
            followed = projectInfo.followed,
            vcsUrl = projectInfo.vcsUrl,
        )
    }

    /**
     * Convert multiple API ProjectInfo to CircleCIProject.
     */
    fun fromApiModels(projectInfos: List<ProjectInfo>): List<CircleCIProject> {
        return projectInfos.mapNotNull { fromApiModel(it) }
    }
}
