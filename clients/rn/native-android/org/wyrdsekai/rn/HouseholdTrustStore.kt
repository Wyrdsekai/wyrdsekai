package org.wyrdsekai.rn

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process store of per-host pins, in memory only. Companion to
 * the OkHttp TrustManager reads this on every TLS
 * check. Pins arrive from JS (HouseholdTrustModule): certificates matched
 * against an invite's fingerprints, and CA fingerprint pins for the home's
 * network address (`home_ca_fp`, D1). Their lasting
 * copy is the JS side's secure storage (encrypted, key in the Android
 * Keystore-backed store), which refills this store at every start
 * (HouseholdTrust.ts restoreNativePins). Before 0.5.0 pins were also written to
 * plain SharedPreferences; init() erases that file (D6).
 *
 * Singleton because the OkHttpClientFactory and the native module both touch
 * it from different threads; ConcurrentHashMap keeps reads lock-free.
 */
object HouseholdTrustStore {
  private const val TAG = "HouseholdTrustStore"
  /** The SharedPreferences file older builds kept pins in. */
  private const val LEGACY_PREFS = "wyrd_household_trust"

  private val cache = ConcurrentHashMap<String, X509Certificate>()
  /** host -> CA SHA-256 fingerprints, UPPERCASE hex without colons. */
  private val caPins = ConcurrentHashMap<String, Set<String>>()
  @Volatile private var initialized = false

  /**
   * Call from MainApplication.onCreate before the OkHttp factory creates its
   * first client. Erases the plain SharedPreferences pin file older builds
   * wrote; JS refills the pins from secure storage. Idempotent.
   */
  fun init(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      try {
        context.applicationContext.deleteSharedPreferences(LEGACY_PREFS)
      } catch (e: Exception) {
        Log.w(TAG, "Could not erase the old plain pin file: ${e.message}")
      }
      initialized = true
    }
  }

  /** Pin a certificate (PEM) for a host. Called by HouseholdTrustModule. */
  @Suppress("UNUSED_PARAMETER")
  fun put(context: Context, host: String, pem: String) {
    val cert = parsePem(pem)
    cache[host] = cert
    Log.i(TAG, "Pinned cert for $host (subject=${cert.subjectDN})")
  }

  fun get(host: String): X509Certificate? = cache[host]

  /** Pin a host to a CA by the CA certificate's SHA-256 (hex; colons and case ignored). */
  fun putCaFingerprint(host: String, fingerprint: String) {
    val fp = plainHex(fingerprint)
    require(fp.length == 64) { "not a SHA-256 fingerprint" }
    caPins.merge(host, setOf(fp)) { a, b -> a + b }
  }

  fun caFingerprints(host: String): Set<String> = caPins[host] ?: emptySet()

  @Suppress("UNUSED_PARAMETER")
  fun remove(context: Context, host: String) {
    cache.remove(host)
    caPins.remove(host)
  }

  fun all(): Map<String, X509Certificate> = cache.toMap()

  fun plainHex(fingerprint: String): String =
    fingerprint.replace(":", "").replace(Regex("\\s"), "").uppercase()

  private fun parsePem(pem: String): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    val cleaned = pem.trim().toByteArray()
    return factory.generateCertificate(ByteArrayInputStream(cleaned)) as X509Certificate
  }
}
