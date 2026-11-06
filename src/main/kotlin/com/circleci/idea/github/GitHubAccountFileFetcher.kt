package com.circleci.idea.github

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.lsp.GitHubFile
import com.circleci.idea.lsp.GitHubFileFetcher
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.jetbrains.plugins.github.api.GithubApiRequest
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.api.GithubApiResponse
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.exceptions.GithubAuthenticationException
import org.jetbrains.plugins.github.exceptions.GithubStatusCodeException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.HttpURLConnection

/**
 * Fetches files on GitHub as the GitHub account the IDE is logged in to github.com
 * with, preferring the project's default account. The GitHub plugin sends the
 * requests, so the account's token never leaves it.
 *
 * Only 2026.3 and later can: they add `GithubApiRequestExecutor.Factory.create(GithubAccount)`,
 * in place of the token they stopped handing out, and `GHAccountsUtil.accountsState`. Both
 * are looked up at runtime, so the plugin still loads on the IDEs before.
 */
class GitHubAccountFileFetcher : GitHubFileFetcher {
    private val logger = CircleCILogger.getInstance()

    private val createExecutor: Method? by lazy {
        runCatching {
            GithubApiRequestExecutor.Factory::class.java.getMethod("create", GithubAccount::class.java)
        }.getOrNull()
    }

    // Also 2026.3's, and as needed: before it, there's nothing to fetch with.
    private val accountsState: Method? by lazy {
        runCatching { GHAccountsUtil::class.java.getMethod("getAccountsState") }.getOrNull()
    }

    override fun isAvailable(): Boolean = createExecutor != null && accountsState != null

    @Suppress("UNCHECKED_CAST")
    override fun accountChanges(project: Project): Flow<Unit> {
        val accounts = accountsState?.invoke(GHAccountsUtil) as? StateFlow<Set<GithubAccount>> ?: return emptyFlow()
        return accounts
            .map { gitHubDotComAccount(project, it) }
            .distinctUntilChanged()
            .drop(1)
            .map { }
    }

    override fun fetch(
        project: Project,
        url: String,
    ): GitHubFile {
        val file = GitHubFileUrl.parse(url)
        val account = file?.let { gitHubDotComAccount(project) }
        val executor = account?.let { executorFor(it) }
        if (file == null || account == null || executor == null) return GitHubFile.NotFound
        return try {
            executor.execute(EmptyProgressIndicator(), RawContents(file.contentsUrl(account.server.toApiUrl())))
                ?.let { GitHubFile.Found(it) }
                ?: GitHubFile.NotFound
        } catch (e: GithubAuthenticationException) {
            logger.info("Can't fetch $url as GitHub account ${account.name}", e)
            GitHubFile.NotFound
        } catch (e: GithubStatusCodeException) {
            if (e.statusCode in NOT_FOUND_STATUSES) GitHubFile.NotFound else throw e
        }
    }

    private fun executorFor(account: GithubAccount): GithubApiRequestExecutor? {
        val create = createExecutor ?: return null
        return try {
            create.invoke(GithubApiRequestExecutor.Factory.getInstance(), account) as GithubApiRequestExecutor
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    // Orbs fetched by URL are on github.com, so an Enterprise account can't see them.
    private fun gitHubDotComAccount(
        project: Project,
        accounts: Set<GithubAccount> = GHAccountsUtil.accounts,
    ): GithubAccount? {
        val onGitHubDotCom = accounts.filter { it.server.isGithubDotCom }
        val default = GHAccountsUtil.getDefaultAccount(project)
        return default?.takeIf { it in onGitHubDotCom } ?: onGitHubDotCom.firstOrNull()
    }

    /** A file's contents, through the REST API's raw media type; null when it isn't there. */
    private class RawContents(url: String) : GithubApiRequest.Get.Optional<String>(url, "application/vnd.github.raw") {
        override fun extractResult(response: GithubApiResponse): String = response.readBody { it.readText() }
    }

    private companion object {
        // What the language server takes as nothing there: 404 is a file GitHub won't
        // show this account, as well as one that doesn't exist.
        val NOT_FOUND_STATUSES =
            setOf(
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                HttpURLConnection.HTTP_NOT_FOUND,
                HttpURLConnection.HTTP_GONE,
            )
    }
}
