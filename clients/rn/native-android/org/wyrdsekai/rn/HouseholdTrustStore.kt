package org.wyrdsekai.rn

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process cache of per-host pinned CAs, backed by SharedPreferences so pins
 * survive app restarts. Companion to — the OkHttp
 * TrustManager reads this on every TLS check, and the JS-side TOFU prompt
 * writes to this via HouseholdTrustModule.
 *
 * Singleton because the OkHttpClientFactory and the native module both touch
 * it from different threads; ConcurrentHashMap keeps reads lock-free.
 */
object HouseholdTrustStore {
  private const val TAG = "HouseholdTrustStore"
  private const val PREFS = "wyrd_household_trust"

  private val cache = ConcurrentHashMap<String, X509Certificate>()
  @Volatile private var initialized = false

  /**
   * Load all stored pins into memory. Call from MainApplication.onCreate
   * before the OkHttp factory creates its first client. Idempotent.
   */
  fun init(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      var loaded = 0
      for ((host, value) in prefs.all) {
        val pem = value as? String ?: continue
        try {
          cache[host] = parsePem(pem)
          loaded++
        } catch (e: Exception) {
          Log.w(TAG, "Failed to parse stored cert for $host: ${e.message}")
        }
      }
      initialized = true
      Log.i(TAG, "Loaded $loaded pinned certs from SharedPreferences")
    }
  }

  /**
   * Persist a new pin. Called by HouseholdTrustModule after the JS-side
   * user-confirmation prompt resolves accept.
   */
  fun put(context: Context, host: String, pem: String) {
    val cert = parsePem(pem)
    cache[host] = cert
    context.applicationContext
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(host, pem)
      .apply()
    Log.i(TAG, "Pinned cert for $host (subject=${cert.subjectDN})")
  }

  fun get(host: String): X509Certificate? = cache[host]

  fun remove(context: Context, host: String) {
    cache.remove(host)
    context.applicationContext
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .remove(host)
      .apply()
  }

  fun all(): Map<String, X509Certificate> = cache.toMap()

  private fun parsePem(pem: String): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    val cleaned = pem.trim().toByteArray()
    return factory.generateCertificate(ByteArrayInputStream(cleaned)) as X509Certificate
  }
}
