package org.wyrdsekai.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import javax.net.ssl.SSLContext

/**
 * Android actual: wires the OkHttp engine with our per-host
 * [HouseholdTrustManager] so HTTPS calls accept either system-trusted CAs
 * (Let's Encrypt etc.) or the TOFU-pinned household CA.
 *
 * Requires [HouseholdTrustStore.init] to have been called from the
 * Application/Activity entry point before any HTTPS request fires.
 */
actual fun createHouseholdHttpClient(): HttpClient {
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
  }
}
