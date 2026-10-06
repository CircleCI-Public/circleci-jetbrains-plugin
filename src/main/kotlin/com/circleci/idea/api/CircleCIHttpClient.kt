package com.circleci.idea.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ssl.CertificateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.net.http.HttpClient
import java.time.Duration

/**
 * The plugin's one HTTP client, through the IDE's proxy and certificates,
 * and shut down as the plugin unloads.
 *
 * It's the JDK's client, running on the IDE's IO threads. A library's
 * client keeps threads of its own classes (OkHttp's okio watchdog, say)
 * alive for a minute after its last request, and while they run the
 * plugin's classloader can't go, so the plugin can't be updated without a
 * restart.
 */
@Service(Service.Level.APP)
class CircleCIHttpClient : Disposable {
    val client: HttpClient =
        HttpClient.newBuilder()
            .executor(Dispatchers.IO.asExecutor())
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(JdkProxyProvider.getInstance().proxySelector)
            .authenticator(JdkProxyProvider.getInstance().authenticator)
            .sslContext(CertificateManager.getInstance().sslContext)
            .build()

    // Ends the client's selector thread and closes its connections.
    override fun dispose() = client.shutdownNow()

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 30L

        fun getInstance(): CircleCIHttpClient = service()
    }
}
