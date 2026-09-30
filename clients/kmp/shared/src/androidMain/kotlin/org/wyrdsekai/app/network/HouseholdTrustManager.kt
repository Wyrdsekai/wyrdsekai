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
 * A pin EXISTS for the host but the presented chain does not validate against
 * it. The connection is refused and the person is told to pair again
 * ([SecurityNotices]); the app never offers to trust the new certificate
 * (D6, ).
 */
class PinMismatchException(
  val host: String,
  val newFingerprint: String,
  val pinnedFingerprint: String?,
  cause: Throwable? = null,
) : CertificateException("Pin mismatch for $host (new=$newFingerprint pinned=$pinnedFingerprint)", cause)

/**
 * Per-address TLS TrustManager for the KMP Android client.
 * An address (host and port) with a pin (from an invite's fingerprint: the
 * relay's `fp`/`ca_fp`, the home's `home_ca_fp`) is checked against that pin
 * ONLY: its chain must validate to the pinned certificate. An address without a
 * pin gets system trust (a relay on a public CA). There is no trust on first
 * use. The port is part of the address, so a relay and a home on one machine
 * are each held to their own CA.
 *
 * Extends [X509ExtendedTrustManager] so OkHttp's and jnats's SSL handshakes
 * route through the Socket/SSLEngine overloads, which expose the peer host
 * and port. Without that we cannot apply the pin (the bare
 * [X509TrustManager] interface only sees the cert chain, not where it
 * came from).
 */
class HouseholdTrustManager(
  private val systemTm: X509TrustManager,
  private val pins: (host: String, port: Int) -> X509Certificate? = HouseholdTrustStore::get,
) : X509ExtendedTrustManager() {

  private val tag = "HouseholdTrustMgr"

  // ── client-cert path: pure pass-through (we never present client certs). ──

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
    systemTm.checkClientTrusted(chain, authType)

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
    systemTm.checkClientTrusted(chain, authType)

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
    systemTm.checkClientTrusted(chain, authType)

  // ── server-cert path: the host's pin, or system trust when it has none. ──

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
    // Hostname unavailable in this overload: only system trust applies.
    systemTm.checkServerTrusted(chain, authType)
  }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    socket: Socket?,
  ) {
    val ssl = socket as? SSLSocket
    verify(chain, authType, ssl?.let { extractHost(it) }, ssl?.let { extractPort(it) } ?: -1)
  }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    engine: SSLEngine?,
  ) {
    verify(chain, authType, engine?.peerHost, engine?.peerPort ?: -1)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers

  internal fun verify(chain: Array<X509Certificate>, authType: String, host: String?, port: Int) {
    val pinned = host?.takeIf { it.isNotBlank() }?.let { pins(it, port) }
    if (pinned == null) {
      systemTm.checkServerTrusted(chain, authType)
      return
    }

    // Build a one-shot TrustManager seeded with just the pinned certificate and
    // run the chain through it: path construction and signatures end to end,
    // so a chain that merely CONTAINS the pinned CA next to someone else's leaf
    // does not pass.
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    ks.setCertificateEntry("pinned-$host", pinned)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
      init(ks)
    }
    val pinnedTm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    try {
      pinnedTm.checkServerTrusted(chain, authType)
    } catch (e: CertificateException) {
      val newFp = chain.firstOrNull()?.let { sha256Fingerprint(it.encoded) }
      val pinnedFp = sha256Fingerprint(pinned.encoded)
      Log.w(tag, "Pinned certificate for $host:$port did NOT validate the chain — refusing (new=$newFp pinned=$pinnedFp)")
      SecurityNotices.publish(SecurityNotice.PinMismatch(host!!))
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

  private fun extractPort(socket: SSLSocket): Int =
    try {
      socket.handshakeSession?.peerPort?.takeIf { it > 0 } ?: socket.port
    } catch (e: Throwable) {
      socket.port
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
