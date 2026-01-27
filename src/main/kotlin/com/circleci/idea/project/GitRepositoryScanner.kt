package com.circleci.idea.project

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.models.CircleCIProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Scans workspace for git repositories and detects CircleCI projects.
 */
class GitRepositoryScanner(private val project: Project) {
    private val logger = CircleCILogger.getInstance()

    /**
     * Scan all git repositories in the workspace.
     */
    fun scanForProjects(): List<CircleCIProject> {
        logger.info("Scanning workspace for CircleCI projects")

        val projects = mutableListOf<CircleCIProject>()

        try {
            // Try to use Git4Idea if available
            val gitRepositoryManagerClass = Class.forName("git4idea.repo.GitRepositoryManager")
            val getInstance = gitRepositoryManagerClass.getMethod("getInstance", Project::class.java)
            val gitRepositoryManager = getInstance.invoke(null, project) ?: return emptyList()

            val getRepositories = gitRepositoryManagerClass.getMethod("getRepositories")

            @Suppress("UNCHECKED_CAST")
            val repositories = getRepositories.invoke(gitRepositoryManager) as List<*>

            logger.debug("Found ${repositories.size} git repositories")

            for (repository in repositories) {
                if (repository != null) {
                    val detectedProjects = detectProjectsInRepository(repository)
                    projects.addAll(detectedProjects)
                }
            }

            logger.info("Detected ${projects.size} CircleCI projects in workspace")
        } catch (e: ClassNotFoundException) {
            logger.warn("Git plugin not available, skipping git repository detection")
        } catch (e: Exception) {
            logger.error("Failed to scan for projects", e)
        }

        return projects
    }

    /**
     * Detect CircleCI projects in a git repository.
     */
    private fun detectProjectsInRepository(repository: Any): List<CircleCIProject> {
        val projects = mutableListOf<CircleCIProject>()

        try {
            val repositoryClass = repository::class.java
            val getRoot = repositoryClass.getMethod("getRoot")
            val root = getRoot.invoke(repository) as VirtualFile

            logger.debug("Scanning repository: ${root.path}")

            // Get remote URLs
            val getRemotes = repositoryClass.getMethod("getRemotes")

            @Suppress("UNCHECKED_CAST")
            val remotes = getRemotes.invoke(repository) as Collection<*>
            logger.debug("Found ${remotes.size} remotes")

            for (remote in remotes) {
                val remoteClass = remote!!::class.java
                val getName = remoteClass.getMethod("getName")
                val remoteName = getName.invoke(remote) as String

                val getUrls = remoteClass.getMethod("getUrls")

                @Suppress("UNCHECKED_CAST")
                val urls = getUrls.invoke(remote) as Collection<String>
                logger.debug("Remote '$remoteName' has ${urls.size} URLs")

                for (url in urls) {
                    logger.debug("Parsing remote URL: $url")

                    val project = GitRemoteParser.parseRemoteUrl(url)
                    if (project != null) {
                        // Add local path information
                        val projectWithPath =
                            project.copy(
                                localPath = root.path,
                            )
                        projects.add(projectWithPath)
                        logger.info("Detected project: ${projectWithPath.slug} at ${root.path}")
                    } else {
                        logger.debug("Could not parse remote URL: $url")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to detect projects in repository", e)
        }

        return projects.distinctBy { it.slug }
    }

    /**
     * Check if a directory contains a CircleCI config file.
     */
    fun hasCircleCIConfig(directory: VirtualFile): Boolean {
        val circleCIDir = directory.findChild(".circleci") ?: return false
        val configFile = circleCIDir.findChild("config.yml") ?: circleCIDir.findChild("config.yaml")
        return configFile != null && !configFile.isDirectory
    }

    /**
     * Check if a path contains a CircleCI config file.
     */
    fun hasCircleCIConfig(path: String): Boolean {
        val configYml = File(path, ".circleci/config.yml")
        val configYaml = File(path, ".circleci/config.yaml")
        return configYml.exists() || configYaml.exists()
    }

    /**
     * Get the CircleCI config file for a project path.
     */
    fun getCircleCIConfigFile(path: String): File? {
        val configYml = File(path, ".circleci/config.yml")
        if (configYml.exists()) return configYml

        val configYaml = File(path, ".circleci/config.yaml")
        if (configYaml.exists()) return configYaml

        return null
    }
}
