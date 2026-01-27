package com.circleci.idea.project

import com.circleci.idea.project.models.CircleCIProject
import com.circleci.idea.project.models.VcsType

/**
 * Parser for git remote URLs to extract VCS information.
 * Supports various URL formats:
 * - HTTPS: https://github.com/org/repo.git
 * - SSH: git@github.com:org/repo.git
 * - SSH with protocol: ssh://git@github.com/org/repo.git
 */
object GitRemoteParser {
    private val HTTPS_PATTERN = Regex("""https?://([^/]+)/([^/]+)/([^/\s]+?)(?:\.git)?$""")
    private val SSH_PATTERN = Regex("""git@([^:]+):([^/]+)/([^/\s]+?)(?:\.git)?$""")
    private val SSH_PROTOCOL_PATTERN = Regex("""ssh://git@([^/]+)/([^/]+)/([^/\s]+?)(?:\.git)?$""")

    /**
     * Parse a git remote URL into a CircleCI project.
     */
    fun parseRemoteUrl(url: String): CircleCIProject? {
        val cleanUrl = url.trim()

        // Try HTTPS pattern
        HTTPS_PATTERN.find(cleanUrl)?.let { match ->
            return createProject(match.groupValues[1], match.groupValues[2], match.groupValues[3], cleanUrl)
        }

        // Try SSH pattern
        SSH_PATTERN.find(cleanUrl)?.let { match ->
            return createProject(match.groupValues[1], match.groupValues[2], match.groupValues[3], cleanUrl)
        }

        // Try SSH with protocol pattern
        SSH_PROTOCOL_PATTERN.find(cleanUrl)?.let { match ->
            return createProject(match.groupValues[1], match.groupValues[2], match.groupValues[3], cleanUrl)
        }

        return null
    }

    /**
     * Create a CircleCI project from parsed components.
     */
    private fun createProject(
        host: String,
        org: String,
        repo: String,
        vcsUrl: String,
    ): CircleCIProject? {
        val vcsType = VcsType.fromUrl(host) ?: return null

        val slug = CircleCIProject.createSlug(vcsType, org, repo)

        return CircleCIProject(
            slug = slug,
            vcsType = vcsType,
            organization = org,
            repository = repo,
            vcsUrl = vcsUrl,
        )
    }

    /**
     * Extract organization and repository from various URL formats.
     */
    fun extractOrgAndRepo(url: String): Pair<String, String>? {
        val project = parseRemoteUrl(url) ?: return null
        return Pair(project.organization, project.repository)
    }

    /**
     * Check if a URL is a valid git remote URL.
     */
    fun isValidGitUrl(url: String): Boolean {
        return parseRemoteUrl(url) != null
    }

    /**
     * Get VCS type from URL.
     */
    fun getVcsType(url: String): VcsType? {
        return parseRemoteUrl(url)?.vcsType
    }
}
