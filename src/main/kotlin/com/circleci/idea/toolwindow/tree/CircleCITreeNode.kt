package com.circleci.idea.toolwindow.tree

import com.circleci.idea.api.models.JobInfo
import com.circleci.idea.api.models.PipelineInfo
import com.circleci.idea.api.models.WorkflowInfo
import com.circleci.idea.project.models.CircleCIProject
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
    abstract fun getStatus(): String?

    /**
     * Returns true if this node can have children loaded.
     */
    abstract fun canLoadChildren(): Boolean

    /**
     * Returns true if children have been loaded.
     */
    var childrenLoaded: Boolean = false
}

/**
 * Root node for the tree.
 */
class RootNode : CircleCITreeNode(null) {
    override fun getDisplayText(): String = "CircleCI Projects"

    override fun getStatus(): String? = null

    override fun canLoadChildren(): Boolean = true // Root can contain project nodes
}

/**
 * Node representing a CircleCI project.
 */
class ProjectNode(val project: CircleCIProject) : CircleCITreeNode(project) {
    override fun getDisplayText(): String = project.getDisplayName()

    override fun getStatus(): String? = null

    override fun canLoadChildren(): Boolean = true
}

/**
 * Node representing a pipeline.
 */
class PipelineNode(val pipeline: PipelineInfo) : CircleCITreeNode(pipeline) {
    override fun getDisplayText(): String {
        val number = pipeline.number ?: "unknown"
        val branch = if (pipeline.vcs?.branch != null) " (${pipeline.vcs?.branch})" else ""
        return "Pipeline #$number$branch"
    }

    override fun getStatus(): String? = pipeline.state

    override fun canLoadChildren(): Boolean = true
}

/**
 * Node representing a workflow.
 */
class WorkflowNode(val workflow: WorkflowInfo) : CircleCITreeNode(workflow) {
    override fun getDisplayText(): String = workflow.name

    override fun getStatus(): String? = workflow.status

    override fun canLoadChildren(): Boolean = true
}

/**
 * Node representing a job.
 */
class JobNode(val job: JobInfo) : CircleCITreeNode(job) {
    override fun getDisplayText(): String {
        val number = job.jobNumber?.toString() ?: ""
        return if (number.isNotEmpty()) {
            "${job.name} #$number"
        } else {
            job.name
        }
    }

    override fun getStatus(): String? = job.status

    override fun canLoadChildren(): Boolean = false
}

/**
 * Node representing a "Load More" action for pagination.
 */
class LoadMoreNode(val parentType: String, val nextPageToken: String?) : CircleCITreeNode(null) {
    override fun getDisplayText(): String = "Load More..."

    override fun getStatus(): String? = null

    override fun canLoadChildren(): Boolean = false
}

/**
 * Node representing a loading state.
 */
class LoadingNode : CircleCITreeNode(null) {
    override fun getDisplayText(): String = "Loading..."

    override fun getStatus(): String? = null

    override fun canLoadChildren(): Boolean = false
}

/**
 * Node representing an error state.
 */
class ErrorNode(val message: String) : CircleCITreeNode(null) {
    override fun getDisplayText(): String = "Error: $message"

    override fun getStatus(): String? = "error"

    override fun canLoadChildren(): Boolean = false
}

/**
 * Node representing an empty state.
 */
class EmptyNode(val message: String) : CircleCITreeNode(null) {
    override fun getDisplayText(): String = message

    override fun getStatus(): String? = null

    override fun canLoadChildren(): Boolean = false
}
