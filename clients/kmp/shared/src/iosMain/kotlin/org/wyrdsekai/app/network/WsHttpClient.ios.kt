@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.wyrdsekai.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.readBytes
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustGetCertificateCount
import platform.Security.SecTrustRef

/**
 * iOS actual: Darwin engine with a TLS challenge handler that accepts the
 * household relay's served chain when any certificate in it matches the
 * DER pinned for that host (seeded by [pinRelayFromInviteFingerprints]).
 * Everything else falls through to system default trust.
 */
actual fun createWsHttpClient(): HttpClient = HttpClient(Darwin) {
    engine {
        handleChallenge { _, _, challenge, completionHandler ->
            val space = challenge.protectionSpace
            val trust = space.serverTrust
            val pinned =
                if (space.authenticationMethod == NSURLAuthenticationMethodServerTrust)
                    HouseholdTrustStore.get(space.host)
                else null
            if (trust != null && pinned != null &&
                extractServedChain(trust).any { it.contentEquals(pinned) }
            ) {
                completionHandler(
                    NSURLSessionAuthChallengeUseCredential,
                    NSURLCredential.credentialForTrust(trust),
                )
            } else {
                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            }
        }
    }
    install(WebSockets)
}

/** DER bytes of every certificate the peer served, leaf first. */
internal fun extractServedChain(trust: SecTrustRef): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    val count = SecTrustGetCertificateCount(trust)
    for (i in 0 until count) {
        val cert = SecTrustGetCertificateAtIndex(trust, i) ?: continue
        val dataRef = SecCertificateCopyData(cert) ?: continue
        val len = CFDataGetLength(dataRef).toInt()
        val ptr = CFDataGetBytePtr(dataRef)
        if (ptr != null && len > 0) out.add(ptr.readBytes(len))
        CFRelease(dataRef)
    }
    return out
}
