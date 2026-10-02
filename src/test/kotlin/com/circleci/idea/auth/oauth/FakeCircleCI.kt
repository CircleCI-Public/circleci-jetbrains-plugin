package com.circleci.idea.auth.oauth

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A CircleCI authentication service on a local port: its OAuth metadata
 * (when [advertise] is set) and token endpoint, recording what's posted.
 */
class FakeCircleCI : AutoCloseable {
    /** A form posted to the token endpoint. */
    val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()

    var advertise = true

    /** Serve a web page where the metadata should be, as circleci.com does (it's the marketing site). */
    var metadataIsAWebPage = false
    var tokenStatus = 200
    var tokenResponse = """{"access_token": "oauth-token", "token_type": "Bearer", "expires_in": 7776000}"""

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** Where the metadata says to authorize and exchange codes: not the host's own /oauth paths. */
    val advertisedAuthorize: String get() = "$url/app/oauth/authorize"
    val advertisedToken: String get() = "$url/app/oauth/token"

    init {
        server.createContext("/.well-known/oauth-authorization-server") { exchange ->
            if (metadataIsAWebPage) {
                exchange.respond(200, "<!DOCTYPE html><html><body>CircleCI</body></html>")
            } else if (advertise) {
                exchange.respond(
                    200,
                    """{"authorization_endpoint": "$advertisedAuthorize", "token_endpoint": "$advertisedToken"}""",
                )
            } else {
                exchange.respond(404, "{}")
            }
        }
        server.createContext("/app/oauth/token") { exchange ->
            tokenRequests += form(exchange.requestBody.readBytes().decodeToString())
            exchange.respond(tokenStatus, tokenResponse)
        }
        server.start()
    }

    override fun close() = server.stop(0)

    private fun com.sun.net.httpserver.HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    companion object {
        /** Decode a query string or form body. */
        fun form(encoded: String): Map<String, String> =
            encoded.split('&').filter { it.isNotEmpty() }.associate {
                val (name, value) = it.split('=', limit = 2) + ""
                URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
    }
}
