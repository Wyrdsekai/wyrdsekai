package org.wyrdsekai.app.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ServersViewModel — the "Your servers" screen's behavior (
 * P2/P5). KMP parity with the RN ServersScreen logic.
 *
 * Testable: the actual "open a zone" effect (which instantiates a NATS client) is
 * injected as [open], so this state machine runs in commonTest with a stub. The
 * observable fields are Compose snapshot state (mutableStateOf) so the Compose
 * layer recomposes on every transition; reads outside a composition (the tests)
 * return the plain value.
 */

/** What an open attempt produced — no client (the Compose adapter holds that). */
sealed interface OpenOutcome {
    data class Connected(val zoneId: String, val relayUrl: String) : OpenOutcome
    data object NeedsPassword : OpenOutcome
    data class AuthRejected(val error: String) : OpenOutcome
    data class Unreachable(val error: String) : OpenOutcome
}

class ServersViewModel(
    val bank: ZoneBank,
    private val open: suspend (zoneId: String, password: String?) -> OpenOutcome,
) {
    var busyZone: String? by mutableStateOf(null)
        private set
    /** Zone whose inline password prompt is open. */
    var promptZone: String? by mutableStateOf(null)
        private set
    var errorByZone: Map<String, String> by mutableStateOf(emptyMap())
        private set
    /** Set when a login succeeds — the Compose layer navigates on this. */
    var connectedZone: String? by mutableStateOf(null)
        private set

    /** Tap a server → auto-attempt the login. */
    suspend fun attempt(zoneId: String, password: String? = null) {
        busyZone = zoneId
        errorByZone = errorByZone - zoneId
        when (val r = open(zoneId, password)) {
            is OpenOutcome.Connected -> {
                promptZone = null
                connectedZone = r.zoneId
            }
            OpenOutcome.NeedsPassword -> promptZone = zoneId
            is OpenOutcome.AuthRejected -> {
                promptZone = zoneId
                errorByZone = errorByZone + (zoneId to r.error)
            }
            is OpenOutcome.Unreachable ->
                errorByZone = errorByZone + (zoneId to r.error)
        }
        busyZone = null
    }

    /**
     * Submit the inline prompt: capture a username first if the zone has none
     * (invite-seeded zones don't know your account name), then attempt.
     */
    suspend fun submitPrompt(zoneId: String, username: String?, password: String) {
        val z = bank.getZone(zoneId)
        if (z != null && z.username.isBlank() && !username.isNullOrBlank()) {
            bank.addOrUpdateZone(z.copy(username = username.trim()))
        }
        attempt(zoneId, password)
    }
}
