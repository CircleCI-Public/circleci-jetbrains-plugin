package com.circleci.idea.run

import com.circleci.idea.api.models.JobWire
import com.circleci.idea.api.models.RunWire
import com.circleci.idea.api.models.WorkflowWire
import com.circleci.idea.state.Job
import com.circleci.idea.state.Run
import com.circleci.idea.state.RunError
import com.circleci.idea.state.Workflow
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Converts V3 wire types into the plugin's run/workflow/job models.
 */
object RunMapper {
    private val VCS_SHORT_CODES = mapOf("github" to "gh", "bitbucket" to "bb")

    /**
     * Convert a run. [projectSlug] is the project it was listed for; when null
     * (a cross-project listing) it is derived from the run's repository URL.
     */
    fun toRun(
        wire: RunWire,
        projectSlug: String? = null,
    ): Run? {
        val id = wire.id ?: return null
        val attributes = wire.attributes
        val vcs = wire.references?.event?.attributes?.vcs
        val repositoryName = repositoryName(vcs?.originRepositoryUrl)
        return Run(
            id = id,
            number = attributes?.number,
            projectId = wire.references?.project?.id,
            projectSlug = projectSlug ?: deriveProjectSlug(vcs?.providerName, repositoryName),
            repositoryName = repositoryName,
            status = RunStatus.fromV3(attributes?.phase, attributes?.outcome, attributes?.currentOutcome),
            createdAt = parseInstant(attributes?.createdAt),
            branch = vcs?.branch?.takeIf { it.isNotEmpty() },
            tag = vcs?.tag?.takeIf { it.isNotEmpty() },
            revision = vcs?.revision?.takeIf { it.isNotEmpty() },
            commitSubject = vcs?.commit?.subject?.takeIf { it.isNotBlank() },
            commitAuthor = vcs?.commit?.author?.let { it.login ?: it.name },
            triggeredBy = wire.references?.user?.attributes?.login,
            errors = attributes?.errors.orEmpty().map { RunError(it.type, it.message) },
        )
    }

    fun toWorkflow(
        wire: WorkflowWire,
        run: Run,
    ): Workflow? {
        val id = wire.id ?: return null
        val attributes = wire.attributes
        return Workflow(
            id = id,
            name = attributes?.name ?: id,
            status = RunStatus.fromV3(attributes?.phase, attributes?.outcome, attributes?.currentOutcome),
            createdAt = parseInstant(attributes?.createdAt),
            endedAt = parseInstant(attributes?.endedAt),
            runId = run.id,
            runNumber = run.number,
            projectSlug = run.projectSlug,
        )
    }

    fun toJob(
        wire: JobWire,
        workflow: Workflow,
    ): Job? {
        val id = wire.id ?: return null
        val attributes = wire.attributes
        val startedAt = parseInstant(attributes?.startedAt)
        val status =
            jobStatus(attributes?.type, attributes?.phase, attributes?.outcome, attributes?.currentOutcome, startedAt)
        return Job(
            id = id,
            number = attributes?.number,
            name = attributes?.name ?: id,
            type = attributes?.type,
            status = status,
            startedAt = startedAt,
            endedAt = parseInstant(attributes?.endedAt),
            workflowId = workflow.id,
            projectSlug = workflow.projectSlug,
        )
    }

    /**
     * A job's status, corrected for the jobs list endpoint's optimistic phase:
     * it reports "started" as soon as the workflow dispatches a job, while the
     * job is still waiting for an executor. Only started_at says it really
     * began, so a started job without one is shown as queued. An approval job
     * that hasn't ended is waiting on someone to approve it.
     */
    internal fun jobStatus(
        type: String?,
        phase: String?,
        outcome: String?,
        currentOutcome: String?,
        startedAt: Instant?,
    ): RunStatus {
        if (type == "approval" && phase != "ended") {
            return RunStatus.ON_HOLD
        }
        if (phase == "started" && startedAt == null) {
            return RunStatus.QUEUED
        }
        return RunStatus.fromV3(phase, outcome, currentOutcome)
    }

    /** "org/repo" from a repository URL such as https://github.com/org/repo(.git). */
    internal fun repositoryName(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = url.trimEnd('/').removeSuffix(".git").substringAfter("://").substringAfter('/', "")
        val parts = path.split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 2) "${parts[parts.size - 2]}/${parts.last()}" else null
    }

    /**
     * A project slug for providers whose slug is spelled from the repository
     * ("gh/org/repo", "bb/org/repo"). Other providers' slugs are keyed on
     * org and project IDs, which the run doesn't carry; those return null.
     */
    internal fun deriveProjectSlug(
        providerName: String?,
        repositoryName: String?,
    ): String? {
        val shortCode = VCS_SHORT_CODES[providerName?.lowercase()] ?: return null
        return repositoryName?.let { "$shortCode/$it" }
    }

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrEmpty()) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
