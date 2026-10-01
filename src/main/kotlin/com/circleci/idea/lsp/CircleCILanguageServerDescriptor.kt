package com.circleci.idea.lsp

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.settings.CircleCIConfigurable
import com.circleci.idea.settings.CircleCISettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem

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
class CircleCILspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (CircleCISettings.getInstance().lspEnabled && isCircleCIConfigFile(file)) {
            clientStarter.ensureClientStarted(CircleCILanguageServerDescriptor(project))
        }
    }

    override fun createWidgetItem(
        lspClient: LspClient,
        currentFile: VirtualFile?,
    ): LspClientWidgetItem =
        LspClientWidgetItem(
            lspClient,
            currentFile,
            IconLoader.getIcon("/icons/circleci.svg", CircleCILspIntegrationProvider::class.java),
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
    ProjectWideLspClientDescriptor(project, "CircleCI YAML Language Server") {
    private val logger = CircleCILogger.getInstance()
    private val lspManager = CircleCILanguageServerManager.getInstance()

    override fun isSupportedFile(file: VirtualFile): Boolean = isCircleCIConfigFile(file)

    override fun createCommandLine(): GeneralCommandLine {
        val server = lspManager.getLanguageServer()
        logger.info("Starting CircleCI Language Server ${server.version}: ${server.binary}")

        return GeneralCommandLine(server.binary.toString()).apply {
            // Communicate over stdin/stdout. The server uses its built-in schema, and logs
            // every request unless debug logging is turned off.
            addParameters("-stdio", "-debug=false")

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
