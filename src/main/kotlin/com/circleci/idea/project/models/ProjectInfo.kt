package com.circleci.idea.project.models

/** A CircleCI organization, as the API knows it. */
data class Organization(
    // UUID
    val id: String,
    val name: String,
)

/** A CircleCI project as the API knows it, with its organization. */
data class ProjectInfo(
    // UUID
    val id: String,
    // The slug it's known by: gh/<org>/<repo>, bb/<org>/<repo> or circleci/<org-id>/<project-id>
    val slug: String,
    val name: String,
    val org: Organization,
)
