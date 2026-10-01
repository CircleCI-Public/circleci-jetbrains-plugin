package com.circleci.idea.state

import com.circleci.idea.run.RunScope
import com.circleci.idea.run.RunStatus
import com.circleci.idea.run.RunStatusFilter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant

class CircleCIStateStoreTest : BasePlatformTestCase() {
    private lateinit var stateStore: CircleCIStateStore

    override fun setUp() {
        super.setUp()
        stateStore = CircleCIStateStore(project)
    }

    fun testInitialState() =
        runBlocking {
            val authState = stateStore.auth.first()
            assertFalse(authState.isAuthenticated)
            assertNull(authState.token)
            assertEquals("https://circleci.com", authState.hostUrl)
        }

    fun testUpdateAuth() =
        runBlocking {
            stateStore.updateAuth { it.copy(isAuthenticated = true, token = "test-token") }

            val authState = stateStore.auth.first()
            assertTrue(authState.isAuthenticated)
            assertEquals("test-token", authState.token)
        }

    fun testUpdateFilters() =
        runBlocking {
            stateStore.updateFilters { it.copy(scope = RunScope.MY_RUNS, status = RunStatusFilter.FAILED) }

            val filtersState = stateStore.filters.first()
            assertEquals(RunScope.MY_RUNS, filtersState.scope)
            assertEquals(RunStatusFilter.FAILED, filtersState.status)
        }

    fun testSetRuns() =
        runBlocking {
            stateStore.setRuns("gh/test/repo", listOf(run("test-id")))

            val projectData = stateStore.projectsData.first().data["gh/test/repo"]
            assertNotNull(projectData)
            assertEquals(1, projectData?.runs?.size)
            assertEquals("test-id", projectData?.runs?.first()?.id)
        }

    fun testUpdateWorkflows() =
        runBlocking {
            val run = run("run-1")
            stateStore.setRuns("gh/test/repo", listOf(run))

            val workflow =
                Workflow(
                    id = "workflow-1",
                    name = "build",
                    status = RunStatus.SUCCESS,
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                    endedAt = Instant.parse("2024-01-01T00:05:00Z"),
                    runId = run.id,
                    runNumber = run.number,
                    projectSlug = run.projectSlug,
                )

            stateStore.updateWorkflows("gh/test/repo", "run-1", listOf(workflow))
            // A refresh of the run list keeps the workflows already loaded.
            stateStore.setRuns("gh/test/repo", listOf(run))

            val projectData = stateStore.projectsData.first().data["gh/test/repo"]
            assertEquals(1, projectData?.runs?.first()?.workflows?.size)
            assertEquals("workflow-1", projectData?.runs?.first()?.workflows?.first()?.id)
        }

    fun testClearAllData() =
        runBlocking {
            stateStore.setRuns("gh/test/repo", listOf(run("test-id")))
            stateStore.clearAllData()

            val projectsData = stateStore.projectsData.first()
            assertTrue(projectsData.data.isEmpty())

            val projects = stateStore.projects.first()
            assertTrue(projects.selectedProjects.isEmpty())
        }

    private fun run(id: String) =
        Run(
            id = id,
            number = 1,
            projectId = "project-id",
            projectSlug = "gh/test/repo",
            repositoryName = "test/repo",
            status = RunStatus.SUCCESS,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            branch = "main",
            tag = null,
            revision = "abc1234",
            commitSubject = "Fix the build",
            commitAuthor = "someone",
            triggeredBy = "someone",
        )
}
