package com.circleci.idea.lsp

import com.circleci.idea.auth.CircleCIAuthService
import com.circleci.idea.logging.CircleCILogger
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import org.eclipse.lsp4j.services.LanguageServer

/**
 * Language Server Factory for CircleCI YAML Language Server.
 * Creates and configures the language server connection.
 */
class CircleCILanguageServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        return CircleCIStreamConnectionProvider(project)
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl {
        return LanguageClientImpl(project)
    }

    override fun getServerInterface(): Class<out LanguageServer> {
        return LanguageServer::class.java
    }
}

/**
 * Stream connection provider that starts the CircleCI LSP server process.
 */
class CircleCIStreamConnectionProvider(private val project: Project) : StreamConnectionProvider {
    private val logger = CircleCILogger.getInstance()
    private val lspManager = CircleCILanguageServerManager.getInstance()
    private var process: Process? = null

    override fun start() {
        val binary =
            lspManager.getLanguageServerBinary()
                ?: throw ExecutionException(
                    "CircleCI Language Server binary not found. Please install it from settings.",
                )

        logger.info("Starting CircleCI Language Server: ${binary.absolutePath}")

        // Check for schema.json file
        val schemaFile = java.io.File(binary.parentFile, "schema.json")

        val commandLine =
            GeneralCommandLine(binary.absolutePath).apply {
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

        process = commandLine.createProcess()
        logger.info("CircleCI Language Server process started")
    }

    override fun getInputStream() = process?.inputStream

    override fun getOutputStream() = process?.outputStream

    override fun stop() {
        process?.destroy()
        process = null
        logger.info("CircleCI Language Server process stopped")
    }
}

/**
 * Document matcher that determines which files should use the CircleCI language server.
 */
class CircleCIDocumentMatcher : com.redhat.devtools.lsp4ij.DocumentMatcher {
    override fun match(
        file: VirtualFile,
        project: Project,
    ): Boolean {
        // Support .yml and .yaml files in .circleci directory
        val path = file.path
        return (path.contains("/.circleci/") || path.contains("\\.circleci\\")) &&
            (file.name.endsWith(".yml") || file.name.endsWith(".yaml"))
    }
}
