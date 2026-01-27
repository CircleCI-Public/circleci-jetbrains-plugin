package com.circleci.idea.ssh

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.settings.CircleCISettings
import com.circleci.idea.state.JobDetails
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Service for managing SSH connections to CircleCI jobs.
 * Handles SSH key detection, command formatting, and terminal integration.
 */
@Service(Service.Level.PROJECT)
class CircleCISshService(private val project: Project) {
    private val logger = CircleCILogger.getInstance()
    private val settings = CircleCISettings.getInstance()

    /**
     * Open SSH session by launching terminal with SSH command.
     * This opens the system terminal with the SSH command ready to execute.
     */
    fun openSshSession(jobDetails: JobDetails): String? {
        if (!jobDetails.sshEnabled) {
            logger.warn("SSH is not enabled for job ${jobDetails.jobNumber}")
            return null
        }

        val sshCommand = buildSshCommand(jobDetails)
        if (sshCommand == null) {
            logger.error("Failed to build SSH command for job ${jobDetails.jobNumber}")
            return null
        }

        logger.info("SSH command prepared: $sshCommand")

        // Launch system terminal with SSH command
        try {
            val runtime = Runtime.getRuntime()
            val terminalCommand =
                when {
                    SystemInfo.isMac -> {
                        // macOS: Open Terminal.app with the SSH command
                        arrayOf("osascript", "-e", "tell application \"Terminal\" to do script \"$sshCommand\"")
                    }
                    SystemInfo.isLinux -> {
                        // Linux: Try common terminal emulators
                        when {
                            File("/usr/bin/gnome-terminal").exists() ->
                                arrayOf("gnome-terminal", "--", "bash", "-c", "$sshCommand; exec bash")
                            File("/usr/bin/konsole").exists() ->
                                arrayOf("konsole", "-e", "bash", "-c", "$sshCommand; exec bash")
                            File("/usr/bin/xterm").exists() ->
                                arrayOf("xterm", "-e", "bash", "-c", "$sshCommand; exec bash")
                            else ->
                                arrayOf("x-terminal-emulator", "-e", "bash", "-c", "$sshCommand; exec bash")
                        }
                    }
                    SystemInfo.isWindows -> {
                        // Windows: Use Windows Terminal if available, otherwise cmd
                        val wtPath = File("${System.getenv("LOCALAPPDATA")}\\Microsoft\\WindowsApps\\wt.exe")
                        if (wtPath.exists()) {
                            arrayOf("wt.exe", "wsl", "bash", "-c", sshCommand)
                        } else {
                            arrayOf("cmd", "/c", "start", "cmd", "/k", sshCommand)
                        }
                    }
                    else -> null
                }

            if (terminalCommand != null) {
                runtime.exec(terminalCommand)
                logger.info("Launched terminal with SSH command")
            }
        } catch (e: Exception) {
            logger.error("Failed to launch terminal: ${e.message}", e)
        }

        return sshCommand
    }

    /**
     * Build SSH command for connecting to a job.
     */
    fun buildSshCommand(jobDetails: JobDetails): String? {
        if (!jobDetails.sshEnabled || jobDetails.sshHost == null) {
            return null
        }

        val host = jobDetails.sshHost
        val port = jobDetails.sshPort ?: 22
        val user = jobDetails.sshUser ?: "circleci"
        val keyPath = detectSshKey()

        return if (keyPath != null) {
            "ssh -p $port -i \"$keyPath\" $user@$host"
        } else {
            // No key path - rely on ssh-agent or default keys
            "ssh -p $port $user@$host"
        }
    }

    /**
     * Detect SSH private key path.
     * Priority:
     * 1. User-configured path in settings
     * 2. Auto-detect from ~/.ssh (id_rsa, id_ed25519, id_ecdsa)
     */
    fun detectSshKey(): String? {
        // Check user-configured path
        if (settings.sshKeyPath.isNotEmpty()) {
            val configuredKey = File(settings.sshKeyPath)
            if (configuredKey.exists() && configuredKey.canRead()) {
                logger.debug("Using configured SSH key: ${settings.sshKeyPath}")
                return settings.sshKeyPath
            } else {
                logger.warn("Configured SSH key not found or not readable: ${settings.sshKeyPath}")
            }
        }

        // Auto-detect from ~/.ssh
        val userHome = System.getProperty("user.home")
        val sshDir = File(userHome, ".ssh")

        if (!sshDir.exists() || !sshDir.isDirectory) {
            logger.debug("~/.ssh directory not found")
            return null
        }

        // Try common SSH key files in order
        val commonKeyFiles =
            listOf(
                "id_ed25519",
                "id_rsa",
                "id_ecdsa",
                "id_dsa",
            )

        for (keyFileName in commonKeyFiles) {
            val keyFile = File(sshDir, keyFileName)
            if (keyFile.exists() && keyFile.canRead()) {
                logger.debug("Auto-detected SSH key: ${keyFile.absolutePath}")
                return keyFile.absolutePath
            }
        }

        logger.debug("No SSH key auto-detected in ~/.ssh")
        return null
    }

    /**
     * Check if SSH is available for a project.
     * GitHub App and GitLab projects don't support SSH.
     */
    fun isSshAvailable(projectSlug: String): Boolean {
        // GitHub App projects use different format
        if (projectSlug.contains("github-app") || projectSlug.contains("gitlab")) {
            return false
        }
        return true
    }

    /**
     * Get platform-specific SSH executable.
     */
    private fun getSshExecutable(): String {
        return when {
            SystemInfo.isWindows -> {
                // Check for WSL
                val wslPath = File("C:\\Windows\\System32\\wsl.exe")
                if (wslPath.exists()) {
                    "wsl ssh"
                } else {
                    // Use Windows SSH (Windows 10+)
                    "ssh"
                }
            }
            else -> "ssh"
        }
    }

    /**
     * Validate SSH connection details.
     */
    fun validateSshDetails(jobDetails: JobDetails): SshValidationResult {
        if (!jobDetails.sshEnabled) {
            return SshValidationResult.NotEnabled
        }

        if (jobDetails.sshHost == null) {
            return SshValidationResult.MissingHost
        }

        if (jobDetails.projectSlug != null && !isSshAvailable(jobDetails.projectSlug)) {
            return SshValidationResult.NotSupported
        }

        return SshValidationResult.Valid
    }

    companion object {
        fun getInstance(project: Project): CircleCISshService {
            return project.getService(CircleCISshService::class.java)
        }
    }
}

/**
 * Result of SSH validation.
 */
sealed class SshValidationResult {
    object Valid : SshValidationResult()

    object NotEnabled : SshValidationResult()

    object MissingHost : SshValidationResult()

    object NotSupported : SshValidationResult()
}
