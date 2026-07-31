package org.wyrdsekai.rn

import android.util.Base64
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * JS-facing surface for the household-CA trust store. Companion to
 * clients/rn/src/server/HouseholdTrust.ts.
 *
 * Called after the user accepts a TOFU fingerprint prompt — JS hands us the
 * PEM, we parse and persist it. The OkHttp TrustManager reads from the same
 * HouseholdTrustStore singleton on every TLS check, so the next HTTPS request
 * to that host picks up the new pin without an app restart.
 *
 * Registered via HouseholdTrustPackage and added manually to PackageList in
 * MainApplication (not autolinked — too small to justify codegen).
 */
class HouseholdTrustModule(reactContext: ReactApplicationContext)
  : ReactContextBaseJavaModule(reactContext) {

  init {
    // Hand the React context to the trust-event emitter so the native
    // TLS check can fire a JS event when it detects a pin mismatch. The
    // module is constructed exactly once per RN bridge instance — same
    // lifecycle as the bridge, so no risk of stale context.
    TrustEventEmitter.init(reactContext)
  }

  override fun getName() = "HouseholdTrust"

  /** Persist a CA PEM for a host. Subsequent HTTPS requests to that host trust it. */
  @ReactMethod
  fun addTrustedCert(host: String, pem: String, promise: Promise) {
    try {
      HouseholdTrustStore.put(reactApplicationContext, host, pem)
      promise.resolve(true)
    } catch (e: Exception) {
      promise.reject("ADD_TRUSTED_CERT_FAILED", e.message, e)
    }
  }

  /** Forget the pin for a host. Use for revoke/rotation. */
  @ReactMethod
  fun removeTrustedCert(host: String, promise: Promise) {
    try {
      HouseholdTrustStore.remove(reactApplicationContext, host)
      promise.resolve(true)
    } catch (e: Exception) {
      promise.reject("REMOVE_TRUSTED_CERT_FAILED", e.message, e)
    }
  }

  /**
   * Retrieve the TLS certificate chain a server presents, WITHOUT
   * validating it. the wyrdphone:// invite carries
   * the household CA's SHA-256 fingerprint (ca_fp); the JS layer matches
   * the chain against it and pins the matching cert. The trust decision is
   * the fingerprint comparison, never this fetch — nothing is sent over
   * the connection beyond the handshake.
   *
   * Resolves to [{pem, fingerprint}] in chain order (leaf first; the
   * relay's Caddy serves leaf + household CA). Fingerprints are SHA-256
   * over DER as colon-separated uppercase hex, the relay invite format.
   */
  @ReactMethod
  fun fetchServerCertificates(host: String, port: Int, promise: Promise) {
    thread(name = "wyrd-cert-probe") {
      try {
        val trustAll = object : X509TrustManager {
          override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
          override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
          override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustAll), null)
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        socket.use { s ->
          s.soTimeout = 10_000
          s.connect(java.net.InetSocketAddress(host, port), 10_000)
          s.startHandshake()
          val arr = WritableNativeArray()
          for (cert in s.session.peerCertificates) {
            if (cert !is X509Certificate) continue
            val entry = WritableNativeMap()
            entry.putString("pem", toPem(cert))
            entry.putString("fingerprint", sha256ColonHex(cert.encoded))
            arr.pushMap(entry)
          }
          promise.resolve(arr)
        }
      } catch (e: Exception) {
        promise.reject("FETCH_SERVER_CERTS_FAILED", e.message, e)
      }
    }
  }

  private fun toPem(cert: X509Certificate): String {
    val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
    val body = b64.chunked(64).joinToString("\n")
    return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
  }

  private fun sha256ColonHex(der: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-256").digest(der)
    val sb = StringBuilder(d.size * 3)
    for ((i, b) in d.withIndex()) {
      if (i > 0) sb.append(':')
      sb.append("%02X".format(b))
    }
    return sb.toString()
  }

  /** Inspector for the trust UI — returns a list of {host, subject, validUntil}. */
  @ReactMethod
  fun listTrustedHosts(promise: Promise) {
    try {
      val arr = WritableNativeArray()
      for ((host, cert) in HouseholdTrustStore.all()) {
        val entry = WritableNativeMap()
        entry.putString("host", host)
        entry.putString("subject", cert.subjectDN.name)
        entry.putDouble("validUntil", cert.notAfter.time.toDouble())
        arr.pushMap(entry)
      }
      promise.resolve(arr)
    } catch (e: Exception) {
      promise.reject("LIST_TRUSTED_FAILED", e.message, e)
    }
  }
}
