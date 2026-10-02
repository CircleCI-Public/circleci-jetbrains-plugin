package com.circleci.idea.project

/**
 * The two shapes of project slug CircleCI takes:
 *
 * - standalone projects, `circleci/<org-id>/<project-id>`, both IDs UUIDs
 *   (shown in the project's settings);
 * - classic projects, `gh/<org>/<repo>` or `bb/<org>/<repo>`, named after the
 *   GitHub or Bitbucket repository they build.
 */
object ProjectSlugs {
    private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val STANDALONE = Regex("circleci/$UUID/$UUID")
    private val CLASSIC = Regex("(gh|bb)/[^/\\s]+/[^/\\s]+")

    /** What's wrong with [slug] as a project slug, or null if it's one. */
    fun problem(slug: String): String? =
        when {
            slug.isBlank() -> "Enter a project slug"
            STANDALONE.matches(slug) || CLASSIC.matches(slug) -> null
            slug.startsWith("circleci/") -> "A standalone project's slug is circleci/<org-id>/<project-id>, both UUIDs"
            slug.startsWith(
                "gh/",
            ) || slug.startsWith("bb/") -> "A classic project's slug is gh/<org>/<repo> or bb/<org>/<repo>"
            else -> "Start with circleci/ for a standalone project, or gh/ or bb/ for a classic one"
        }
}
