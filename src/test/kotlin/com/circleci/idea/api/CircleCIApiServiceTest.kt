package com.circleci.idea.api

import com.circleci.idea.api.models.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class CircleCIApiServiceTest {
    private val gson = Gson()

    @Test
    fun testJobInfoDeserialization() {
        val json =
            """
            {
                "id": "job-123",
                "job_number": 456,
                "name": "build",
                "project_slug": "gh/test/repo",
                "status": "success",
                "type": "build",
                "started_at": "2024-01-01T00:00:00Z",
                "stopped_at": "2024-01-01T00:05:00Z",
                "dependencies": ["job-1", "job-2"],
                "approved_by": null
            }
            """.trimIndent()

        val jobInfo = gson.fromJson(json, JobInfo::class.java)

        assertEquals("job-123", jobInfo.id)
        assertEquals(456L, jobInfo.jobNumber)
        assertEquals("build", jobInfo.name)
        assertEquals("gh/test/repo", jobInfo.projectSlug)
        assertEquals("success", jobInfo.status)
        assertEquals("build", jobInfo.type)
        assertEquals("2024-01-01T00:00:00Z", jobInfo.startedAt)
        assertEquals("2024-01-01T00:05:00Z", jobInfo.stoppedAt)
        assertEquals(listOf("job-1", "job-2"), jobInfo.dependencies)
        assertNull(jobInfo.approvedBy)
    }

    @Test
    fun testJobInfoDeserializationWithNullJobNumber() {
        // Test that jobNumber can be null (important for our bug fix)
        val json =
            """
            {
                "id": "job-123",
                "name": "build",
                "project_slug": "gh/test/repo",
                "status": "success",
                "type": "build",
                "started_at": "2024-01-01T00:00:00Z",
                "stopped_at": null,
                "dependencies": [],
                "approved_by": null
            }
            """.trimIndent()

        val jobInfo = gson.fromJson(json, JobInfo::class.java)

        assertEquals("job-123", jobInfo.id)
        assertNull(jobInfo.jobNumber)
        assertEquals("build", jobInfo.name)
    }

    @Test
    fun testJobDetailsInfoDeserialization() {
        val json =
            """
            {
                "id": "job-123",
                "job_number": 456,
                "name": "build",
                "project_slug": "gh/test/repo",
                "status": "success",
                "type": "build",
                "started_at": "2024-01-01T00:00:00Z",
                "stopped_at": "2024-01-01T00:05:00Z",
                "duration": 300000,
                "executor": {
                    "resource_class": "medium"
                },
                "parallelism": 1,
                "steps": [
                    {
                        "name": "Checkout code",
                        "actions": [
                            {
                                "name": "Checkout",
                                "status": "success",
                                "runtime_millis": 1000,
                                "start_time": "2024-01-01T00:00:00Z",
                                "end_time": "2024-01-01T00:00:01Z"
                            }
                        ]
                    }
                ],
                "ssh": {
                    "enabled": true,
                    "host": "ssh.example.com",
                    "port": 22,
                    "user": "circleci"
                },
                "web_url": "https://app.circleci.com/pipelines/gh/test/repo/jobs/456"
            }
            """.trimIndent()

        val jobDetails = gson.fromJson(json, JobDetailsInfo::class.java)

        assertEquals("job-123", jobDetails.id)
        assertEquals(456L, jobDetails.jobNumber)
        assertEquals("build", jobDetails.name)
        assertEquals("gh/test/repo", jobDetails.projectSlug)
        assertEquals("success", jobDetails.status)
        assertEquals(300000L, jobDetails.duration)
        assertEquals("medium", jobDetails.executor?.resourceClass)
        assertEquals(1, jobDetails.parallelism)
        assertNotNull(jobDetails.steps)
        assertEquals(1, jobDetails.steps?.size)
        assertEquals("Checkout code", jobDetails.steps?.first()?.name)
        assertEquals(true, jobDetails.ssh?.enabled)
        assertEquals("ssh.example.com", jobDetails.ssh?.host)
        assertEquals(22, jobDetails.ssh?.port)
        assertEquals("circleci", jobDetails.ssh?.user)
    }

    @Test
    fun testPipelineInfoDeserialization() {
        val json =
            """
            {
                "id": "pipeline-123",
                "number": 1,
                "project_slug": "gh/test/repo",
                "state": "success",
                "created_at": "2024-01-01T00:00:00Z",
                "vcs": {
                    "origin_repository_url": "https://github.com/test/repo",
                    "target_repository_url": "https://github.com/test/repo",
                    "revision": "abc123",
                    "branch": "main",
                    "tag": null
                },
                "trigger": {
                    "type": "webhook",
                    "actor": {
                        "login": "testuser",
                        "avatar_url": "https://example.com/avatar.png"
                    }
                }
            }
            """.trimIndent()

        val pipeline = gson.fromJson(json, PipelineInfo::class.java)

        assertEquals("pipeline-123", pipeline.id)
        assertEquals(1, pipeline.number)
        assertEquals("gh/test/repo", pipeline.projectSlug)
        assertEquals("success", pipeline.state)
        assertEquals("2024-01-01T00:00:00Z", pipeline.createdAt)
        assertEquals("https://github.com/test/repo", pipeline.vcs?.originRepositoryUrl)
        assertEquals("main", pipeline.vcs?.branch)
        assertEquals("testuser", pipeline.trigger?.actor?.login)
    }

    @Test
    fun testWorkflowInfoDeserialization() {
        val json =
            """
            {
                "id": "workflow-123",
                "name": "build-and-test",
                "status": "success",
                "created_at": "2024-01-01T00:00:00Z",
                "stopped_at": "2024-01-01T00:10:00Z"
            }
            """.trimIndent()

        val workflow = gson.fromJson(json, WorkflowInfo::class.java)

        assertEquals("workflow-123", workflow.id)
        assertEquals("build-and-test", workflow.name)
        assertEquals("success", workflow.status)
        assertEquals("2024-01-01T00:00:00Z", workflow.createdAt)
        assertEquals("2024-01-01T00:10:00Z", workflow.stoppedAt)
    }

    @Test
    fun testPaginatedResponseDeserialization() {
        val json =
            """
            {
                "items": [
                    {
                        "id": "job-1",
                        "name": "build",
                        "project_slug": "gh/test/repo",
                        "status": "success",
                        "type": "build",
                        "started_at": "2024-01-01T00:00:00Z",
                        "stopped_at": "2024-01-01T00:05:00Z",
                        "dependencies": [],
                        "approved_by": null
                    }
                ],
                "next_page_token": "next-token-123"
            }
            """.trimIndent()

        val response =
            gson.fromJson(
                json,
                object : com.google.gson.reflect.TypeToken<PaginatedResponse<JobInfo>>() {}.type,
            ) as PaginatedResponse<JobInfo>

        assertEquals(1, response.items.size)
        assertEquals("job-1", response.items.first().id)
        assertEquals("next-token-123", response.nextPageToken)
    }

    @Test
    fun testProjectInfoDeserialization() {
        val json =
            """
            {
                "slug": "gh/test/repo",
                "name": "repo",
                "organization_name": "test",
                "vcs_info": {
                    "provider": "GitHub",
                    "default_branch": "main"
                }
            }
            """.trimIndent()

        val project = gson.fromJson(json, ProjectInfo::class.java)

        assertEquals("gh/test/repo", project.slug)
        assertEquals("repo", project.name)
        assertEquals("test", project.organizationName)
        assertEquals("GitHub", project.vcsInfo?.provider)
        assertEquals("main", project.vcsInfo?.defaultBranch)
    }

    @Test
    fun testJobStepInfoWithMultipleActions() {
        val json =
            """
            {
                "name": "Build",
                "actions": [
                    {
                        "name": "Install dependencies",
                        "status": "success",
                        "run_time_millis": 5000,
                        "start_time": "2024-01-01T00:00:00Z",
                        "end_time": "2024-01-01T00:00:05Z"
                    },
                    {
                        "name": "Run build",
                        "status": "success",
                        "run_time_millis": 10000,
                        "start_time": "2024-01-01T00:00:05Z",
                        "end_time": "2024-01-01T00:00:15Z"
                    }
                ]
            }
            """.trimIndent()

        val step = gson.fromJson(json, JobStepInfo::class.java)

        assertEquals("Build", step.name)
        assertNotNull(step.actions)
        assertEquals(2, step.actions?.size)
        assertEquals("Install dependencies", step.actions?.get(0)?.name)
        assertEquals("Run build", step.actions?.get(1)?.name)
        assertEquals(5000L, step.actions?.get(0)?.runTimeMillis)
        assertEquals(10000L, step.actions?.get(1)?.runTimeMillis)
    }

    @Test
    fun testApprovalJobType() {
        val json =
            """
            {
                "id": "job-123",
                "job_number": 456,
                "name": "approve-deploy",
                "project_slug": "gh/test/repo",
                "status": "on_hold",
                "type": "approval",
                "started_at": null,
                "stopped_at": null,
                "dependencies": [],
                "approved_by": null
            }
            """.trimIndent()

        val jobInfo = gson.fromJson(json, JobInfo::class.java)

        assertEquals("approval", jobInfo.type)
        assertEquals("on_hold", jobInfo.status)
        assertNull(jobInfo.startedAt)
        assertNull(jobInfo.stoppedAt)
    }

    @Test
    fun testConfigValidationResponse() {
        val json =
            """
            {
                "valid": true,
                "errors": []
            }
            """.trimIndent()

        val response = gson.fromJson(json, ConfigValidationResponse::class.java)

        assertTrue(response.valid)
        assertTrue(response.errors.isEmpty())
    }

    @Test
    fun testConfigValidationResponseWithErrors() {
        val json =
            """
            {
                "valid": false,
                "errors": [
                    {"message": "Invalid workflow name"},
                    {"message": "Unknown job reference"}
                ]
            }
            """.trimIndent()

        val response = gson.fromJson(json, ConfigValidationResponse::class.java)

        assertFalse(response.valid)
        assertEquals(2, response.errors.size)
        assertEquals("Invalid workflow name", response.errors[0].message)
        assertEquals("Unknown job reference", response.errors[1].message)
    }
}
