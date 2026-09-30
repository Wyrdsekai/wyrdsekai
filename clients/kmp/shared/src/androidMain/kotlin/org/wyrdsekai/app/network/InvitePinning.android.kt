package org.wyrdsekai.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wyrdsekai.app.platform.PlatformContext
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

private const val TAG = "InvitePinning"
private const val HANDSHAKE_TIMEOUT_MS = 8_000

/**
 * Fetch the chain the relay (or the home) serves at [host]:[port]
 * (trust-all, READ-ONLY — nothing is sent after the handshake), match it
 * against the invite's fingerprints, pin the match into [HouseholdTrustStore]
 * for that address. Prefers the LAST matching certificate: the relay serves
 * chain.crt = leaf + household CA, so when both match the CA wins and the pin
 * survives leaf rotation (mirrors RN HouseholdTrust.trustFromInviteFingerprints).
 *
 * Without an application context (a JVM test run) the pin is held for this
 * process only.
 */
actual suspend fun pinRelayFromInviteFingerprints(
    host: String,
    port: Int,
    fingerprints: List<String>,
): Boolean = withContext(Dispatchers.IO) {
    val context = PlatformContext.app
    val wanted = fingerprints.map { normalizeFp(it) }.filter { it.isNotEmpty() }.toSet()
    if (wanted.isEmpty()) return@withContext false

    val chain = try {
        fetchServedChain(host, port)
    } catch (e: Exception) {
        Log.w(TAG, "Chain fetch from $host:$port failed: ${e.message}")
        return@withContext false
    }

    val match = chain.lastOrNull { normalizeFp(sha256Hex(it)) in wanted }
    if (match == null) {
        Log.w(TAG, "No served certificate matched the invite fingerprints for $host:$port")
        return@withContext false
    }
    context?.let { HouseholdTrustStore.init(it) }
    HouseholdTrustStore.put(context, host, port, match)
    Log.i(TAG, "Pinned $host:$port from invite fingerprint (subject=${match.subjectX500Principal.name})")
    true
}

private fun fetchServedChain(host: String, port: Int): List<X509Certificate> {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(trustAll), SecureRandom())
    val socket = sslContext.socketFactory.createSocket() as SSLSocket
    socket.use { s ->
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        s.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
        s.startHandshake()
        return s.session.peerCertificates.filterIsInstance<X509Certificate>()
    }
}

private fun sha256Hex(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString("") { "%02x".format(it) }

/** Colon-hex or bare hex, any case → bare lowercase hex. */
private fun normalizeFp(fp: String): String =
    fp.replace(":", "").trim().lowercase()

