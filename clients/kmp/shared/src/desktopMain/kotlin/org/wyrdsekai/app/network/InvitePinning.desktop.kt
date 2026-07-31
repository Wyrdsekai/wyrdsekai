package org.wyrdsekai.app.network

// Desktop has no HouseholdTrustStore (it talks to the local node over
// localhost); nothing to pin.
actual suspend fun pinRelayFromInviteFingerprints(
    host: String,
    port: Int,
    fingerprints: List<String>,
): Boolean = false
