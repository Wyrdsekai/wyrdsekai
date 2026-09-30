package org.wyrdsekai.app.network

import android.content.pm.ApplicationInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import javax.net.ssl.SSLContext
import org.wyrdsekai.app.platform.PlatformContext

/**
 * Android actual: wires the OkHttp engine with our per-host
 * [HouseholdTrustManager] so HTTPS calls accept either system-trusted CAs
 * (Let's Encrypt etc.) or, for a host with an invite pin, that pin only; and
 * refuses plain http:// off the device ([RefusePlaintextOffDevice]).
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
    install(RefusePlaintextOffDevice)
  }
}

actual fun devLoopbackAliases(): Set<String> {
  val app = PlatformContext.app ?: return emptySet()
  val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  return if (debuggable) setOf("10.0.2.2") else emptySet()
}
