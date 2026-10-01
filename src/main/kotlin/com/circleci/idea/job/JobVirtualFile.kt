package com.circleci.idea.job

import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.Job
import com.circleci.idea.state.Workflow
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile

/**
 * What a job page needs to know about a job beyond its V3 detail, taken from
 * the run tree it was opened from: the V3 job detail has no number, project or run.
 */
data class JobRef(
    val jobId: String,
    val number: Long?,
    val name: String,
    val projectSlug: String?,
    val workflowId: String,
    val workflowName: String?,
    val runNumber: Long?,
) {
    companion object {
        fun of(
            job: Job,
            workflow: Workflow?,
        ): JobRef =
            JobRef(
                jobId = job.id,
                number = job.number,
                name = job.name,
                projectSlug = job.projectSlug,
                workflowId = job.workflowId,
                workflowName = workflow?.name,
                runNumber = workflow?.runNumber,
            )
    }
}

/**
 * The in-memory file a job page is opened on, one per job, so opening a job
 * again goes to its existing tab.
 */
class JobVirtualFile(val ref: JobRef) : LightVirtualFile(title(ref)) {
    /** The job's last known status, for the tab icon. */
    @Volatile
    var status: RunStatus = RunStatus.UNKNOWN

    init {
        isWritable = false
    }

    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    override fun getPath(): String = "circleci-job/${ref.jobId}"

    private companion object {
        fun title(ref: JobRef): String = ref.number?.let { "${ref.name} #$it" } ?: ref.name
    }
}
