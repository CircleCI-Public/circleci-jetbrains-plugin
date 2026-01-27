package com.circleci.idea.lsp

import com.circleci.idea.logging.CircleCILogger
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor

/**
 * LSP Server descriptor for CircleCI YAML Language Server.
 *
 * Configures the language server for CircleCI configuration files.
 */
class CircleCILanguageServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "CircleCI") {

    private val logger = CircleCILogger.getInstance()
    private val lspManager = CircleCILanguageServerManager.getInstance()

    override fun isSupportedFile(file: VirtualFile): Boolean {
        // Support .yml and .yaml files in .circleci directory
        val path = file.path
        return (path.contains("/.circleci/") || path.contains("\\.circleci\\")) &&
                (file.name.endsWith(".yml") || file.name.endsWith(".yaml"))
    }

    override fun createCommandLine(): GeneralCommandLine {
        val binary = lspManager.getLanguageServerBinary()
            ?: throw ExecutionException("CircleCI Language Server binary not found. Please install it from settings.")

        logger.info("Starting CircleCI Language Server: ${binary.absolutePath}")

        return GeneralCommandLine(binary.absolutePath).apply {
            val basePath = this@CircleCILanguageServerDescriptor.project.basePath
            if (basePath != null) {
                withWorkDirectory(basePath)
            }
        }
    }

    override val lspGoToDefinitionSupport = true
    override val lspCompletionSupport = null // Use default completion support
    override val lspDiagnosticsSupport = null // Use default diagnostics support
    override val lspHoverSupport = true
}

/**
 * LSP Server Support Provider for CircleCI.
 */
class CircleCILspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        // Check if this is a CircleCI config file
        val path = file.path
        if ((path.contains("/.circleci/") || path.contains("\\.circleci\\")) &&
            (file.name.endsWith(".yml") || file.name.endsWith(".yaml"))
        ) {
            serverStarter.ensureServerStarted(CircleCILanguageServerDescriptor(project))
        }
    }
}
