package com.circleci.idea.websocket

/**
 * Events from CircleCI WebSocket.
 */
sealed class CircleCIWebSocketEvent {
    /**
     * Workflow completed event.
     */
    data class WorkflowCompleted(
        val workflowId: String,
        val status: String,
        val projectSlug: String,
        val pipelineId: String,
    ) : CircleCIWebSocketEvent()

    /**
     * Job started event.
     */
    data class JobStarted(
        val jobId: String,
        val jobNumber: Long?,
        val workflowId: String,
        val projectSlug: String,
    ) : CircleCIWebSocketEvent()

    /**
     * Job completed event.
     */
    data class JobCompleted(
        val jobId: String,
        val jobNumber: Long?,
        val status: String,
        val workflowId: String,
        val projectSlug: String,
    ) : CircleCIWebSocketEvent()

    /**
     * Connection established.
     */
    object Connected : CircleCIWebSocketEvent()

    /**
     * Connection closed.
     */
    data class Disconnected(val reason: String?) : CircleCIWebSocketEvent()

    /**
     * Error occurred.
     */
    data class Error(val message: String, val throwable: Throwable?) : CircleCIWebSocketEvent()
}
