package org.wyrdsekai.app.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * DiscoverZones — "Find a zone" over the opt-in ZoneDirectory
 * KMP parity (P5) with the RN `discoverZones` module.
 *
 * Discovery is a separate, deliberate action from the bank. Only zones that
 * publish themselves to the directory appear; hidden zones never do, and a
 * relay's roster is never enumerated. From a discovered zone the user requests
 * access via a per-zone steward knock — that knock is a cross-zone path verified
 * against live infra (P6), so it is intentionally NOT wired here. This is the
 * read-only discovery surface; each result carries [DiscoveredZone.inBank] so the
 * UI can show "already in your servers" vs. "ask this zone's steward".
 */

/** A zone surfaced by the directory, normalised for the UI. */
data class DiscoveredZone(
    /** Zone label (subject scope) — the bank key if/when it's added. */
    val zoneLabel: String,
    val did: String? = null,
    val displayName: String? = null,
    val tagline: String? = null,
    val tags: List<String> = emptyList(),
    /** True if this zone is already in the user's bank (don't re-request). */
    val inBank: Boolean = false,
)

/** The slice of the NATS client this module needs — keeps it test-mockable. */
interface DirectorySearchClient {
    /** @return raw directory manifests, or null on transport failure. */
    suspend fun searchDirectory(query: String, limit: Int): List<JsonObject>?
}

data class DiscoverResult(val zones: List<DiscoveredZone>, val error: String? = null)

object DiscoverZones {

    private fun str(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    /**
     * Map raw directory manifests onto [DiscoveredZone], dropping entries with no
     * zone label (unaddressable) and flagging the ones already banked.
     */
    fun normalize(raw: List<JsonObject>, banked: Set<String>): List<DiscoveredZone> {
        val out = ArrayList<DiscoveredZone>()
        for (m in raw) {
            val zoneLabel = str(m, "zoneLabel") ?: str(m, "zoneId") ?: continue
            val tags = m["tags"]?.let { node ->
                try { node.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } } catch (_: Exception) { emptyList() }
            } ?: emptyList()
            out.add(
                DiscoveredZone(
                    zoneLabel = zoneLabel,
                    did = str(m, "did"),
                    displayName = str(m, "displayName"),
                    tagline = str(m, "tagline"),
                    tags = tags,
                    inBank = banked.contains(zoneLabel),
                )
            )
        }
        return out
    }

    /**
     * Query the directory and return normalised results. Best-effort: a transport
     * failure yields an empty list with the error, never throws.
     */
    suspend fun discover(
        client: DirectorySearchClient,
        bank: ZoneBank,
        query: String = "",
        limit: Int = 20,
    ): DiscoverResult {
        val raw = client.searchDirectory(query, limit)
            ?: return DiscoverResult(zones = emptyList(), error = "directory search failed")
        val banked = bank.zones.map { it.zoneId }.toSet()
        return DiscoverResult(zones = normalize(raw, banked))
    }
}
