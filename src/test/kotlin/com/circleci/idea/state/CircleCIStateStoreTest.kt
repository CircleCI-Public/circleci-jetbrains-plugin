package com.circleci.idea.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class CircleCIStateStoreTest : BasePlatformTestCase() {

    private lateinit var stateStore: CircleCIStateStore

    override fun setUp() {
        super.setUp()
        stateStore = CircleCIStateStore(project)
    }

    fun testInitialState() = runBlocking {
        val authState = stateStore.auth.first()
        assertFalse(authState.isAuthenticated)
        assertNull(authState.token)
        assertEquals("https://circleci.com", authState.hostUrl)
    }

    fun testUpdateAuth() = runBlocking {
        stateStore.updateAuth { it.copy(isAuthenticated = true, token = "test-token") }

        val authState = stateStore.auth.first()
        assertTrue(authState.isAuthenticated)
        assertEquals("test-token", authState.token)
    }

    fun testUpdateFilters() = runBlocking {
        stateStore.updateFilters { it.copy(branchFilter = BranchFilter.ALL, myPipelinesOnly = true) }

        val filtersState = stateStore.filters.first()
        assertEquals(BranchFilter.ALL, filtersState.branchFilter)
        assertTrue(filtersState.myPipelinesOnly)
    }

    fun testAddPipelines() = runBlocking {
        val pipeline = Pipeline(
            id = "test-id",
            number = 1,
            projectSlug = "gh/test/repo",
            state = "success",
            createdAt = "2024-01-01T00:00:00Z",
            branch = "main",
            vcs = null,
            trigger = null
        )

        stateStore.addPipelines("gh/test/repo", listOf(pipeline))

        val projectsData = stateStore.projectsData.first()
        val projectData = projectsData.data["gh/test/repo"]
        assertNotNull(projectData)
        assertEquals(1, projectData?.pipelines?.size)
        assertEquals("test-id", projectData?.pipelines?.first()?.id)
    }

    fun testUpdateWorkflows() = runBlocking {
        val pipeline = Pipeline(
            id = "pipeline-1",
            number = 1,
            projectSlug = "gh/test/repo",
            state = "success",
            createdAt = "2024-01-01T00:00:00Z",
            branch = "main",
            vcs = null,
            trigger = null,
            workflows = emptyList()
        )

        stateStore.setPipelines("gh/test/repo", listOf(pipeline))

        val workflow = Workflow(
            id = "workflow-1",
            name = "build",
            status = "success",
            createdAt = "2024-01-01T00:00:00Z",
            stoppedAt = "2024-01-01T00:05:00Z"
        )

        stateStore.updateWorkflows("gh/test/repo", "pipeline-1", listOf(workflow))

        val projectsData = stateStore.projectsData.first()
        val projectData = projectsData.data["gh/test/repo"]
        assertNotNull(projectData)
        assertEquals(1, projectData?.pipelines?.first()?.workflows?.size)
        assertEquals("workflow-1", projectData?.pipelines?.first()?.workflows?.first()?.id)
    }

    fun testClearAllData() = runBlocking {
        val pipeline = Pipeline(
            id = "test-id",
            number = 1,
            projectSlug = "gh/test/repo",
            state = "success",
            createdAt = "2024-01-01T00:00:00Z",
            branch = "main",
            vcs = null,
            trigger = null
        )

        stateStore.addPipelines("gh/test/repo", listOf(pipeline))
        stateStore.clearAllData()

        val projectsData = stateStore.projectsData.first()
        assertTrue(projectsData.data.isEmpty())

        val projects = stateStore.projects.first()
        assertTrue(projects.selectedProjects.isEmpty())
    }
}
