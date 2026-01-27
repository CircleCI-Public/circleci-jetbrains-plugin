package com.circleci.idea.lsp

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.settings.CircleCISettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/**
 * Manages the CircleCI YAML Language Server binary lifecycle.
 *
 * Responsibilities:
 * - Download and install language server binary
 * - Check for updates
 * - Provide binary path for LSP client
 * - Handle platform-specific binary selection
 */
@Service(Service.Level.APP)
class CircleCILanguageServerManager {

    private val logger = CircleCILogger.getInstance()
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val GITHUB_REPO = "CircleCI-Public/circleci-yaml-language-server"
        private const val CURRENT_VERSION_FILE = "version.txt"

        fun getInstance(): CircleCILanguageServerManager {
            return service()
        }

        /**
         * Get platform-specific binary name.
         */
        fun getPlatformBinaryName(): String {
            val isArm = SystemInfo.OS_ARCH.contains("aarch64") || SystemInfo.OS_ARCH.contains("arm64")
            return when {
                SystemInfo.isMac && isArm -> "darwin-arm64-lsp"
                SystemInfo.isMac -> "darwin-amd64-lsp"
                SystemInfo.isLinux && isArm -> "linux-arm64-lsp"
                SystemInfo.isLinux -> "linux-amd64-lsp"
                SystemInfo.isWindows -> "windows-amd64-lsp.exe"
                else -> throw UnsupportedOperationException("Unsupported platform: ${SystemInfo.OS_NAME} ${SystemInfo.OS_ARCH}")
            }
        }
    }

    /**
     * Get the directory where language server binaries are stored.
     */
    private fun getLanguageServerDir(): File {
        val pluginDir = File(PathManager.getPluginsPath(), "circleci-idea-plugin")
        val lspDir = File(pluginDir, "lsp")
        lspDir.mkdirs()
        return lspDir
    }

    /**
     * Get the path to the language server binary.
     * Downloads if not present.
     */
    fun getLanguageServerBinary(): File? {
        return try {
            val lspDir = getLanguageServerDir()
            val binaryName = getPlatformBinaryName()
            val binaryFile = File(lspDir, binaryName)

            if (!binaryFile.exists()) {
                logger.info("Language server binary not found, downloading...")
                downloadLanguageServer()
            }

            if (binaryFile.exists() && binaryFile.canExecute()) {
                binaryFile
            } else {
                logger.warn("Language server binary not executable: ${binaryFile.absolutePath}")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to get language server binary", e)
            null
        }
    }

    /**
     * Download the language server binary from GitHub releases.
     */
    private fun downloadLanguageServer(version: String? = null): Boolean {
        return try {
            val targetVersion = version ?: getLatestVersion() ?: return false
            logger.info("Downloading CircleCI language server version $targetVersion")

            val lspDir = getLanguageServerDir()

            // Download binary
            val binaryName = getPlatformBinaryName()
            if (!downloadFile(targetVersion, binaryName, lspDir)) {
                return false
            }

            // Download schema.json
            if (!downloadFile(targetVersion, "schema.json", lspDir)) {
                logger.warn("Failed to download schema.json, language server may not work correctly")
            }

            // Make binary executable on Unix systems
            val binaryFile = File(lspDir, binaryName)
            if (!SystemInfo.isWindows) {
                val perms = Files.getPosixFilePermissions(binaryFile.toPath()).toMutableSet()
                perms.add(PosixFilePermission.OWNER_EXECUTE)
                perms.add(PosixFilePermission.GROUP_EXECUTE)
                Files.setPosixFilePermissions(binaryFile.toPath(), perms)
            }

            // Save version info
            File(lspDir, CURRENT_VERSION_FILE).writeText(targetVersion)

            logger.info("Successfully downloaded language server to ${binaryFile.absolutePath}")
            true
        } catch (e: Exception) {
            logger.error("Failed to download language server", e)
            false
        }
    }

    /**
     * Download a file from GitHub releases.
     */
    private fun downloadFile(version: String, fileName: String, targetDir: File): Boolean {
        return try {
            val downloadUrl = "https://github.com/$GITHUB_REPO/releases/download/$version/$fileName"
            logger.info("Downloading $fileName from $downloadUrl")

            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.error("Failed to download $fileName: HTTP ${response.code}")
                    return false
                }

                val targetFile = File(targetDir, fileName)
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                logger.info("Successfully downloaded $fileName to ${targetFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            logger.error("Failed to download $fileName", e)
            false
        }
    }

    /**
     * Get the latest version from GitHub releases.
     */
    private fun getLatestVersion(): String? {
        return try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.error("Failed to fetch latest version: HTTP ${response.code}")
                    return null
                }

                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, JsonObject::class.java)
                json.get("tag_name")?.asString
            }
        } catch (e: Exception) {
            logger.error("Failed to get latest version", e)
            null
        }
    }

    /**
     * Get currently installed version.
     */
    fun getCurrentVersion(): String? {
        val versionFile = File(getLanguageServerDir(), CURRENT_VERSION_FILE)
        return if (versionFile.exists()) {
            versionFile.readText().trim()
        } else {
            null
        }
    }

    /**
     * Check if an update is available.
     */
    fun checkForUpdate(): String? {
        val currentVersion = getCurrentVersion() ?: return getLatestVersion()
        val latestVersion = getLatestVersion() ?: return null

        return if (currentVersion != latestVersion) {
            latestVersion
        } else {
            null
        }
    }

    /**
     * Update the language server to the latest version.
     */
    fun updateLanguageServer(): Boolean {
        val latestVersion = getLatestVersion() ?: return false
        logger.info("Updating language server to version $latestVersion")
        return downloadLanguageServer(latestVersion)
    }

    /**
     * Delete the language server binary and metadata.
     */
    fun deleteLanguageServer() {
        try {
            val lspDir = getLanguageServerDir()
            lspDir.listFiles()?.forEach { it.delete() }
            logger.info("Deleted language server files")
        } catch (e: Exception) {
            logger.error("Failed to delete language server", e)
        }
    }
}
