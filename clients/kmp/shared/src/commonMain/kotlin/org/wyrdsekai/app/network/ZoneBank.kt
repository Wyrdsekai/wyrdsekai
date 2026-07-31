package org.wyrdsekai.app.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ZoneBank — the phone's HELD RELAYS + ZONE BANK.
 *
 * KMP parity (P5) with the RN `zoneBankStore`. Two lists:
 *
 *   • [relays] — held transport credentials. A relay is dumb plumbing: pin it
 *     once (TOFU on caFp) from an invite, then never think about it again. The
 *     phone holds SEVERAL.
 *   • [zones]  — the user's ADDRESS BOOK of zones they can reach. The ONLY
 *     routing source: to open a zone we look it up here, take its relayUrls, and
 *     auto-attempt the login across whichever held relay(s) reach it. The phone
 *     NEVER enumerates a relay.
 *
 * Secrets: a zone entry stores [ZoneBankEntry.username] but NEVER the password.
 * The password lives in per-device secure storage; first use of a synced zone on
 * a new device prompts once, then remembers locally (§4.4).
 *
 * Pure state-holder — no platform deps. Persistence is delegated to [onChange]
 * (the caller wires it to TokenStore); timestamps are passed in by callers so
 * the logic stays deterministic and testable. Mirrors RN exactly so the two
 * clients converge on the same on-the-wire bank blob (§4 sync).
 */
@Serializable
data class HeldRelay(
    /** wss://host:port — the relay's NATS-over-WebSocket endpoint. */
    val wsUrl: String,
    /** Household CA SHA-256 (colon-hex) for TOFU pin; null on web-PKI relays. */
    val caFp: String? = null,
    /** Relay NATS credentials (transport auth, not account auth). */
    val natsUser: String,
    val natsPass: String,
    val label: String? = null,
    val addedAt: Long,
)

/** A zone bank entry = one server the user has access to. */
@Serializable
data class ZoneBankEntry(
    /** Canonical zone id — subject scope wyrd.zone.{zoneId}.* */
    val zoneId: String,
    /** Human label ("home-server", "example-relay Commons"). */
    val displayName: String,
    /** wsUrls of held relays that reach this zone, in preference order. */
    val relayUrls: List<String>,
    /** YOUR account name on this zone. The password is NOT stored here. */
    val username: String,
    /** True for the user's home zone (the sync anchor, §4.1). */
    val homeZone: Boolean = false,
    val addedAt: Long,
    val lastUsedAt: Long? = null,
)

class ZoneBank(
    private val onChange: (relaysJson: String, zonesJson: String) -> Unit = { _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var relays: List<HeldRelay> = emptyList()
        private set
    var zones: List<ZoneBankEntry> = emptyList()
        private set

    /** Hydrate from persisted JSON (either may be null/blank → empty). */
    fun load(relaysJson: String?, zonesJson: String?) {
        relays = decodeOrEmpty(relaysJson) { json.decodeFromString<List<HeldRelay>>(it) }
        zones = decodeOrEmpty(zonesJson) { json.decodeFromString<List<ZoneBankEntry>>(it) }
    }

    /** Add/refresh a held relay (dedupe by wsUrl; updates creds/label in place). */
    fun addRelay(relay: HeldRelay) {
        val idx = relays.indexOfFirst { it.wsUrl == relay.wsUrl }
        relays = if (idx >= 0) {
            // Refresh creds/label/fp in place, keep original addedAt.
            relays.toMutableList().also { it[idx] = relay.copy(addedAt = relays[idx].addedAt) }
        } else {
            relays + relay
        }
        persist()
    }

    fun removeRelay(wsUrl: String) {
        relays = relays.filterNot { it.wsUrl == wsUrl }
        persist()
    }

    /** Add/update a zone (last-write-wins by zoneId; merges relayUrls union). */
    fun addOrUpdateZone(zone: ZoneBankEntry) {
        val idx = zones.indexOfFirst { it.zoneId == zone.zoneId }
        zones = if (idx >= 0) {
            val prev = zones[idx]
            val merged = prev.relayUrls.toMutableList()
            for (u in zone.relayUrls) if (u !in merged) merged.add(u)
            zones.toMutableList().also {
                it[idx] = zone.copy(
                    relayUrls = merged,
                    addedAt = prev.addedAt,
                    lastUsedAt = zone.lastUsedAt ?: prev.lastUsedAt,
                )
            }
        } else {
            zones + zone
        }
        persist()
    }

    fun removeZone(zoneId: String) {
        zones = zones.filterNot { it.zoneId == zoneId }
        persist()
    }

    /** Bump lastUsedAt (call after a successful open). */
    fun touchZone(zoneId: String, now: Long) {
        zones = zones.map { if (it.zoneId == zoneId) it.copy(lastUsedAt = now) else it }
        persist()
    }

    /** Move (or add) a relay to the front of a zone's preference order. */
    fun bumpRelay(zoneId: String, wsUrl: String) {
        zones = zones.map {
            if (it.zoneId == zoneId) {
                it.copy(relayUrls = listOf(wsUrl) + it.relayUrls.filterNot { u -> u == wsUrl })
            } else it
        }
        persist()
    }

    /** Mark exactly one zone as the home/anchor; clears the flag on others. */
    fun setHomeZone(zoneId: String) {
        zones = zones.map { it.copy(homeZone = it.zoneId == zoneId) }
        persist()
    }

    fun getZone(zoneId: String): ZoneBankEntry? = zones.firstOrNull { it.zoneId == zoneId }

    /** Held relays that reach a zone, in the entry's preference order; falls back
     *  to all held relays when the entry names none this device has pinned. */
    fun relaysForZone(zoneId: String): List<HeldRelay> {
        val zone = getZone(zoneId) ?: return emptyList()
        val byUrl = zone.relayUrls.mapNotNull { u -> relays.firstOrNull { it.wsUrl == u } }
        return if (byUrl.isNotEmpty()) byUrl else relays
    }

    fun homeZone(): ZoneBankEntry? = zones.firstOrNull { it.homeZone }

    /** Serialize the zones (only) for upload — no secrets, no relay creds (§4). */
    fun serializeZones(): String = json.encodeToString(zones)

    /** Replace the whole zones list (used by the sync merge); persists once. */
    fun setZones(next: List<ZoneBankEntry>) {
        zones = next
        persist()
    }

    private fun persist() = onChange(json.encodeToString(relays), json.encodeToString(zones))

    private fun <T> decodeOrEmpty(raw: String?, decode: (String) -> List<T>): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return try { decode(raw) } catch (_: Exception) { emptyList() }
    }
}
