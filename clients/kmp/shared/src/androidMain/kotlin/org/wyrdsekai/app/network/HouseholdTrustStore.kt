package org.wyrdsekai.app.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * `host:port → pinned certificate` for the relay and the home (invite pins,
 * never trust on first use). Kept in EncryptedSharedPreferences under its own
 * Android Keystore key (D6, ); held in memory after
 * [init].
 *
 * A pin belongs to one address, host AND port: a relay and a home on the same
 * machine (a relay container next to the home, or one address for both) each
 * keep their own CA, and pinning one never replaces the other.
 *
 * Pins made before the port was part of the key are keyed by the bare host.
 * They still answer for a port of that host that has no pin of its own
 * ([get]), until the node pins that address again from the saved invite
 * fingerprints ([getExact] is what decides that).
 *
 * Pins made before 0.5.0 sat in the plain SharedPreferences file
 * `wyrd_household_trust` (still what the e2e `seed_relay_trust.sh` fixture
 * writes, keyed by host). [init] moves them into the encrypted store once and
 * deletes the plain file.
 *
 * Without an Android context (a JVM test run) pins are held in memory only.
 */
object HouseholdTrustStore {
  private const val TAG = "HouseholdTrustStore"
  private const val LEGACY_PREFS_NAME = "wyrd_household_trust"
  private const val PREFS_NAME = "wyrd_household_trust_enc"
  private const val KEY_ALIAS = "wyrd_household_trust_key"

  private val cache = ConcurrentHashMap<String, X509Certificate>()
  @Volatile private var initialized = false
  @Volatile private var prefs: SharedPreferences? = null

  /** The key of one address: lowercase host (IPv6 in brackets) and port. */
  fun keyOf(host: String, port: Int): String = "${hostKey(host)}:$port"

  private fun hostKey(host: String): String {
    val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
    return if (h.contains(':')) "[$h]" else h
  }

  fun init(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      val app = context.applicationContext
      val store = securePrefs(app)
      prefs = store
      val moved = migrateLegacy(app, store)
      var loaded = 0
      for ((host, raw) in store.all) {
        val cert = (raw as? String)?.let { parsePem(it) } ?: continue
        cache[host] = cert
        loaded++
      }
      Log.i(TAG, "Loaded $loaded pinned certs (moved $moved from the old plain store)")
      initialized = true
    }
  }

  /**
   * The pin that decides trust for [host]:[port]: that address's own, else a
   * pin from before ports were part of the key (bare host). Null → no pin
   * (system trust only).
   */
  fun get(host: String, port: Int): X509Certificate? =
    getExact(host, port) ?: cache[hostKey(host)] ?: cache[host]

  /** Only a pin made for exactly [host]:[port]. */
  fun getExact(host: String, port: Int): X509Certificate? =
    if (port in 1..65535) cache[keyOf(host, port)] else null

  /**
   * Pins [cert] for [host]:[port]. With a [context] the pin is kept across
   * starts; without one (a JVM test run) for this process only.
   */
  fun put(context: Context?, host: String, port: Int, cert: X509Certificate) {
    val key = keyOf(host, port)
    cache[key] = cert
    if (context != null) store(context).edit().putString(key, toPem(cert)).apply()
    Log.i(TAG, "Pinned cert for $key")
  }

  fun remove(context: Context?, host: String, port: Int) {
    val key = keyOf(host, port)
    cache.remove(key)
    if (context != null) store(context).edit().remove(key).apply()
  }

  fun listKeys(): List<String> = cache.keys.toList()

  private fun store(context: Context): SharedPreferences {
    init(context)
    return prefs!!
  }

  /** Copies every pin from the plain file into [store], then deletes the plain file. */
  private fun migrateLegacy(app: Context, store: SharedPreferences): Int {
    val legacyFile = File(app.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml")
    if (!legacyFile.exists()) return 0
    val legacy = app.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    val edit = store.edit()
    var n = 0
    for ((host, raw) in legacy.all) {
      val pem = raw as? String ?: continue
      if (parsePem(pem) == null) continue
      edit.putString(host, pem)
      n++
    }
    edit.commit()
    legacy.edit().clear().commit()
    try { app.deleteSharedPreferences(LEGACY_PREFS_NAME) } catch (_: Throwable) {}
    legacyFile.delete()
    return n
  }

  private fun securePrefs(app: Context): SharedPreferences = try {
    createSecurePrefs(app)
  } catch (e: Throwable) {
    // The Keystore key can no longer read a store left by an earlier install:
    // start an empty one. The phone then asks to be paired again; it never
    // falls back to a plain store.
    Log.w(TAG, "Pin store unreadable (${e.javaClass.simpleName}) — starting a new one", e)
    try { app.deleteSharedPreferences(PREFS_NAME) } catch (_: Throwable) {}
    try { File(app.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml").delete() } catch (_: Throwable) {}
    try {
      val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
    } catch (_: Throwable) {}
    createSecurePrefs(app)
  }

  private fun createSecurePrefs(app: Context): SharedPreferences {
    val key = MasterKey.Builder(app, KEY_ALIAS)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
    return EncryptedSharedPreferences.create(
      app,
      PREFS_NAME,
      key,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  private fun toPem(cert: X509Certificate): String = buildString {
    append("-----BEGIN CERTIFICATE-----\n")
    append(Base64.getEncoder().encodeToString(cert.encoded).chunked(64).joinToString("\n"))
    append("\n-----END CERTIFICATE-----\n")
  }

  private fun parsePem(pem: String): X509Certificate? = try {
    val cf = CertificateFactory.getInstance("X.509")
    cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as? X509Certificate
  } catch (e: Throwable) {
    Log.w(TAG, "PEM parse failed: ${e.message}")
    null
  }
}
