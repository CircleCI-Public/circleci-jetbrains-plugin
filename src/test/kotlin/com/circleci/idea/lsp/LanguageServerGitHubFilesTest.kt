package com.circleci.idea.lsp

import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.TimeUnit

class LanguageServerGitHubFilesTest : BasePlatformTestCase() {
    private class FakeFetcher(
        private val available: Boolean,
        private val files: Map<String, String>,
    ) : GitHubFileFetcher {
        override fun isAvailable(): Boolean = available

        override fun accountChanges(project: Project): Flow<Unit> = emptyFlow()

        override fun fetch(
            project: Project,
            url: String,
        ): GitHubFile = files[url]?.let { GitHubFile.Found(it) } ?: GitHubFile.NotFound
    }

    private fun useFetchers(vararg fetchers: GitHubFileFetcher) {
        ExtensionTestUtil.maskExtensions(GitHubFileFetcher.EP_NAME, fetchers.toList(), testRootDisposable)
    }

    private fun fetch(url: String) = LanguageServerGitHubFiles.getInstance(project).fetch(url).get(10, TimeUnit.SECONDS)

    fun testTellsTheServerItFetchesFilesWhenAFetcherIsAvailable() {
        useFetchers(FakeFetcher(available = true, files = emptyMap()))

        val options = LanguageServerGitHubFiles.getInstance(project).initializationOptions()

        assertEquals("options", mapOf(FETCHES_GITHUB_FILES_OPTION to true), options)
    }

    fun testTellsTheServerNothingWithoutAnAvailableFetcher() {
        useFetchers(FakeFetcher(available = false, files = emptyMap()))

        val options = LanguageServerGitHubFiles.getInstance(project).initializationOptions()

        assertEquals("options", emptyMap<String, Any>(), options)
    }

    fun testAnswersWithTheFileTheFetcherFinds() {
        val url = "https://raw.githubusercontent.com/acme/orbs/main/go.yml"
        useFetchers(FakeFetcher(available = true, files = mapOf(url to "version: 2.1\n")))

        val result = fetch(url)

        assertEquals("result", FetchGitHubFileResult(found = true, source = "version: 2.1\n"), result)
    }

    fun testAnswersNotFoundWhenTheFetcherFindsNothing() {
        useFetchers(FakeFetcher(available = true, files = emptyMap()))

        val result = fetch("https://raw.githubusercontent.com/acme/orbs/main/go.yml")

        assertEquals("result", FetchGitHubFileResult(found = false), result)
    }

    fun testAnswersNotFoundWithoutAnAvailableFetcher() {
        val url = "https://raw.githubusercontent.com/acme/orbs/main/go.yml"
        useFetchers(FakeFetcher(available = false, files = mapOf(url to "version: 2.1\n")))

        val result = fetch(url)

        assertEquals("result", FetchGitHubFileResult(found = false), result)
    }
}
