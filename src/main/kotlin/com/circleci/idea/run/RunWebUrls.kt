package com.circleci.idea.run

import com.circleci.idea.state.Job
import com.circleci.idea.state.Run
import com.circleci.idea.state.Workflow

/**
 * Links to runs, workflows and jobs in the CircleCI web app. The app's paths
 * still say "pipelines"; that's the app's routing, not our terminology.
 */
object RunWebUrls {
    private const val APP_URL = "https://app.circleci.com/pipelines"

    fun run(run: Run): String? {
        val slug = run.projectSlug ?: return null
        val number = run.number ?: return null
        return "$APP_URL/$slug/$number"
    }

    fun workflow(workflow: Workflow): String? {
        val slug = workflow.projectSlug ?: return null
        val number = workflow.runNumber ?: return null
        return "$APP_URL/$slug/$number/workflows/${workflow.id}"
    }

    fun job(
        job: Job,
        workflow: Workflow?,
    ): String? {
        val slug = job.projectSlug ?: return null
        val number = job.number ?: return null
        val runNumber = workflow?.runNumber
        return if (workflow != null && runNumber != null) {
            "$APP_URL/$slug/$runNumber/workflows/${workflow.id}/jobs/$number"
        } else {
            "$APP_URL/$slug/jobs/$number"
        }
    }
}
