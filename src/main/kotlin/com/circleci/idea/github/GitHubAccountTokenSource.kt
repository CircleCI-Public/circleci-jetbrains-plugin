package com.circleci.idea.github

import com.circleci.idea.lsp.GitHubTokenSource
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount

/**
 * The token of the GitHub account the IDE is logged in to github.com with,
 * preferring the project's default account.
 */
class GitHubAccountTokenSource : GitHubTokenSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun tokens(project: Project): Flow<String?> {
        val accountManager = service<GHAccountManager>()
        return accountManager.accountsState
            .map { accounts -> gitHubDotComAccount(project, accounts) }
            .distinctUntilChanged()
            .flatMapLatest { account ->
                if (account == null) {
                    flowOf(null)
                } else {
                    accountManager.getCredentialsFlow(account)
                        .onStart { emit(accountManager.findCredentials(account)) }
                }
            }
            .distinctUntilChanged()
    }

    // Orbs are fetched from raw.githubusercontent.com, which only a github.com token reads.
    private fun gitHubDotComAccount(
        project: Project,
        accounts: Set<GithubAccount>,
    ): GithubAccount? {
        val onGitHubDotCom = accounts.filter { it.server.isGithubDotCom }
        val default = GHAccountsUtil.getDefaultAccount(project)
        return default?.takeIf { it in onGitHubDotCom } ?: onGitHubDotCom.firstOrNull()
    }
}
