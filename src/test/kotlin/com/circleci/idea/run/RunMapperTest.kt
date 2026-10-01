package com.circleci.idea.run

import com.circleci.idea.api.models.JobDetailWire
import com.circleci.idea.api.models.JobWire
import com.circleci.idea.api.models.RunWire
import com.circleci.idea.api.models.V3Entity
import com.circleci.idea.api.models.V3List
import com.circleci.idea.api.models.WorkflowWire
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RunMapperTest {
    private val gson = Gson()

    // Trimmed from a real GET /api/v3/runs?filter[user_id]=me response.
    private val runsJson =
        """
        {
          "data": [{
            "id": "cc8fed36-558c-430f-bd09-4e16a68072d0",
            "attributes": {"number": 3102, "phase": "started", "created_at": "2026-10-01T14:33:44.374Z"},
            "references": {
              "event": {"attributes": {"type": "push", "vcs": {
                "provider_name": "github",
                "origin_repository_url": "https://github.com/CircleCI-Public/circleci-yaml-language-server",
                "branch": "feat/links",
                "revision": "7d3b7bcb6cc2",
                "commit": {"subject": "feat: answer go-to-definition with links", "author": {"name": "Pete", "login": "pete-woods"}}
              }}},
              "project": {"id": "75859158-08b0-4d27-bafd-398176e34f16"},
              "user": {"id": "69f6066c", "attributes": {"login": "pete-woods"}}
            }
          }, {
            "id": "4e0fc3ad-c0db-4eab-90b3-1f5d34ef77a9",
            "attributes": {"number": 3100, "phase": "ended", "current_outcome": "succeeded", "created_at": "2026-09-30T22:54:12.232Z"},
            "references": {"project": {"id": "p2"}}
          }],
          "page": {"next": "eyJpZCI6ImNj"}
        }
        """.trimIndent()

    @Test
    fun testRunFromCrossProjectListing() {
        val list: V3List<RunWire> = gson.fromJson(runsJson, object : TypeToken<V3List<RunWire>>() {}.type)
        val run = RunMapper.toRun(list.data!![0])

        assertNotNull("run should map", run)
        assertEquals("number", 3102L, run!!.number)
        assertEquals("status", RunStatus.RUNNING, run.status)
        assertEquals(
            "slug derived from the GitHub URL",
            "gh/CircleCI-Public/circleci-yaml-language-server",
            run.projectSlug,
        )
        assertEquals("repository name", "CircleCI-Public/circleci-yaml-language-server", run.repositoryName)
        assertEquals("branch", "feat/links", run.branch)
        assertEquals("subject", "feat: answer go-to-definition with links", run.commitSubject)
        assertEquals("triggered by", "pete-woods", run.triggeredBy)
        assertEquals("created at", Instant.parse("2026-10-01T14:33:44.374Z"), run.createdAt)
        assertEquals("next cursor", "eyJpZCI6ImNj", list.page?.next)
    }

    @Test
    fun testEndedRunUsesCurrentOutcomeAndListedSlug() {
        val list: V3List<RunWire> = gson.fromJson(runsJson, object : TypeToken<V3List<RunWire>>() {}.type)
        val run = RunMapper.toRun(list.data!![1], projectSlug = "gh/org/repo")!!

        assertEquals("status from current_outcome", RunStatus.SUCCESS, run.status)
        assertEquals("slug of the project it was listed for", "gh/org/repo", run.projectSlug)
        assertNull("no commit", run.commitSubject)
    }

    @Test
    fun testWorkflowAndJobs() {
        val runs: V3List<RunWire> = gson.fromJson(runsJson, object : TypeToken<V3List<RunWire>>() {}.type)
        val run = RunMapper.toRun(runs.data!![0])!!

        val workflowJson =
            """
            {"id": "67593e63", "attributes": {"name": "Build and test", "phase": "started",
              "created_at": "2026-10-01T14:33:44.596Z"}}
            """.trimIndent()
        val workflow = RunMapper.toWorkflow(gson.fromJson(workflowJson, WorkflowWire::class.java), run)!!
        assertEquals("workflow name", "Build and test", workflow.name)
        assertEquals("workflow status", RunStatus.RUNNING, workflow.status)
        assertEquals("workflow run number", 3102L, workflow.runNumber)
        assertEquals("workflow slug", run.projectSlug, workflow.projectSlug)

        val jobsJson =
            """
            {"data": [
              {"id": "j1", "attributes": {"number": 23726, "name": "test-macos", "type": "build", "phase": "ended", "outcome": "succeeded",
                "started_at": "2026-10-01T14:33:49.128Z", "ended_at": "2026-10-01T14:35:57.195Z"}},
              {"id": "j2", "attributes": {"number": 23727, "name": "test-windows", "type": "build", "phase": "started",
                "started_at": "2026-10-01T14:33:49.633Z"}},
              {"id": "j3", "attributes": {"number": 23733, "name": "smoke", "type": "build", "phase": "started"}},
              {"id": "j4", "attributes": {"number": 23740, "name": "hold", "type": "approval", "phase": "created"}}
            ]}
            """.trimIndent()
        val jobs: V3List<JobWire> = gson.fromJson(jobsJson, object : TypeToken<V3List<JobWire>>() {}.type)
        val mapped = jobs.data!!.map { RunMapper.toJob(it, workflow)!! }

        assertEquals("job number", 23726L, mapped[0].number)
        assertEquals("ended job", RunStatus.SUCCESS, mapped[0].status)
        assertEquals("started job", RunStatus.RUNNING, mapped[1].status)
        assertEquals("started job with no start time is queued", RunStatus.QUEUED, mapped[2].status)
        assertEquals("waiting approval job is on hold", RunStatus.ON_HOLD, mapped[3].status)
        assertEquals("job slug", run.projectSlug, mapped[0].projectSlug)
        assertEquals("job workflow", workflow.id, mapped[0].workflowId)
    }

    @Test
    fun testRepositoryName() {
        assertEquals("plain", "org/repo", RunMapper.repositoryName("https://github.com/org/repo"))
        assertEquals(".git suffix", "org/repo", RunMapper.repositoryName("https://bitbucket.org/org/repo.git"))
        assertEquals("trailing slash", "org/repo", RunMapper.repositoryName("https://github.com/org/repo/"))
        assertNull("no repo", RunMapper.repositoryName("https://github.com"))
        assertNull("empty", RunMapper.repositoryName(""))
    }

    @Test
    fun testDeriveProjectSlug() {
        assertEquals("github", "gh/org/repo", RunMapper.deriveProjectSlug("github", "org/repo"))
        assertEquals("bitbucket", "bb/org/repo", RunMapper.deriveProjectSlug("Bitbucket", "org/repo"))
        assertNull("other providers key slugs on IDs", RunMapper.deriveProjectSlug("gitlab", "org/repo"))
    }

    @Test
    fun testJobDetail() {
        // Trimmed from a real GET /api/v3/jobs/{id} response.
        val json =
            """
            {"data": {"id": "b4551b1f", "attributes": {"name": "test-macos", "type": "build", "phase": "ended",
              "outcome": "succeeded", "started_at": "2026-10-01T14:33:49.128Z", "ended_at": "2026-10-01T14:35:57.195Z",
              "parallel_executions": [{"steps": [
                {"name": "Spin up environment", "num": 0, "outcome": "succeeded", "phase": "ended",
                 "started_at": "2026-10-01T14:33:46.246Z", "ended_at": "2026-10-01T14:33:48.764Z",
                 "stderr_bytes": 0, "stdout_bytes": 210, "type": "spinup_environment"},
                {"name": "Run tests", "num": 101, "outcome": "failed", "phase": "ended", "exit_code": 1}
              ]}, {"steps": [{"name": "Run tests", "num": 101, "phase": "started"}]}]}}}
            """.trimIndent()
        val entity: V3Entity<JobDetailWire> = gson.fromJson(json, object : TypeToken<V3Entity<JobDetailWire>>() {}.type)
        val job = RunMapper.toJobDetail(entity.data!!)!!

        assertEquals("name", "test-macos", job.name)
        assertEquals("status", RunStatus.SUCCESS, job.status)
        assertEquals("executions", 2, job.executions.size)
        assertEquals("second execution index", 1, job.executions[1].index)

        val steps = job.executions[0].steps
        assertEquals("step nums", listOf(0, 101), steps.map { it.num })
        assertEquals("step status", RunStatus.SUCCESS, steps[0].status)
        assertEquals("stdout bytes", 210L, steps[0].stdoutBytes)
        assertEquals("failed step", RunStatus.FAILED, steps[1].status)
        assertEquals("exit code", 1, steps[1].exitCode)
        assertEquals("running step", RunStatus.RUNNING, job.executions[1].steps[0].status)
    }
}
