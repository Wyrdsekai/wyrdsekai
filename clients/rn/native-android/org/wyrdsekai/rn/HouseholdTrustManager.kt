package org.wyrdsekai.rn

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
 * Per-host TLS TrustManager: try system trust first (Let's Encrypt / public CA
 * path), fall back to the household-CA pin for this hostname.
 *
 * Extends X509ExtendedTrustManager so OkHttp's SSL handshake routes through the
 * Socket/SSLEngine overloads, which expose the peer hostname. Without that we
 * can't apply the per-host pin (the bare X509TrustManager interface only sees
 * the cert chain, not where it came from).
 */
/**
 * Thrown when a per-host pin EXISTS but the chain doesn't match — i.e.
 * cert rotation. Caller catches this to offer a re-TOFU prompt instead of
 * treating the failure like a generic TLS error.
 */
class PinMismatchException(
  val host: String,
  val newFingerprint: String,
  val pinnedFingerprint: String?,
  cause: Throwable? = null,
) : CertificateException("Pin mismatch for $host (new=$newFingerprint pinned=$pinnedFingerprint)", cause)

class HouseholdTrustManager(
  private val systemTm: X509TrustManager
) : X509ExtendedTrustManager() {

  private val tag = "HouseholdTrustMgr"

  // ── client-cert path: pure pass-through to system (we never present client certs) ──

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
    systemTm.checkClientTrusted(chain, authType)

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
    systemTm.checkClientTrusted(chain, authType)

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
    systemTm.checkClientTrusted(chain, authType)

  // ── server-cert path: system → per-host pin ──

  /** Fallback when caller passes no Socket/SSLEngine — no hostname available; only system trust. */
  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
    systemTm.checkServerTrusted(chain, authType)
  }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    socket: Socket?
  ) {
    val host = (socket as? SSLSocket)?.let { extractHost(it) }
    verify(chain, authType, host)
  }

  override fun checkServerTrusted(
    chain: Array<X509Certificate>,
    authType: String,
    engine: SSLEngine?
  ) {
    val host = engine?.peerHost
    verify(chain, authType, host)
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers

  // ── core ──

  private fun verify(chain: Array<X509Certificate>, authType: String, host: String?) {
    // 1. System path — Let's Encrypt / public CAs / OS user-installed.
    try {
      systemTm.checkServerTrusted(chain, authType)
      return
    } catch (systemReject: CertificateException) {
      // Fall through to per-host pin lookup.
      if (host.isNullOrBlank()) throw systemReject
    }

    // 2. Per-host pin — the household CA persisted for THIS host key.
    val exact = HouseholdTrustStore.get(host!!)
    if (exact != null && validatesAgainst(chain, authType, exact)) {
      Log.i(tag, "TLS chain for $host validated against pinned household CA")
      return
    }

    // 3. Host-key fallback (2026-07-24). The pin can be stored under a DIFFERENT
    // key than the one we resolved here: a relay reached by DNS NAME is pinned
    // under that name, but during the trust check SSLSession.peerHost is often
    // null so extractHost() falls back to inetAddress.hostName — which yields the
    // bare IP string for a LAN IP (matches an IP-keyed pin) but the reverse-DNS
    // of a public IP for a DNS relay (never the pinned name). That silently broke
    // login to any DNS-named relay (e.g. relay.example.com → 217.x reverse-DNS)
    // while IP relays worked. System trust was already tried above, so only a
    // self-signed household chain reaches here; a chain signed by ANY CA the user
    // pinned from an invite is trust they already granted. Try them all.
    for ((pinHost, ca) in HouseholdTrustStore.all()) {
      if (ca === exact) continue
      if (validatesAgainst(chain, authType, ca)) {
        Log.i(tag, "TLS chain for '$host' validated against pin under '$pinHost' (host-key fallback)")
        return
      }
    }

    // Nothing matched. If a pin existed for this exact host, it's a genuine
    // mismatch (rotation) → surface for re-TOFU; otherwise no pin covers it.
    if (exact != null) {
      val newFp = chain.firstOrNull()?.let { sha256Fingerprint(it.encoded) } ?: ""
      val pinnedFp = sha256Fingerprint(exact.encoded)
      Log.w(tag, "Pinned CA for $host did NOT match chain root — pin mismatch (new=$newFp pinned=$pinnedFp)")
      TrustEventEmitter.emitPinMismatch(host, newFp, pinnedFp)
      throw PinMismatchException(host, newFp, pinnedFp, null)
    }
    throw CertificateException(
      "No pinned household CA matched the chain for host '$host' and system trust rejected it"
    )
  }

  /** Does [chain] validate against a one-shot trust store seeded with only [ca]? */
  private fun validatesAgainst(chain: Array<X509Certificate>, authType: String, ca: X509Certificate): Boolean =
    try {
      val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
      ks.setCertificateEntry("pinned", ca)
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
      (tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager)
        .checkServerTrusted(chain, authType)
      true
    } catch (_: CertificateException) {
      false
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

  /** Best-effort: SSLSocket has no direct peerHost; SSLSession.peerHost is set during handshake. */
  private fun extractHost(socket: SSLSocket): String? =
    try {
      socket.handshakeSession?.peerHost ?: socket.inetAddress?.hostName
    } catch (e: Throwable) {
      socket.inetAddress?.hostName
    }
}
