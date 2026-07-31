package org.wyrdsekai.app.network

import android.util.Log
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Per-host TLS TrustManager for the KMP Android client: try system trust
 * first (Let's Encrypt / public CA path), fall back to a household-CA pin
 * for this hostname.
 *
 * Mirrors clients/rn/.../HouseholdTrustManager.kt — they share the same
 * spec + seed_relay_trust.sh fixture.
 *
 * Extends [X509ExtendedTrustManager] so OkHttp's SSL handshake routes
 * through the Socket/SSLEngine overloads, which expose the peer hostname.
 * Without that we cannot apply the per-host pin (the bare
 * [X509TrustManager] interface only sees the cert chain, not where it
 * came from).
 */
/**
 * Thrown when a per-host pin EXISTS but the presented chain doesn't validate
 * against it. Distinct from a plain `CertificateException` so the
 * recovery UX can offer a re-TOFU prompt (rotation) instead of treating
 * this like a brand-new connect (which would log the user into TOFU as if
 * they'd never paired). Caller flow:
 *   1. catch PinMismatchException
 *   2. ask the user via UI: "trust new cert? fingerprint XXXX"
 *   3. on accept: HouseholdTrustStore.clear(host) + retry the request →
 *      the retry hits the no-pin path → TOFU flow → re-pins
 * (cert rotation).
 */
class PinMismatchException(
  val host: String,
  val newFingerprint: String,
  val pinnedFingerprint: String?,
  cause: Throwable? = null,
) : CertificateException("Pin mismatch for $host (new=$newFingerprint pinned=$pinnedFingerprint)", cause)

class HouseholdTrustManager(
  private val systemTm: X509TrustManager,
) : X509ExtendedTrustManager() {

  private val tag = "HouseholdTrustMgr"

  // ── client-cert path: pure pass-through (we never present client certs). ──

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
    systemTm.checkClientTrusted(chain, authType)

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
    systemTm.checkClientTrusted(chain, authType)

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
    systemTm.checkClientTrusted(chain, authType)

  // ── server-cert path: system → per-host pin. ──

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
    // Hostname unavailable in this overload: only system trust applies.
    systemTm.checkServerTrusted(chain, authType)
  }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    socket: Socket?,
  ) {
    val host = (socket as? SSLSocket)?.let { extractHost(it) }
    verify(chain, authType, host)
  }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    engine: SSLEngine?,
  ) {
    val host = engine?.peerHost
    verify(chain, authType, host)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers

  private fun verify(chain: Array<X509Certificate>, authType: String, host: String?) {
    try {
      systemTm.checkServerTrusted(chain, authType)
      return
    } catch (systemReject: CertificateException) {
      if (host.isNullOrBlank()) throw systemReject
    }

    val pinnedCa = HouseholdTrustStore.get(host!!)
      ?: throw CertificateException(
        "No pinned cert for host '$host' and system trust rejected the chain",
      )

    // Build a one-shot TrustManager seeded with just the pinned CA, run the
    // chain through it. This validates path construction + signatures end
    // to end — equivalent to bundling that CA at build time but per-host.
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    ks.setCertificateEntry("pinned-$host", pinnedCa)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
      init(ks)
    }
    val pinnedTm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    try {
      pinnedTm.checkServerTrusted(chain, authType)
      Log.i(tag, "TLS chain for $host validated against pinned household CA")
    } catch (e: CertificateException) {
      // Pinned CA EXISTS but doesn't match the chain root. This is the
      // rotation case (operator ran `wyrd relay rotate-cert --ca`).
      // Surface it as a distinct exception so the UI can prompt for
      // re-TOFU instead of treating it as an opaque TLS failure.
      val newFp = chain.firstOrNull()?.let { sha256Fingerprint(it.encoded) }
      val pinnedFp = sha256Fingerprint(pinnedCa.encoded)
      Log.w(tag, "Pinned CA for $host did NOT match chain root — pin mismatch (new=$newFp pinned=$pinnedFp)")
      TrustEventBus.publish(TrustEvent.PinMismatch(host, newFp ?: "", pinnedFp))
      throw PinMismatchException(host, newFp ?: "", pinnedFp, e)
    }
  }

  private fun sha256Fingerprint(bytes: ByteArray): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val d = md.digest(bytes)
    val sb = StringBuilder(d.size * 3)
    for ((i, b) in d.withIndex()) {
      if (i > 0) sb.append(':')
      sb.append("%02X".format(b))
    }
    return sb.toString()
  }

  private fun extractHost(socket: SSLSocket): String? =
    try {
      socket.handshakeSession?.peerHost ?: socket.inetAddress?.hostName
    } catch (e: Throwable) {
      socket.inetAddress?.hostName
    }

  companion object {
    /** Resolve the JDK default system X509TrustManager (CA bundle + user roots). */
    fun resolveSystemTrustManager(): X509TrustManager {
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(null as KeyStore?)
      return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
  }
}
