package org.wyrdsekai.rn

import android.util.Log
import com.facebook.react.modules.network.OkHttpClientFactory
import com.facebook.react.modules.network.OkHttpClientProvider
import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * RN's pluggable OkHttp factory — overrides only the SSL chain, inherits all
 * RN defaults (timeouts, connection pool, CookieJar, gzip interceptors) from
 * `OkHttpClientProvider.createClientBuilder()`. Installed in
 * MainApplication.onCreate before the first ReactHost access.
 *
 * The per-host pin lookup lives in HouseholdTrustManager.
 */
class WyrdOkHttpClientFactory : OkHttpClientFactory {
  private val tag = "WyrdOkHttpFactory"

  override fun createNewNetworkModuleClient(): OkHttpClient {
    val builder = OkHttpClientProvider.createClientBuilder()
    try {
      val systemTm = resolveSystemTrustManager()
      val customTm = HouseholdTrustManager(systemTm)
      val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(customTm), null)
      }
      builder.sslSocketFactory(sslContext.socketFactory, customTm)
      Log.i(tag, "Installed per-host TrustManager (TOFU pinning via HouseholdTrustStore)")
    } catch (e: Throwable) {
      // Falling back to RN defaults means HTTPS will only work for system-trusted
      // hosts. The TOFU path is broken but the app boots. Log loudly so this is
      // visible in production telemetry.
      Log.e(tag, "Failed to install custom TrustManager — falling back to RN default", e)
    }
    return builder.build()
  }

  /**
   * The default Android system TrustManager — the one loaded with the OS trust
   * store (Let's Encrypt + DigiCert + … plus any user-installed CAs trusted by
   * network_security_config.xml). We chain through this first; only fall back
   * to per-host pins on rejection.
   */
  private fun resolveSystemTrustManager(): X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    return tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
      ?: error("No default X509TrustManager available — JVM TLS broken")
  }
}
