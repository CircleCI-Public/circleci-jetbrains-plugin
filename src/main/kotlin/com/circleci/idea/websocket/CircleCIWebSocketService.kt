package com.circleci.idea.websocket

import com.circleci.idea.api.ApiResponse
import com.circleci.idea.api.CircleCIApiClient
import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.settings.CircleCISettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import kotlin.math.min
import kotlin.math.pow

/**
 * Service for managing WebSocket connections to CircleCI.
 * Uses Pusher for real-time updates.
 */
@Service(Service.Level.APP)
class CircleCIWebSocketService {
    private val logger = CircleCILogger.getInstance()
    private val gson = Gson()
    private val settings = CircleCISettings.getInstance()

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var pusherConfig: PusherConfig? = null

    private val _events = MutableSharedFlow<CircleCIWebSocketEvent>(replay = 0)
    val events: SharedFlow<CircleCIWebSocketEvent> = _events.asSharedFlow()

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private var reconnectJob: Job? = null

    private val subscribedChannels = mutableSetOf<String>()
    private var isConnected = false

    init {
        logger.logLifecycleEvent("CircleCIWebSocketService initialized")
    }

    /**
     * Connect to CircleCI WebSocket.
     *
     * @param token CircleCI API token
     */
    suspend fun connect(token: String) {
        logger.info("Connecting to CircleCI WebSocket")

        try {
            // Fetch Pusher configuration
            pusherConfig = fetchPusherConfig(token)

            if (pusherConfig == null) {
                logger.error("Failed to fetch Pusher configuration")
                _events.emit(CircleCIWebSocketEvent.Error("Failed to fetch Pusher configuration", null))
                return
            }

            // Create WebSocket client
            client =
                OkHttpClient.Builder()
                    .build()

            // Build WebSocket request
            val url = pusherConfig!!.getWebSocketUrl() + "?protocol=7&client=circleci-plugin&version=1.0.0"
            val request =
                Request.Builder()
                    .url(url)
                    .build()

            // Connect
            webSocket = client!!.newWebSocket(request, PusherWebSocketListener())

            logger.info("WebSocket connection initiated")
        } catch (e: Exception) {
            logger.error("Failed to connect to WebSocket", e)
            _events.emit(CircleCIWebSocketEvent.Error("Failed to connect: ${e.message}", e))
            scheduleReconnect()
        }
    }

    /**
     * Disconnect from WebSocket.
     */
    fun disconnect() {
        logger.info("Disconnecting from CircleCI WebSocket")

        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
        subscribedChannels.clear()
        reconnectAttempts = 0
    }

    /**
     * Subscribe to a project channel.
     *
     * @param projectId Project ID
     */
    suspend fun subscribeToProject(projectId: String) {
        val channel = "private-$projectId"

        if (channel in subscribedChannels) {
            logger.debug("Already subscribed to channel: $channel")
            return
        }

        logger.info("Subscribing to channel: $channel")

        if (!isConnected) {
            logger.warn("Not connected, queuing subscription for $channel")
            subscribedChannels.add(channel)
            return
        }

        try {
            val subscribeMessage =
                mapOf(
                    "event" to "pusher:subscribe",
                    "data" to
                        mapOf(
                            "channel" to channel,
                        ),
                )

            val json = gson.toJson(subscribeMessage)
            webSocket?.send(json)

            subscribedChannels.add(channel)
            logger.debug("Subscribed to channel: $channel")
        } catch (e: Exception) {
            logger.error("Failed to subscribe to channel: $channel", e)
        }
    }

    /**
     * Unsubscribe from a project channel.
     *
     * @param projectId Project ID
     */
    fun unsubscribeFromProject(projectId: String) {
        val channel = "private-$projectId"

        if (channel !in subscribedChannels) {
            return
        }

        logger.info("Unsubscribing from channel: $channel")

        try {
            val unsubscribeMessage =
                mapOf(
                    "event" to "pusher:unsubscribe",
                    "data" to
                        mapOf(
                            "channel" to channel,
                        ),
                )

            val json = gson.toJson(unsubscribeMessage)
            webSocket?.send(json)

            subscribedChannels.remove(channel)
            logger.debug("Unsubscribed from channel: $channel")
        } catch (e: Exception) {
            logger.error("Failed to unsubscribe from channel: $channel", e)
        }
    }

    /**
     * Fetch Pusher configuration from CircleCI API.
     */
    private suspend fun fetchPusherConfig(token: String): PusherConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val apiClient =
                    CircleCIApiClient(
                        baseUrl = settings.hostUrl,
                        token = token,
                    )

                val response = apiClient.get("/api/private/pusher/config")

