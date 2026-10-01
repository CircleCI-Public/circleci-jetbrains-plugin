package com.circleci.idea.api.models

import com.google.gson.annotations.SerializedName

// Wire types for the V3 runs, workflows, jobs and projects endpoints. Every
// field is nullable: Gson ignores Kotlin nullability, and the V3 API omits
// attributes that don't apply yet (an in-progress run has no outcome, a queued
// job has no started_at).

/** A single-entity V3 response: `{"data": {...}}`. */
data class V3Entity<T>(
    @SerializedName("data")
    val data: T? = null,
)

/** A V3 list response: `{"data": [...], "page": {"next": "..."}}`. */
data class V3List<T>(
    @SerializedName("data")
    val data: List<T>? = null,
    @SerializedName("page")
    val page: V3PageInfo? = null,
)

data class V3PageInfo(
    @SerializedName("next")
    val next: String? = null,
)

/** A V3 `{"id": "..."}` reference to another entity. */
data class V3Ref(
    @SerializedName("id")
    val id: String? = null,
)

data class RunWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: RunAttributesWire? = null,
    @SerializedName("references")
    val references: RunReferencesWire? = null,
)

data class RunAttributesWire(
    @SerializedName("number")
    val number: Long? = null,
    @SerializedName("phase")
    val phase: String? = null,
    @SerializedName("outcome")
    val outcome: String? = null,
    @SerializedName("current_outcome")
    val currentOutcome: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("errors")
    val errors: List<RunErrorWire>? = null,
)

data class RunErrorWire(
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("message")
    val message: String? = null,
)

data class RunReferencesWire(
    @SerializedName("event")
    val event: RunEventWire? = null,
    @SerializedName("project")
    val project: V3Ref? = null,
    @SerializedName("user")
    val user: RunUserWire? = null,
)

data class RunEventWire(
    @SerializedName("attributes")
    val attributes: RunEventAttributesWire? = null,
)

data class RunEventAttributesWire(
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("vcs")
    val vcs: RunVcsWire? = null,
)

data class RunVcsWire(
    @SerializedName("provider_name")
    val providerName: String? = null,
    @SerializedName("origin_repository_url")
    val originRepositoryUrl: String? = null,
    @SerializedName("branch")
    val branch: String? = null,
    @SerializedName("tag")
    val tag: String? = null,
    @SerializedName("revision")
    val revision: String? = null,
    @SerializedName("commit")
    val commit: RunCommitWire? = null,
)

data class RunCommitWire(
    @SerializedName("subject")
    val subject: String? = null,
    @SerializedName("url")
    val url: String? = null,
    @SerializedName("author")
    val author: RunCommitAuthorWire? = null,
)

data class RunCommitAuthorWire(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("login")
    val login: String? = null,
)

data class RunUserWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: RunUserAttributesWire? = null,
)

data class RunUserAttributesWire(
    @SerializedName("login")
    val login: String? = null,
)

data class WorkflowWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: WorkflowAttributesWire? = null,
)

data class WorkflowAttributesWire(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("phase")
    val phase: String? = null,
    @SerializedName("outcome")
    val outcome: String? = null,
    @SerializedName("current_outcome")
    val currentOutcome: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("ended_at")
    val endedAt: String? = null,
)

data class JobWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: JobAttributesWire? = null,
)

data class JobAttributesWire(
    @SerializedName("number")
    val number: Long? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("phase")
    val phase: String? = null,
    @SerializedName("outcome")
    val outcome: String? = null,
    @SerializedName("current_outcome")
    val currentOutcome: String? = null,
    @SerializedName("started_at")
    val startedAt: String? = null,
    @SerializedName("ended_at")
    val endedAt: String? = null,
)

data class ProjectWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("references")
    val references: ProjectReferencesWire? = null,
)

data class ProjectReferencesWire(
    @SerializedName("org")
    val org: V3Ref? = null,
)

/** Body for POST /api/v3/runs/search. */
data class RunSearchRequest(
    @SerializedName("scope")
    val scope: RunSearchScope,
    @SerializedName("filter")
    val filter: String,
    @SerializedName("page")
    val page: RunSearchPage,
)

data class RunSearchScope(
    @SerializedName("project_ids")
    val projectIds: List<String>,
    @SerializedName("from")
    val from: String,
    @SerializedName("to")
    val to: String,
)

data class RunSearchPage(
    @SerializedName("cursor")
    val cursor: String,
    @SerializedName("limit")
    val limit: Int,
)
