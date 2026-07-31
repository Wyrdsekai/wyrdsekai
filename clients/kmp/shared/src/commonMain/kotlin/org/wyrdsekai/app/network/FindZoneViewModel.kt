package org.wyrdsekai.app.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * FindZoneViewModel — the "Find a zone" screen's behavior (
 * §5/P5). KMP parity with the RN FindZoneScreen logic.
 *
 * Pure + testable: directory search and the knock are injected, so this runs in
 * commonTest with stubs. The knock is REAL (records an access request the zone's
 * steward sees) — not theater.
 */
class FindZoneViewModel(
    private val discover: suspend (query: String, limit: Int) -> DiscoverResult,
    private val requestAccessFn: suspend (zoneLabel: String, requesterName: String) -> Boolean,
    private val requesterName: () -> String = { "a wyrdsekai user" },
) {
    var busy by mutableStateOf(false)
        private set
    var searched by mutableStateOf(false)
        private set
    var results: List<DiscoveredZone> by mutableStateOf(emptyList())
        private set
    var error: String? by mutableStateOf(null)
        private set
    /** Per-zone knock state: "asking" while in-flight, "sent" once recorded. */
    var knockState: Map<String, String> by mutableStateOf(emptyMap())
        private set

    suspend fun search(query: String, limit: Int = 20) {
        busy = true
        error = null
        val r = discover(query, limit)
        results = r.zones
        error = r.error
        searched = true
        busy = false
    }

    /** Knock on a discovered zone's door — records a real access request. */
    suspend fun requestAccess(zone: DiscoveredZone) {
        knockState = knockState + (zone.zoneLabel to "asking")
        val ok = requestAccessFn(zone.zoneLabel, requesterName())
        knockState = if (ok) {
            knockState + (zone.zoneLabel to "sent")
        } else {
            error = "Could not reach that zone's steward."
            knockState - zone.zoneLabel
        }
    }
}