                when (response) {
                    is ApiResponse.Success -> {
                        val key = response.data.get("key")?.asString
                        val cluster = response.data.get("cluster")?.asString
                        val wsEndpoint = response.data.get("ws_endpoint")?.asString

                        if (key != null) {
                            PusherConfig(key, cluster, wsEndpoint)
                        } else {
                            logger.error("Pusher config missing 'key' field")
                            null
                        }
                    }
                    else -> {
                        logger.error("Failed to fetch Pusher config: $response")
                        null
                    }
                }
            } catch (e: Exception) {
                logger.error("Exception fetching Pusher config", e)
                null
            }
        }
    }

    /**
     * Schedule a reconnection attempt with exponential backoff.
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            logger.error("Max reconnection attempts reached, giving up")
            return
        }

        reconnectAttempts++
        val delaySeconds = min(2.0.pow(reconnectAttempts - 1).toInt(), 30)

        logger.info("Scheduling reconnection attempt #$reconnectAttempts in ${delaySeconds}s")

        reconnectJob =
            GlobalScope.launch {
                delay(delaySeconds * 1000L)
                // Reconnect logic would need token - this is simplified
                logger.info("Attempting to reconnect...")
            }
    }

    /**
     * WebSocket listener for Pusher events.
     */
    private inner class PusherWebSocketListener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            logger.info("WebSocket connection opened")
            isConnected = true
            reconnectAttempts = 0

            GlobalScope.launch {
                _events.emit(CircleCIWebSocketEvent.Connected)

                // Re-subscribe to channels
                for (channel in subscribedChannels.toList()) {
                    val projectId = channel.removePrefix("private-")
                    subscribeToProject(projectId)
                }
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            logger.debug("WebSocket message received")

            try {
                val json = gson.fromJson(text, JsonObject::class.java)
                val event = json.get("event")?.asString

                when (event) {
                    "pusher:connection_established" -> {
                        logger.info("Pusher connection established")
                    }
                    "pusher_internal:subscription_succeeded" -> {
                        val channel = json.get("channel")?.asString
                        logger.info("Subscription succeeded: $channel")
                    }
                    "workflow.completed" -> {
                        handleWorkflowCompleted(json)
                    }
                    "job.started" -> {
                        handleJobStarted(json)
                    }
                    "job.completed" -> {
                        handleJobCompleted(json)
                    }
                    else -> {
                        logger.debug("Unhandled event: $event")
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to process WebSocket message", e)
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            logger.error("WebSocket connection failed", t)
            isConnected = false

            GlobalScope.launch {
                _events.emit(CircleCIWebSocketEvent.Disconnected(t.message))
            }

            scheduleReconnect()
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            logger.info("WebSocket connection closed: code=$code, reason=$reason")
            isConnected = false

            GlobalScope.launch {
                _events.emit(CircleCIWebSocketEvent.Disconnected(reason))
            }
        }

        private fun handleWorkflowCompleted(json: JsonObject) {
            try {
                val data = json.getAsJsonObject("data")
                val dataContent = gson.fromJson(data.get("data")?.asString, JsonObject::class.java)

                val workflowId = dataContent.get("workflow_id")?.asString ?: return
                val status = dataContent.get("status")?.asString ?: return
                val projectSlug = dataContent.get("project_slug")?.asString ?: return
                val pipelineId = dataContent.get("pipeline_id")?.asString ?: return

                logger.logWebSocketEvent("workflow.completed", "workflow:$workflowId")

                GlobalScope.launch {
                    _events.emit(
                        CircleCIWebSocketEvent.WorkflowCompleted(
                            workflowId,
                            status,
                            projectSlug,
                            pipelineId,
                        ),
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to handle workflow.completed event", e)
            }
        }

        private fun handleJobStarted(json: JsonObject) {
            try {
                val data = json.getAsJsonObject("data")
                val dataContent = gson.fromJson(data.get("data")?.asString, JsonObject::class.java)

                val jobId = dataContent.get("job_id")?.asString ?: return
                val jobNumber = dataContent.get("job_number")?.asLong
                val workflowId = dataContent.get("workflow_id")?.asString ?: return
                val projectSlug = dataContent.get("project_slug")?.asString ?: return

                logger.logWebSocketEvent("job.started", "job:$jobId")

                GlobalScope.launch {
                    _events.emit(
                        CircleCIWebSocketEvent.JobStarted(
                            jobId,
                            jobNumber,
                            workflowId,
                            projectSlug,
                        ),
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to handle job.started event", e)
            }
        }

        private fun handleJobCompleted(json: JsonObject) {
            try {
                val data = json.getAsJsonObject("data")
                val dataContent = gson.fromJson(data.get("data")?.asString, JsonObject::class.java)

                val jobId = dataContent.get("job_id")?.asString ?: return
                val jobNumber = dataContent.get("job_number")?.asLong
                val status = dataContent.get("status")?.asString ?: return
                val workflowId = dataContent.get("workflow_id")?.asString ?: return
                val projectSlug = dataContent.get("project_slug")?.asString ?: return

                logger.logWebSocketEvent("job.completed", "job:$jobId")

                GlobalScope.launch {
                    _events.emit(
                        CircleCIWebSocketEvent.JobCompleted(
                            jobId,
                            jobNumber,
                            status,
                            workflowId,
                            projectSlug,
                        ),
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to handle job.completed event", e)
            }
        }
    }

    companion object {
        fun getInstance(): CircleCIWebSocketService {
            return com.intellij.openapi.components.service()
        }
    }
}
