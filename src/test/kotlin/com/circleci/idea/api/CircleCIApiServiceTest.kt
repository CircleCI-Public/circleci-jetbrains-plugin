package com.circleci.idea.api

import com.circleci.idea.api.models.ConfigValidationResponse
import com.circleci.idea.api.models.JobDetailsInfo
import com.circleci.idea.api.models.JobStepInfo
import com.circleci.idea.api.models.ProjectInfo
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleCIApiServiceTest {
    private val gson = Gson()

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
