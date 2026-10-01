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
import java.nio.file.Path
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
     * Fetch a job's artifacts.
     */
    suspend fun fetchArtifacts(jobId: String): Result<List<Artifact>> {
        return withContext(Dispatchers.IO) {
            apiService.getJobArtifacts(jobId).map { artifacts ->
                artifacts.mapNotNull { wire ->
                    val attributes = wire.attributes ?: return@mapNotNull null
                    val path = attributes.path ?: return@mapNotNull null
                    val url = attributes.url ?: return@mapNotNull null
                    Artifact(path = path, url = url, execution = attributes.execution ?: 0)
                }
            }
        }
    }

    /**
     * Read an artifact to view it: its bytes, or a failure when it's over
     * [MAX_ARTIFACT_PREVIEW_BYTES], as the CLI caps previews, since artifacts
     * can be huge build outputs.
     */
    suspend fun readArtifact(artifact: Artifact): Result<ByteArray> {
        return withContext(Dispatchers.IO) {
            apiService.readArtifact(artifact.url, MAX_ARTIFACT_PREVIEW_BYTES).mapCatching {
                if (it.truncated) {
                    val limit = "${MAX_ARTIFACT_PREVIEW_BYTES shr MIB_SHIFT} MiB"
                    error("${artifact.path} is over $limit, too large to view; download it instead")
                }
                it.body
            }
        }
    }

    /**
     * Download an artifact to [target]. Blocks; call it off the EDT.
     */
    fun downloadArtifact(
        artifact: Artifact,
        target: Path,
    ): Result<Long> {
        logger.info("Downloading artifact ${artifact.path} to $target")
        return apiService.downloadArtifact(artifact.url, target)
    }

    companion object {
        /** The most of an artifact read to view it in the IDE: 8 MiB, as in the CLI. */
        const val MAX_ARTIFACT_PREVIEW_BYTES = 8L shl 20
        private const val MIB_SHIFT = 20

        fun getInstance(project: Project): JobDetailsService = project.getService(JobDetailsService::class.java)

        /** The command to SSH into one execution of a job that was rerun with SSH. */
        fun sshCommand(
            jobId: String,
            execution: Int,
        ): String = "ssh $jobId-$execution@ssh.circleci.com"
    }
}
