package com.circleci.idea.job

import com.circleci.idea.state.Artifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Paths

class ArtifactTreeTest {
    private fun artifact(
        path: String,
        execution: Int = 0,
    ) = Artifact(path = path, url = "https://output.circle-artifacts.com/$execution/$path", execution = execution)

    /** The tree as indented "name" lines, to compare whole. */
    private fun render(
        entries: List<ArtifactEntry>,
        depth: Int = 0,
    ): List<String> = entries.flatMap { listOf("  ".repeat(depth) + it.node.name) + render(it.children, depth + 1) }

    @Test
    fun testNestsByPathFoldingSingleChildDirectories() {
        val root =
            ArtifactTree.build(
                listOf(
                    artifact("dist/release/checksums.txt"),
                    artifact("dist/release/linux/server.tar.gz"),
                    artifact("coverage.html"),
                    artifact("dist/release/Darwin.tar.gz"),
                ),
            )

        assertEquals(
            "directories first, both sorted; dist/release folded",
            listOf(
                "dist/release",
                "  linux",
                "    server.tar.gz",
                "  checksums.txt",
                "  Darwin.tar.gz",
                "coverage.html",
            ),
            render(root),
        )
    }

    @Test
    fun testGroupsByExecutionWhenParallel() {
        val root = ArtifactTree.build(listOf(artifact("out.log", 1), artifact("out.log", 0)))

        assertEquals(
            "one node per execution",
            listOf("Execution 0", "  out.log", "Execution 1", "  out.log"),
            render(root),
        )
        assertEquals("files under the tree", 2, ArtifactTree.files(root).size)
        assertEquals("files under an entry", 1, root.first().files.size)
        assertEquals("ids apart by execution", 4, (root + root.flatMap { it.children }).map { it.id }.distinct().size)
    }

    @Test
    fun testDownloadTarget() {
        val dir = Paths.get("/tmp/artifacts")

        assertEquals(
            "at its path",
            dir.resolve("dist/a.txt"),
            ArtifactTree.downloadTarget(dir, artifact("dist/a.txt"), false),
        )
        assertEquals(
            "under its execution when parallel",
            dir.resolve("2/a.txt"),
            ArtifactTree.downloadTarget(dir, artifact("a.txt", 2), true),
        )
        assertEquals(
            "leading slash stays inside",
            dir.resolve("a.txt"),
            ArtifactTree.downloadTarget(dir, artifact("/a.txt"), false),
        )
        assertNull("never outside the folder", ArtifactTree.downloadTarget(dir, artifact("../../etc/passwd"), false))
    }
}
