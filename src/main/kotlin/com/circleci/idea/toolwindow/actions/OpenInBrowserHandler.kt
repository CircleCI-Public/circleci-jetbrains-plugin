package com.circleci.idea.toolwindow.actions

import com.circleci.idea.state.JobDetailsState
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project

/**
 * Handler for opening the job in the CircleCI web interface.
 */
class OpenInBrowserHandler(project: Project) : JobActionHandler(project) {
    override fun isAvailable(state: JobDetailsState): ActionAvailability {
        val jobDetails = state.jobDetails
        val webUrl = jobDetails?.webUrl

        return if (webUrl != null) {
            ActionAvailability.Available
        } else {
            ActionAvailability.Unavailable(
                reason = "Job web URL not available",
                title = "Cannot Open in Browser",
            )
        }
    }

    override suspend fun execute(state: JobDetailsState) {
        val jobDetails = state.jobDetails ?: return
        val webUrl = jobDetails.webUrl ?: return

        BrowserUtil.browse(webUrl)
    }
}
