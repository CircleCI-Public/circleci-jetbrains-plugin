package com.circleci.idea.api.models

import com.google.gson.annotations.SerializedName
import java.time.Instant

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

/** A context, as listed, or as read on its own, when it names its organization. */
data class ContextWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: ContextAttributesWire? = null,
    @SerializedName("references")
    val references: ContextReferencesWire? = null,
)

data class ContextAttributesWire(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
)

data class ContextReferencesWire(
    @SerializedName("org")
    val org: V3Ref? = null,
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
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
)

/**
 * A context restriction. Its [ContextRestrictionAttributesWire.matchPattern]
 * is a project's ID, a group's ID (the organization's for all its members),
 * or an expression.
 */
data class ContextRestrictionWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: ContextRestrictionAttributesWire? = null,
)

data class ContextRestrictionAttributesWire(
    @SerializedName("restriction_type")
    val restrictionType: String? = null,
    @SerializedName("match_pattern")
    val matchPattern: String? = null,
    // The project's or group's name, as listed; never an expression's.
    @SerializedName("name")
    val name: String? = null,
)

/** A group, or a project, as listed. */
data class NamedEntityWire(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("attributes")
    val attributes: NamedAttributesWire? = null,
)

/**
 * An environment variable, with as much of its value as the API shows, and
 * for a context's, when it was added and last set.
 */
data class EnvVar(
    val name: String,
    val maskedValue: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class Context(
    val id: String,
    val name: String,
)

/** A context read on its own, with the organization it's in. */
data class ContextDetail(
    val id: String,
    val name: String,
    val orgId: String?,
    val createdAt: Instant?,
)

/** What a context restriction limits the context to, as the API names it. */
enum class RestrictionType(val wireName: String) {
    /** Pipelines run by members of a group: the restriction's value is the group's ID. */
    GROUP("group"),

    /** Pipelines of a project: the value is the project's ID. */
    PROJECT("project"),

    /** Pipelines whose values match an expression: the value is the expression. */
    EXPRESSION("expression"),
    ;

    companion object {
        fun of(wireName: String?): RestrictionType? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * One of a context's restrictions.
 *
 * @property value A project's or group's ID, or an expression
 * @property name The project's or group's name, if the API could find it
 */
data class ContextRestriction(
    val id: String,
    val type: RestrictionType,
    val value: String,
    val name: String?,
)

/** A group in an organization, or one of its projects, to restrict a context to. */
data class NamedEntity(
    val id: String,
    val name: String,
)
