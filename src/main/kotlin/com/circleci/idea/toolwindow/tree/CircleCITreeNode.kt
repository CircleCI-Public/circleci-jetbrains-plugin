package com.circleci.idea.toolwindow.tree

import com.circleci.idea.run.RunStatus
import com.circleci.idea.state.Job
import com.circleci.idea.state.Run
import com.circleci.idea.state.Workflow
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Base class for all CircleCI tree nodes.
 */
sealed class CircleCITreeNode(userObject: Any?) : DefaultMutableTreeNode(userObject) {
    /**
     * Returns the display text for this node.
     */
    abstract fun getDisplayText(): String

    /**
     * Returns the status for this node (for icon display).
     */
    open fun getStatus(): RunStatus? = null

    /**
     * Returns true if this node can have children loaded.
     */
    abstract fun canLoadChildren(): Boolean

    /**
     * Returns true if children have been loaded.
     */
    var childrenLoaded: Boolean = false

    /**
     * Bumped each time a load of this node's children starts, so a load that
     * finishes after a newer one has started can tell its result is stale.
     */
    var loadGeneration: Int = 0
}

/**
 * The tree's (hidden) root, whose children are the runs listed.
 */
class RootNode : CircleCITreeNode(null) {
    override fun getDisplayText(): String = "Runs"

    override fun canLoadChildren(): Boolean = true
}

/**
 * Node representing a run.
 *
 * @property showProject Whether the label names the run's project, for lists spanning projects
 */
class RunNode(run: Run, val showProject: Boolean = false) : CircleCITreeNode(run) {
    // Replaced when loading workflows resolves details the run listing lacked.
    var run: Run = run
        set(value) {
            field = value
            userObject = value
        }

    override fun getDisplayText(): String {
        val number = run.number?.let { "#$it " }.orEmpty()
        val description =
            run.commitSubject?.let { subjectLine(it) }
                ?: run.errors.firstOrNull()?.let { errorSummary(it.message ?: it.type.orEmpty()) }
                ?: run.status.label
        return number + description
    }

    /** The ref the run was for, e.g. "main", or "org/repo:main" when [showProject]. */
    fun getRefText(): String? {
        val ref = run.branch ?: run.tag
        val project = if (showProject) run.repositoryName else null
        return when {
            project != null && ref != null -> "$project:$ref"
            else -> project ?: ref
        }
    }

    override fun getStatus(): RunStatus = run.status

    override fun canLoadChildren(): Boolean = true

    private companion object {
        const val SUBJECT_MAX = 50
        const val ERROR_MAX = 60

        /** A commit subject's first line, capped in length. */
        fun subjectLine(subject: String): String = truncate(subject.lineSequence().first().trim(), SUBJECT_MAX)

        /** An error message's first sentence, capped in length. */
        fun errorSummary(message: String): String {
            val line = message.trim().lineSequence().firstOrNull().orEmpty()
            val sentenceEnd = line.indexOf(". ")
            return truncate(if (sentenceEnd >= 0) line.substring(0, sentenceEnd + 1) else line, ERROR_MAX)
        }

        fun truncate(
            text: String,
            max: Int,
        ): String = if (text.length > max) text.take(max).trimEnd() + "…" else text
    }
}

/**
 * Node representing a workflow.
 */
class WorkflowNode(workflow: Workflow) : CircleCITreeNode(workflow) {
    // Replaced when a refresh finds the workflow still listed.
    var workflow: Workflow = workflow
        set(value) {
            field = value
            userObject = value
        }

    override fun getDisplayText(): String = workflow.name

    override fun getStatus(): RunStatus = workflow.status

    override fun canLoadChildren(): Boolean = true
}

/**
 * Node representing a job.
 */
class JobNode(val job: Job) : CircleCITreeNode(job) {
    override fun getDisplayText(): String {
        val number = job.number?.toString() ?: ""
        return if (number.isNotEmpty()) {
            "${job.name} #$number"
        } else {
            job.name
        }
    }

    override fun getStatus(): RunStatus = job.status

    override fun canLoadChildren(): Boolean = false
}

/**
 * Node representing a "Load More" action for pagination.
 */
class LoadMoreNode(val nextCursor: String) : CircleCITreeNode(null) {
    override fun getDisplayText(): String = "Load More..."

    override fun canLoadChildren(): Boolean = false
}

/**
 * Node representing a loading state.
 */
class LoadingNode : CircleCITreeNode(null) {
    override fun getDisplayText(): String = "Loading..."

    override fun canLoadChildren(): Boolean = false
}

/**
 * Node representing an error state.
 */
class ErrorNode(val message: String) : CircleCITreeNode(null) {
    override fun getDisplayText(): String = "Error: $message"

    override fun canLoadChildren(): Boolean = false
}

/**
 * Node representing an empty state.
 */
class EmptyNode(val message: String) : CircleCITreeNode(null) {
    override fun getDisplayText(): String = message

    override fun canLoadChildren(): Boolean = false
}
