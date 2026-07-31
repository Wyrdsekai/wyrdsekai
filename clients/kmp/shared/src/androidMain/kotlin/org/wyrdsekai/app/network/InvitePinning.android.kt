package org.wyrdsekai.app.network

import android.util.Base64
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
 * Fetch the chain the relay serves (trust-all, READ-ONLY — nothing is sent
 * after the handshake), match it against the invite's fingerprints, pin the
 * match into [HouseholdTrustStore]. Prefers the LAST matching certificate:
 * the relay serves chain.crt = leaf + household CA, so when both match the
 * CA wins and the pin survives leaf rotation (mirrors RN
 * HouseholdTrust.trustFromInviteFingerprints).
 */
actual suspend fun pinRelayFromInviteFingerprints(
    host: String,
    port: Int,
    fingerprints: List<String>,
): Boolean = withContext(Dispatchers.IO) {
    val context = PlatformContext.app ?: run {
        Log.w(TAG, "No application context — cannot pin")
        return@withContext false
    }
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
    HouseholdTrustStore.init(context)
    HouseholdTrustStore.put(context, host, toPem(match))
    Log.i(TAG, "Pinned $host from invite fingerprint (subject=${match.subjectX500Principal.name})")
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

private fun toPem(cert: X509Certificate): String = buildString {
    append("-----BEGIN CERTIFICATE-----\n")
    append(Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
    append("\n-----END CERTIFICATE-----\n")
}
