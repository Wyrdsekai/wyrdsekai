@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.wyrdsekai.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.wyrdsekai.app.platform.sha256
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.serverTrust

/**
 * iOS invite-fingerprint pinning (#733, ).
 *
 * Mirrors InvitePinning.android.kt: fetch the chain the relay serves
 * (read-only — the probe request is cancelled at the TLS challenge, nothing
 * is sent past the handshake), match it against the invite's fingerprints,
 * and pin the LAST matching certificate so the household CA wins over the
 * leaf and the pin survives leaf rotation.
 */
actual suspend fun pinRelayFromInviteFingerprints(
    host: String,
    port: Int,
    fingerprints: List<String>,
): Boolean {
    val wanted = fingerprints.map { normalizeFp(it) }.filter { it.isNotEmpty() }.toSet()
    if (wanted.isEmpty()) return false

    val chainDeferred = CompletableDeferred<List<ByteArray>>()
    val probe = HttpClient(Darwin) {
        engine {
            handleChallenge { _, _, challenge, completionHandler ->
                challenge.protectionSpace.serverTrust?.let { trust ->
                    if (!chainDeferred.isCompleted) {
                        chainDeferred.complete(extractServedChain(trust))
                    }
                }
                // Capture-only: never proceed past the handshake.
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }
    try {
        withTimeoutOrNull(8_000L) {
            runCatching { probe.get("https://$host:$port/") }
        }
    } finally {
        probe.close()
    }

    val chain = withTimeoutOrNull(8_000L) { chainDeferred.await() }
    if (chain.isNullOrEmpty()) {
        println("InvitePinning: chain fetch from $host:$port failed")
        return false
    }
    val match = chain.lastOrNull { normalizeFp(hex(sha256(it))) in wanted }
    if (match == null) {
        println("InvitePinning: no served certificate matched the invite fingerprints for $host:$port")
        return false
    }
    HouseholdTrustStore.put(host, match)
    println("InvitePinning: Pinned $host from invite fingerprint (iOS)")
    return true
}

private fun normalizeFp(fp: String): String =
    fp.lowercase().filter { it in "0123456789abcdef" }

private fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
