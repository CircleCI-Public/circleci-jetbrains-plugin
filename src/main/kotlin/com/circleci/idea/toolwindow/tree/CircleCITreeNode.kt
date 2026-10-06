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

    /**
     * Its workflows loaded after it ended. They won't change while it stays
     * ended: rerunning a workflow starts the run again.
     */
    var workflowsFinal: Boolean = false

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

    // No length caps: the row's label ends in "…" itself when it runs out of room.
    private companion object {
        /** A commit subject's first line. */
        fun subjectLine(subject: String): String = subject.lineSequence().first().trim()

        /** An error message's first sentence. */
        fun errorSummary(message: String): String {
            val line = message.trim().lineSequence().firstOrNull().orEmpty()
            val sentenceEnd = line.indexOf(". ")
            return if (sentenceEnd >= 0) line.substring(0, sentenceEnd + 1) else line
        }
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

    /** Its jobs loaded after it ended, so they won't change: a rerun is a new workflow. */
    var jobsFinal: Boolean = false

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
 * The last row of a run list with more pages: the next page loads as the
 * tree scrolls near it.
 */
class LoadMoreNode : CircleCITreeNode(null) {
    /** Why its page last failed to load, if it did; clicking the row tries again. */
    var error: String? = null

    override fun getDisplayText(): String =
        error?.let { "Couldn't load more runs: $it. Click to retry" } ?: "Loading more runs..."

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
