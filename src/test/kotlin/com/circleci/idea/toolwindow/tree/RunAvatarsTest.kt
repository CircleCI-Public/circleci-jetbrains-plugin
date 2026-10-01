package com.circleci.idea.toolwindow.tree

import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunAvatarsTest {
    private fun run(
        projectSlug: String?,
        triggeredBy: String?,
    ) = Run(
        id = "id",
        number = 1,
        projectId = null,
        projectSlug = projectSlug,
        repositoryName = null,
        status = RunStatus.SUCCESS,
        createdAt = null,
        branch = null,
        tag = null,
        revision = null,
        commitSubject = null,
        commitAuthor = null,
        triggeredBy = triggeredBy,
    )

    @Test
    fun testGitHubAvatarForWhoeverTriggeredTheRun() {
        assertEquals(
            "GitHub avatar, at twice the drawn size",
            "https://github.com/pete-woods.png?size=30",
            RunAvatars.avatarUrl(run("gh/org/repo", "pete-woods")),
        )
    }

    @Test
    fun testNoAvatarWithoutAGitHubLogin() {
        assertNull("other providers", RunAvatars.avatarUrl(run("bb/org/repo", "someone")))
        assertNull("project unknown", RunAvatars.avatarUrl(run(null, "someone")))
        assertNull("no one", RunAvatars.avatarUrl(run("gh/org/repo", null)))
        assertNull("blank", RunAvatars.avatarUrl(run("gh/org/repo", " ")))
    }
}
