package com.circleci.idea.project

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.project.models.CircleCIProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.IOException
import java.nio.file.Path

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
            logger.warn("Git plugin not available, skipping git repository detection", e)
        } catch (e: Exception) {
            logger.error("Failed to scan for projects", e)
        }

        // A linked project directory needn't be a git repository, or one the IDE knows of
        project.basePath?.takeIf { base -> projects.none { it.localPath == base } }?.let { base ->
            linkedProject(base)?.let { projects.add(0, it) }
        }

        // Linked projects first, so they're the ones picked when there's no choice yet
        return projects.sortedByDescending { it.linked }.distinctBy { it.slug }
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

            // A link from `circleci project link` says which project this is; its remotes' projects follow
            linkedProject(root.path)?.let { projects.add(it) }

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
            logger.warn("Failed to detect projects in repository", e)
        }

        return projects.distinctBy { it.slug }
    }

    /**
     * The project [root]'s `.circleci/info.yml` links it to, or null if it has no
     * (usable) link.
     */
    private fun linkedProject(root: String): CircleCIProject? {
        val info =
            try {
                ProjectLinkFile.read(Path.of(root)) ?: return null
            } catch (e: IOException) {
                logger.warn("Ignoring $root/${ProjectLinkFile.PATH}: ${e.message}")
                return null
            }
        return linkedProject(info, root).also {
            if (it == null) logger.warn("Ignoring $root/${ProjectLinkFile.PATH}: \"${info.slug}\" isn't a project slug")
        }
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

/** The project [info] links the checkout at [root] to, or null if its slug isn't one. */
internal fun linkedProject(
    info: ProjectLinkFile.Info,
    root: String?,
): CircleCIProject? {
    val label =
        if (!info.orgName.isNullOrEmpty() && !info.projectName.isNullOrEmpty()) {
            "${info.orgName}/${info.projectName}"
        } else {
            null
        }
    return CircleCIProject.fromSlug(info.effectiveSlug)?.copy(localPath = root, label = label, linked = true)
}
