package com.circleci.idea.job

import com.circleci.idea.api.CircleCIApiService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.run.RunMapper
import com.circleci.idea.state.Artifact
import com.circleci.idea.state.JobDetail
import com.circleci.idea.state.TestOutcome
import com.circleci.idea.state.TestResult
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens job pages, and fetches what they show.
 */
@Service(Service.Level.PROJECT)
class JobDetailsService(private val project: Project) {
    private val logger = CircleCILogger.getInstance()
    private val apiService = CircleCIApiService.getInstance()

    // One file per job, so opening a job that's already open selects its tab.
    private val files = ConcurrentHashMap<String, JobVirtualFile>()

    /**
     * Open a job's page in an editor tab, or select it if it's already open.
     * Must be called on the EDT.
     */
    fun openJob(ref: JobRef) {
        val file = files.computeIfAbsent(ref.jobId) { JobVirtualFile(ref) }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /**
     * Fetch a job with its steps.
     */
    suspend fun fetchJob(jobId: String): Result<JobDetail> {
        return withContext(Dispatchers.IO) {
            apiService.getJob(jobId).mapCatching { RunMapper.toJobDetail(it) ?: error("Job $jobId has no ID") }
        }
    }

    /**
     * Stream a step's output. The flow blocks on API calls, so collect it off the EDT.
     */
    fun stepOutput(
        jobId: String,
        execution: Int,
        stepNum: Int,
        isStepActive: () -> Boolean,
    ): StepOutputStream {
        return StepOutputStream(
            fetchStdout = { offset -> apiService.getStepStdout(jobId, execution, stepNum, offset) },
            fetchStderr = { apiService.getStepStderr(jobId, execution, stepNum) },
            isStepActive = isStepActive,
        )
    }

    /**
     * Fetch a job's test results.
     */
    suspend fun fetchTests(jobId: String): Result<List<TestResult>> {
        return withContext(Dispatchers.IO) {
            apiService.getJobTests(jobId).map { tests ->
                tests.map {
                    TestResult(
                        classname = it.classname.orEmpty(),
                        name = it.name.orEmpty(),
                        outcome = TestOutcome.of(it.result),
                        runTime = it.runTime,
                        message = it.message.orEmpty(),
                    )
                }
            }
        }
    }

    /**
     * Fetch artifacts for a job.
     */
    suspend fun fetchArtifacts(
        projectSlug: String,
        jobNumber: Long,
    ): Result<List<Artifact>> {
        logger.info("Fetching artifacts for job $jobNumber")

        return withContext(Dispatchers.IO) {
            apiService.getArtifacts(projectSlug, jobNumber).map { response ->
                response.items?.map { artifact ->
                    Artifact(
                        path = artifact.path,
                        nodeIndex = artifact.nodeIndex,
                        url = artifact.url,
                        prettyPath = artifact.prettyPath,
                    )
                } ?: emptyList()
            }
        }
    }

    companion object {
        fun getInstance(project: Project): JobDetailsService = project.getService(JobDetailsService::class.java)

        /** The command to SSH into one execution of a job that was rerun with SSH. */
        fun sshCommand(
            jobId: String,
            execution: Int,
        ): String = "ssh $jobId-$execution@ssh.circleci.com"
    }
}
