package com.circleci.idea.project.models

/**
 * Represents a CircleCI project.
 */
data class CircleCIProject(
    // Format: vcs/org/repo (e.g., "gh/circleci/circleci")
    val slug: String,
    val vcsType: VcsType,
    val organization: String,
    val repository: String,
    val defaultBranch: String? = null,
    val followed: Boolean = false,
    val vcsUrl: String? = null,
    // Local workspace path if detected
    val localPath: String? = null,
) {
    companion object {
        /**
         * Create project from slug.
         * Format: vcs/org/repo (e.g., "gh/circleci/circleci")
         */
        fun fromSlug(slug: String): CircleCIProject? {
            val parts = slug.split("/")
            if (parts.size != 3) return null

            val vcsType = VcsType.fromShortCode(parts[0]) ?: return null
            val organization = parts[1]
            val repository = parts[2]

            return CircleCIProject(
                slug = slug,
                vcsType = vcsType,
                organization = organization,
                repository = repository,
            )
        }

        /**
         * Create project slug from components.
         */
        fun createSlug(
            vcsType: VcsType,
            organization: String,
            repository: String,
        ): String {
            return "${vcsType.shortCode}/$organization/$repository"
        }
    }

    /**
     * Get display name for the project.
     */
    fun getDisplayName(): String {
        return "$organization/$repository"
    }

    /**
     * Get full display name with VCS type.
     */
    fun getFullDisplayName(): String {
        return "${vcsType.displayName}: $organization/$repository"
    }
}
