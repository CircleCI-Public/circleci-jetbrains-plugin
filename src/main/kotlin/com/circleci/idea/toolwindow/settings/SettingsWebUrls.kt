package com.circleci.idea.toolwindow.settings

/**
 * Links to a project's, and its organization's, settings in the CircleCI
 * web app, from the project's slug. The app's settings paths spell the VCS
 * out ("github", not "gh").
 */
object SettingsWebUrls {
    private const val APP_URL = "https://app.circleci.com/settings"

    private val VCS = mapOf("gh" to "github", "bb" to "bitbucket", "circleci" to "circleci")

    /** The project's settings, or null if [projectSlug] isn't a project slug. */
    fun project(projectSlug: String): String? {
        val (vcs, org, project) = parts(projectSlug) ?: return null
        return "$APP_URL/project/$vcs/$org/$project"
    }

    /** The settings of the project's organization, or null if [projectSlug] isn't a project slug. */
    fun organization(projectSlug: String): String? {
        val (vcs, org) = parts(projectSlug) ?: return null
        return "$APP_URL/organization/$vcs/$org"
    }

    private fun parts(projectSlug: String): List<String>? {
        val parts = projectSlug.split('/')
        if (parts.size != SLUG_PARTS || parts.any { it.isEmpty() }) return null
        val vcs = VCS[parts[0]] ?: return null
        return listOf(vcs, parts[1], parts[2])
    }

    private const val SLUG_PARTS = 3
}
