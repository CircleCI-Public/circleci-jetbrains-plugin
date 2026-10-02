package com.circleci.idea.auth.oauth

import com.intellij.openapi.util.text.StringUtil

/**
 * The page the browser shows once it's back from CircleCI: the CircleCI
 * CLI's (its internal/oauth/page.html), so logging in from either looks the
 * same. The template is the CLI's Go template; this fills in the little of
 * it that it uses.
 */
object CallbackPage {
    private val template: String by lazy {
        checkNotNull(
            CallbackPage::class.java.getResource("callback-page.html"),
        ) { "callback-page.html is missing" }.readText()
    }

    private val successBlock = Regex("""\{\{if \.Success}}(.*?)\{\{else}}(.*?)\{\{end}}""", RegexOption.DOT_MATCHES_ALL)

    fun success(): String =
        render(
            "Authorization successful",
            "You can close this window and return to your IDE.",
            success = true,
        )

    fun failure(reason: String): String = render("Authorization failed", reason, success = false)

    private fun render(
        title: String,
        body: String,
        success: Boolean,
    ): String =
        template
            .replace(successBlock) { if (success) it.groupValues[1] else it.groupValues[2] }
            .replace("{{.Title}}", StringUtil.escapeXmlEntities(title))
            .replace("{{.Body}}", StringUtil.escapeXmlEntities(body))
}
