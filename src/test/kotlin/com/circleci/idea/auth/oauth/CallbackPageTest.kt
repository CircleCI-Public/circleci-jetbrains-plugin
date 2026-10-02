package com.circleci.idea.auth.oauth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackPageTest {
    @Test
    fun successShowsTheCheckmark() {
        val page = CallbackPage.success()

        assertTrue("title", page.contains("<title>Authorization successful — CircleCI JetBrains Plugin</title>"))
        assertTrue("heading", page.contains("<h1>Authorization successful</h1>"))
        assertTrue("checkmark", page.contains("M7 18L15 26L29 10"))
        assertFalse("no cross", page.contains("M10 10L26 26M26 10L10 26"))
        assertFalse("template filled in", page.contains("{{"))
    }

    @Test
    fun failureShowsTheCrossAndWhy() {
        val page = CallbackPage.failure("access_denied: <the user> said no")

        assertTrue("heading", page.contains("<h1>Authorization failed</h1>"))
        assertTrue("cross", page.contains("M10 10L26 26M26 10L10 26"))
        assertFalse("no checkmark", page.contains("M7 18L15 26L29 10"))
        assertTrue("reason, escaped", page.contains("<p>access_denied: &lt;the user&gt; said no</p>"))
        assertFalse("template filled in", page.contains("{{"))
    }
}
