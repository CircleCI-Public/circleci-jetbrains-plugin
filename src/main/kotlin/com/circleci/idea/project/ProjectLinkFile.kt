package com.circleci.idea.project

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `.circleci/info.yml` that `circleci project link` writes, binding a
 * checkout to its CircleCI project. Where there is one, it says which project
 * the checkout is, ahead of anything guessed from its git remote.
 *
 *     organization:
 *         id: ec6887ec-7d44-4b31-b468-7e552408ee32
 *         name: CircleCI-Public
 *     project:
 *         id: 7097f60c-74d1-4936-8d1a-268d4042a493
 *         slug: gh/CircleCI-Public/circleci-cli
 *         name: circleci-cli
 *
 * Only `project.slug` is required; the rest is filled in when the project
 * could be looked up.
 */
object ProjectLinkFile {
    /** Where the file sits, relative to the checkout's root. */
    const val PATH = ".circleci/info.yml"

    data class Info(
        val slug: String,
        val projectId: String? = null,
        val projectName: String? = null,
        val orgId: String? = null,
        val orgName: String? = null,
    ) {
        /**
         * The slug to look the project up by. A standalone project's slug
         * (`circleci/<org>/<project>`) is rebuilt from the IDs when both are
         * known, so it survives renames; any other is taken as written.
         */
        val effectiveSlug: String
            get() =
                if (slug.startsWith("circleci/") && !projectId.isNullOrEmpty() && !orgId.isNullOrEmpty()) {
                    "circleci/$orgId/$projectId"
                } else {
                    slug
                }
    }

    /** Thrown for a file that's there but can't be read as a link. */
    class InvalidLinkException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun path(root: Path): Path = root.resolve(PATH)

    /** The link in [root], or null if it has none. */
    @Throws(IOException::class)
    fun read(root: Path): Info? {
        val file = path(root)
        if (!Files.isRegularFile(file)) return null
        return parse(Files.readString(file))
    }

    @Throws(InvalidLinkException::class)
    fun parse(text: String): Info {
        val document =
            try {
                Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
            } catch (e: YAMLException) {
                throw InvalidLinkException("$PATH isn't valid YAML: ${e.message}", e)
            }
        val root = document as? Map<*, *>
        val project = root?.get("project") as? Map<*, *>
        val org = root?.get("organization") as? Map<*, *>
        val slug =
            project.string("slug")
                ?: throw InvalidLinkException("$PATH is missing required 'project.slug' field")
        return Info(
            slug = slug,
            projectId = project.string("id"),
            projectName = project.string("name"),
            orgId = org.string("id"),
            orgName = org.string("name"),
        )
    }

    /** Link [root] to [info]'s project, replacing any link it had. */
    @Throws(IOException::class)
    fun write(
        root: Path,
        info: Info,
    ) {
        val file = path(root)
        Files.createDirectories(file.parent)
        Files.writeString(file, format(info))
    }

    /** [info] as the CLI writes it: the same keys, in the same order, leaving out the empty ones. */
    fun format(info: Info): String {
        val org = linkedMapOf<String, String>()
        info.orgId.ifPresent { org["id"] = it }
        info.orgName.ifPresent { org["name"] = it }
        val project = linkedMapOf<String, String>()
        info.projectId.ifPresent { project["id"] = it }
        project["slug"] = info.slug
        info.projectName.ifPresent { project["name"] = it }

        val options =
            DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                indent = 4
            }
        return Yaml(options).dump(linkedMapOf("organization" to org, "project" to project))
    }

    private fun Map<*, *>?.string(key: String): String? = this?.get(key)?.toString()?.takeIf { it.isNotBlank() }

    private inline fun String?.ifPresent(use: (String) -> Unit) {
        if (!isNullOrEmpty()) use(this)
    }
}
