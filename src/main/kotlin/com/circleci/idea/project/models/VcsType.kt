package com.circleci.idea.project.models

/**
 * Version Control System types supported by CircleCI.
 */
enum class VcsType(val shortCode: String, val displayName: String) {
    GITHUB("gh", "GitHub"),
    BITBUCKET("bb", "Bitbucket"),
    GITLAB("gl", "GitLab"),
    CIRCLECI("circleci", "CircleCI");

    companion object {
        fun fromShortCode(code: String): VcsType? {
            return values().firstOrNull { it.shortCode.equals(code, ignoreCase = true) }
        }

        fun fromUrl(url: String): VcsType? {
            return when {
                url.contains("github.com", ignoreCase = true) -> GITHUB
                url.contains("bitbucket.org", ignoreCase = true) -> BITBUCKET
                url.contains("gitlab.com", ignoreCase = true) -> GITLAB
                url.contains("circleci.com", ignoreCase = true) -> CIRCLECI
                else -> null
            }
        }
    }
}
