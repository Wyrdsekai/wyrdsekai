package org.wyrdsekai.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import javax.net.ssl.SSLContext

/**
 * Android actual: OkHttp engine with [HouseholdTrustManager] (system CAs +
 * TOFU/invite-pinned household certs), mirroring HouseholdHttpClient.
 */
actual fun createWsHttpClient(): HttpClient {
    val systemTm = HouseholdTrustManager.resolveSystemTrustManager()
    val customTm = HouseholdTrustManager(systemTm)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(customTm), null)
    }
    return HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(sslContext.socketFactory, customTm)
            }
        }
        install(WebSockets)
    }
}
