package com.circleci.idea.job

import com.circleci.idea.state.Artifact
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

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

/**
 * Lays a job's artifacts out as a file tree, as the CLI's artifact browser does.
 */
object ArtifactTree {
    /** The tree's root, whose children are executions (when parallel) or the top-level entries. */
    fun build(artifacts: List<Artifact>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        val byExecution = artifacts.groupBy { it.execution }.toSortedMap()
        if (byExecution.size > 1) {
            byExecution.forEach { (index, executionArtifacts) ->
                root.add(
                    DefaultMutableTreeNode(ArtifactNode.Execution(index)).also { addEntries(it, executionArtifacts) },
                )
            }
        } else {
            addEntries(root, artifacts)
        }
        return root
    }

    /** The artifacts at and below a node. */
    fun files(node: DefaultMutableTreeNode): List<Artifact> {
        return node.depthFirstEnumeration().toList()
            .mapNotNull { ((it as DefaultMutableTreeNode).userObject as? ArtifactNode.File)?.artifact }
    }

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

    private fun addEntries(
        parent: DefaultMutableTreeNode,
        artifacts: List<Artifact>,
    ) {
        val root = Folder()
        for (artifact in artifacts) {
            val segments = artifact.path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            val directories = segments.dropLast(1)
            val folder = directories.fold(root) { folder, segment -> folder.folders.getOrPut(segment) { Folder() } }
            folder.files.add(artifact)
        }
        addFolder(parent, root)
    }

    private fun addFolder(
        parent: DefaultMutableTreeNode,
        folder: Folder,
    ) {
        for ((name, child) in folder.folders.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
            // Fold a chain of directories that each hold only the next into one node.
            var label = name
            var current = child
            while (current.files.isEmpty() && current.folders.size == 1) {
                val (nextName, next) = current.folders.entries.single()
                label += "/$nextName"
                current = next
            }
            parent.add(DefaultMutableTreeNode(ArtifactNode.Directory(label)).also { addFolder(it, current) })
        }
        folder.files.sortedBy { it.path.substringAfterLast('/').lowercase() }
            .forEach { parent.add(DefaultMutableTreeNode(ArtifactNode.File(it))) }
    }

    private class Folder {
        val folders = linkedMapOf<String, Folder>()
        val files = mutableListOf<Artifact>()
    }
}
