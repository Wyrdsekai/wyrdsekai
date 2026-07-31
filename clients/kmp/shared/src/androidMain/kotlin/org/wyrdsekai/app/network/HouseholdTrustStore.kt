package org.wyrdsekai.app.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton SharedPreferences-backed map of `host → X509Certificate` used for
 * TOFU household-CA pinning. Mirrors the RN HouseholdTrustStore.kt so the
 * `seed_relay_trust.sh` test fixture works for both clients.
 *
 * The store is read once at app start (`init`) and held in memory; later
 * `put`/`remove` writes both to memory and disk so a fresh TLS handshake
 * after a TOFU prompt does not need an Application restart.
 *
 * Prefs file: `wyrd_household_trust.xml` (matches RN naming).
 */
object HouseholdTrustStore {
  private const val TAG = "HouseholdTrustStore"
  private const val PREFS_NAME = "wyrd_household_trust"

  private val cache = ConcurrentHashMap<String, X509Certificate>()
  @Volatile private var initialized = false

  fun init(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      var loaded = 0
      for ((host, raw) in prefs.all) {
        val pem = raw as? String ?: continue
        val cert = parsePem(pem)
        if (cert != null) {
          cache[host] = cert
          loaded++
        }
      }
      Log.i(TAG, "Loaded $loaded pinned certs from SharedPreferences")
      initialized = true
    }
  }

  fun get(host: String): X509Certificate? = cache[host]

  fun put(context: Context, host: String, pem: String) {
    val cert = parsePem(pem) ?: run {
      Log.w(TAG, "Refusing to pin invalid PEM for $host")
      return
    }
    cache[host] = cert
    val prefs: SharedPreferences = context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(host, pem).apply()
    Log.i(TAG, "Pinned cert for $host")
  }

  fun remove(context: Context, host: String) {
    cache.remove(host)
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().remove(host).apply()
  }

  fun listHosts(): List<String> = cache.keys.toList()

  private fun parsePem(pem: String): X509Certificate? = try {
    val cf = CertificateFactory.getInstance("X.509")
    cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as? X509Certificate
  } catch (e: Throwable) {
    Log.w(TAG, "PEM parse failed: ${e.message}")
    null
  }
}
