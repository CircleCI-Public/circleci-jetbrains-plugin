package com.circleci.idea.job

import com.circleci.idea.state.Artifact
import java.nio.file.Path

/** What a node in the artifacts tree stands for. */
sealed class ArtifactNode {
    abstract val name: String

    /** A parallel execution's artifacts, when there's more than one execution. */
    data class Execution(val index: Int) : ArtifactNode() {
        override val name: String = "Execution $index"
    }

    /** A directory; [name] spans several path segments when they each held only the next. */
    data class Directory(override val name: String) : ArtifactNode()

    data class File(val artifact: Artifact) : ArtifactNode() {
        override val name: String = artifact.path.substringAfterLast('/')
    }
}

/** A node in the artifacts tree, with what's under it. [id] is unique in the tree. */
data class ArtifactEntry(
    val node: ArtifactNode,
    val id: String,
    val children: List<ArtifactEntry> = emptyList(),
) {
    /** The artifacts at and below this entry. */
    val files: List<Artifact>
        get() = ArtifactTree.files(listOf(this))
}

/**
 * Lays a job's artifacts out as a file tree, as the CLI's artifact browser does.
 */
object ArtifactTree {
    /** The tree's top level: executions (when parallel), or the top-level entries. */
    fun build(artifacts: List<Artifact>): List<ArtifactEntry> {
        val byExecution = artifacts.groupBy { it.execution }.toSortedMap()
        if (byExecution.size <= 1) return entries("", artifacts)
        return byExecution.map { (index, executionArtifacts) ->
            val id = "$index:"
            ArtifactEntry(ArtifactNode.Execution(index), id, entries(id, executionArtifacts))
        }
    }

    /** The artifacts at and below [entries]. */
    fun files(entries: List<ArtifactEntry>): List<Artifact> =
        entries.flatMap { entry -> listOfNotNull((entry.node as? ArtifactNode.File)?.artifact) + files(entry.children) }

    /**
     * Where to download [artifact] under [dir]: at its path, inside a
     * directory per execution when the job ran in parallel. Null if the path
     * would escape [dir] (a "../" in it), which is never written.
     */
    fun downloadTarget(
        dir: Path,
        artifact: Artifact,
        parallel: Boolean,
    ): Path? {
        val root = dir.normalize()
        val base = if (parallel) root.resolve(artifact.execution.toString()) else root
        val target = base.resolve(artifact.path.trimStart('/')).normalize()
        return target.takeIf { it.startsWith(root) && it != root }
    }

    private fun entries(
        prefix: String,
        artifacts: List<Artifact>,
    ): List<ArtifactEntry> {
        val root = Folder()
        for (artifact in artifacts) {
            val segments = artifact.path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            val directories = segments.dropLast(1)
            val folder = directories.fold(root) { folder, segment -> folder.folders.getOrPut(segment) { Folder() } }
            folder.files.add(artifact)
        }
        return entries(prefix, root)
    }

    private fun entries(
        prefix: String,
        folder: Folder,
    ): List<ArtifactEntry> {
        val directories =
            folder.folders.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (name, child) ->
                // Fold a chain of directories that each hold only the next into one entry.
                var label = name
                var current = child
                while (current.files.isEmpty() && current.folders.size == 1) {
                    val (nextName, next) = current.folders.entries.single()
                    label += "/$nextName"
                    current = next
                }
                val id = "$prefix$label/"
                ArtifactEntry(ArtifactNode.Directory(label), id, entries(id, current))
            }
        val files =
            folder.files.sortedBy { it.path.substringAfterLast('/').lowercase() }
                .map { ArtifactEntry(ArtifactNode.File(it), prefix + it.path.substringAfterLast('/')) }
        return directories + files
    }

    private class Folder {
        val folders = linkedMapOf<String, Folder>()
        val files = mutableListOf<Artifact>()
    }
}
