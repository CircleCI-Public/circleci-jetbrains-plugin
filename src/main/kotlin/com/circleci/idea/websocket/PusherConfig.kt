package com.circleci.idea.websocket

/**
 * Configuration for Pusher WebSocket connection.
 */
data class PusherConfig(
    val key: String,
    val cluster: String? = null,
    val wsEndpoint: String? = null,
) {
    /**
     * Get the WebSocket URL for Pusher.
     */
    fun getWebSocketUrl(): String {
        return wsEndpoint ?: "wss://ws-${cluster ?: "us2"}.pusher.com/app/$key"
    }
}
