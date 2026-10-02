package com.circleci.idea.api.models

import com.google.gson.annotations.SerializedName

// Wire types for environment variables and contexts. Response fields are
// nullable, as Gson ignores Kotlin nullability.

/** A page of a project's environment variables. */
data class ProjectEnvVarPageWire(
    @SerializedName("items")
    val items: List<ProjectEnvVarWire>? = null,
    @SerializedName("next_page_token")
    val nextPageToken: String? = null,
)

/** A project environment variable; the API masks its value, e.g. "xxxx1234". */
data class ProjectEnvVarWire(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("value")
    val value: String? = null,
)

/** Body for setting an environment variable. */
data class EnvVarRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("value")
    val value: String,
)

/** A context, as listed. */
data class ContextWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: NamedAttributesWire? = null,
)

/** A context's environment variable. Keyed by name: it has no ID. */
data class ContextEnvVarWire(
    @SerializedName("attributes")
    val attributes: ContextEnvVarAttributesWire? = null,
)

data class ContextEnvVarAttributesWire(
    @SerializedName("name")
    val name: String? = null,
    // The value's last few characters.
    @SerializedName("truncated_value")
    val truncatedValue: String? = null,
)

/** An environment variable, with as much of its value as the API shows. */
data class EnvVar(
    val name: String,
    val maskedValue: String,
)

data class Context(
    val id: String,
    val name: String,
)
