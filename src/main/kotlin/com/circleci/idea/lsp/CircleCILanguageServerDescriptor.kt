package com.circleci.idea.lsp

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.settings.CircleCIConfigurable
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem

/**
 * Whether the file is a CircleCI config file (a .yml or .yaml file in a .circleci directory).
 */
internal fun isCircleCIConfigFile(file: VirtualFile): Boolean {
    val path = file.path
    return (path.contains("/.circleci/") || path.contains("\\.circleci\\")) &&
        (file.name.endsWith(".yml") || file.name.endsWith(".yaml"))
}

/**
 * Starts the CircleCI YAML Language Server when a CircleCI config file is opened.
 */
class CircleCILspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter,
    ) {
        if (isCircleCIConfigFile(file)) {
            serverStarter.ensureServerStarted(CircleCILanguageServerDescriptor(project))
        }
    }

    override fun createLspServerWidgetItem(
        lspServer: LspServer,
        currentFile: VirtualFile?,
    ): LspServerWidgetItem =
        LspServerWidgetItem(
            lspServer,
            currentFile,
            IconLoader.getIcon("/icons/circleci.svg", CircleCILspServerSupportProvider::class.java),
            CircleCIConfigurable::class.java,
        )
}

/**
 * Configures how the CircleCI YAML Language Server process is launched.
 *
 * LSP features (diagnostics, completion, hover, etc.) are left at the platform defaults,
 * which enable everything the server advertises in its capabilities.
 */
class CircleCILanguageServerDescriptor(project: Project) :
    ProjectWideLspServerDescriptor(project, "CircleCI YAML Language Server") {
    private val logger = CircleCILogger.getInstance()
    private val lspManager = CircleCILanguageServerManager.getInstance()

    override fun isSupportedFile(file: VirtualFile): Boolean = isCircleCIConfigFile(file)

    override fun createCommandLine(): GeneralCommandLine {
        val binary =
            lspManager.getLanguageServerBinary()
                ?: throw ExecutionException(
                    "CircleCI Language Server binary not found. Please install it from settings.",
                )

        logger.info("Starting CircleCI Language Server: ${binary.absolutePath}")

        // Check for schema.json file
        val schemaFile = java.io.File(binary.parentFile, "schema.json")

        return GeneralCommandLine(binary.absolutePath).apply {
            // Add -stdio flag for stdin/stdout communication
            addParameter("-stdio")

            // Add schema file path if it exists
            if (schemaFile.exists()) {
                addParameter("-schema")
                addParameter(schemaFile.absolutePath)
                logger.info("Using schema file: ${schemaFile.absolutePath}")
            } else {
                logger.warn(
                    "Schema file not found at ${schemaFile.absolutePath}, " +
                        "language server may have limited functionality",
                )
            }

            val basePath = project.basePath
            if (basePath != null) {
                withWorkDirectory(basePath)
            }

            // Add CircleCI API token as environment variable
            val authService = CircleCIAuthService.getInstance(project)
            val token = authService.getToken()
            if (token != null && token.isNotEmpty()) {
                withEnvironment("CIRCLECI_CLI_TOKEN", token)
                logger.info("CircleCI token configured for language server")
            } else {
                logger.warn("No CircleCI token found - language server diagnostics may be limited")
            }
        }
    }
}
