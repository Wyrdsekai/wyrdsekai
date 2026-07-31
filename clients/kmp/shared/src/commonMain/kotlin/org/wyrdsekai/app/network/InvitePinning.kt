package org.wyrdsekai.app.network

/**
 * pin the relay's TLS identity from invite material.
 *
 * The wyrdphone:// invite carries the relay's `fp` (leaf SHA-256) and
 * `ca_fp` (household-CA SHA-256). The invite IS the trust decision: at
 * paste/scan time we fetch the certificate chain the relay actually
 * serves (read-only handshake, nothing sent), match it against the
 * invite's fingerprints, and pin the matching certificate — preferring
 * the CA so the pin survives leaf rotation. No first-contact TOFU leap,
 * no cleartext /ca.crt bootstrap (single-port relays have none).
 *
 * Mirrors the RN client's HouseholdTrust.trustFromInviteFingerprints.
 *
 * @param fingerprints SHA-256 fingerprints from the invite, colon-hex or
 *   bare hex, any case. Empty → returns false without connecting.
 * @return true when a served certificate matched and was pinned.
 *   Android: pins into [HouseholdTrustStore]. iOS/desktop: not yet
 *   implemented, always false (iOS native pinning = #733/#1232).
 */
expect suspend fun pinRelayFromInviteFingerprints(
    host: String,
    port: Int,
    fingerprints: List<String>,
): Boolean

/**
 * Extract host/port from a ws(s):// URL ("wss://host:4443/path" →
 * host to 4443). Default ports: wss/https 443, ws/http 80.
 * Null when the URL has no recognizable host.
 */
fun parseWsHostPort(url: String): Pair<String, Int>? {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    val defaultPort = if (scheme == "wss" || scheme == "https") 443 else 80
    val rest = trimmed.substring(schemeEnd + 3)
    val authority = rest.substringBefore('/').substringBefore('?')
    if (authority.isEmpty()) return null
    val colon = authority.lastIndexOf(':')
    return if (colon > 0) {
        val port = authority.substring(colon + 1).toIntOrNull() ?: return null
        authority.substring(0, colon) to port
    } else {
        authority to defaultPort
    }
}
