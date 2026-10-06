package com.circleci.idea.lsp

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.settings.CircleCISettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Provides the CircleCI YAML Language Server binary, installing it from the latest
 * GitHub release on first use and keeping it up to date in the background.
 */
@Service(Service.Level.APP)
class CircleCILanguageServerManager(private val scope: CoroutineScope) {
    private val logger = CircleCILogger.getInstance()
    private val source = GitHubReleaseSource()
    private val installer: LanguageServerInstaller? =
        ReleaseTarget.current()?.let { LanguageServerInstaller(installRoot(), source, it) }
    private val lastUpdateCheck = AtomicLong(0)

    companion object {
        private val UPDATE_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

        fun getInstance(): CircleCILanguageServerManager = service()

        private fun installRoot(): Path = PathManager.getSystemDir().resolve("circleci").resolve("language-server")
    }

    /**
     * Returns the installed language server, downloading the latest release if none is
     * installed yet. Blocks on the network in that case, so call it off the EDT; the
     * calling thread's progress indicator, if it has one, cancels the download.
     */
    fun getLanguageServer(): InstalledLanguageServer {
        val installer =
            installer ?: throw ExecutionException(
                "The CircleCI language server isn't available for ${SystemInfo.OS_NAME} ${SystemInfo.OS_ARCH}",
            )

        installer.installed()?.let {
            scheduleUpdateCheck(installer, it)
            return it
        }

        logger.info("CircleCI language server not installed, downloading the latest release")
        return installLatest(installer)
    }

    private fun installLatest(installer: LanguageServerInstaller): InstalledLanguageServer =
        try {
            val indicator = ProgressManager.getGlobalProgressIndicator()
            val installed = synchronized(installer) { installer.install(source.latestRelease(indicator), indicator) }
            lastUpdateCheck.set(System.currentTimeMillis())
            logger.info("Installed CircleCI language server ${installed.version}")
            installed
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            throw ExecutionException("Couldn't download the CircleCI language server: ${e.message}", e)
        }

    /**
     * Installs a newer release in the background, at most once per [UPDATE_CHECK_INTERVAL_MS],
     * then restarts running servers onto it.
     */
    private fun scheduleUpdateCheck(
        installer: LanguageServerInstaller,
        current: InstalledLanguageServer,
    ) {
        if (CircleCISettings.getInstance().lspAutoUpdate == "never") return
        val now = System.currentTimeMillis()
        val last = lastUpdateCheck.get()
        if (now - last < UPDATE_CHECK_INTERVAL_MS || !lastUpdateCheck.compareAndSet(last, now)) return

        // Cancelled with the scope, e.g. as the plugin unloads, which the indicator passes on to the downloads.
        scope.launch(Dispatchers.IO) {
            try {
                installer.removeAllExcept(current.version)
                val updated =
                    coroutineToIndicator { indicator ->
                        val latest = source.latestRelease(indicator)
                        if (isValidVersion(latest.version) && compareVersions(latest.version, current.version) > 0) {
                            synchronized(installer) { installer.install(latest, indicator) }
                        } else {
                            null
                        }
                    } ?: return@launch
                logger.info("Updated CircleCI language server from ${current.version} to ${updated.version}")
                restartRunningServers()
                installer.removeAllExcept(updated.version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Couldn't update the CircleCI language server", e)
            }
        }
    }

    private suspend fun restartRunningServers() {
        withContext(Dispatchers.EDT) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) {
                    LspClientManager.getInstance(project)
                        .stopAndRestartClientsIfNeeded(CircleCILspIntegrationProvider::class.java)
                }
            }
        }
    }
}

/**
 * Fetches releases from the language server's GitHub repository, honouring the IDE's proxy settings.
 */
private class GitHubReleaseSource : LanguageServerReleaseSource {
    private val gson = Gson()

    override fun latestRelease(indicator: ProgressIndicator?): LanguageServerRelease {
        val body =
            HttpRequests.request(LATEST_RELEASE_URL)
                .accept("application/vnd.github+json")
                .productNameAsUserAgent()
                .connectTimeout(TIMEOUT_MS)
                .readTimeout(TIMEOUT_MS)
                .readString(indicator)
        val json = gson.fromJson(body, JsonObject::class.java)
        val assets =
            json.getAsJsonArray("assets").map {
                val asset = it.asJsonObject
                LanguageServerRelease.Asset(asset.get("name").asString, asset.get("browser_download_url").asString)
            }
        return LanguageServerRelease(json.get("tag_name").asString, assets)
    }

    override fun download(
        url: String,
        target: Path,
        indicator: ProgressIndicator?,
    ) {
        HttpRequests.request(url)
            .productNameAsUserAgent()
            .connectTimeout(TIMEOUT_MS)
            .readTimeout(TIMEOUT_MS)
            .saveToFile(target, indicator)
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/CircleCI-Public/circleci-yaml-language-server/releases/latest"
        val TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60).toInt()
    }
}
