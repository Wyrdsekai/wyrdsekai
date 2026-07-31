package org.wyrdsekai.app.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.state.TokenStore

/**
 * ZoneBankStore — local persistence for the [ZoneBank]
 * the Android equivalent of the RN zustand-backed `zoneBankStore`.
 *
 * Holds the bank's two lists (held relays + zone address book) as ONE JSON blob
 * in the TokenStore, and wires [ZoneBank.onChange] back to it so every mutation
 * persists. Cross-device sync still goes through the home zone ([ZoneBankSync]);
 * this is just the on-device mirror so the bank survives app restarts.
 */
class ZoneBankStore(private val tokens: TokenStore = TokenStore()) {

    @Serializable
    private data class Blob(val relays: String = "[]", val zones: String = "[]")

    /** Build a ZoneBank hydrated from storage, with persistence wired in. */
    fun load(): ZoneBank {
        val bank = ZoneBank(onChange = { relaysJson, zonesJson -> persist(relaysJson, zonesJson) })
        val blob = tokens.loadZoneBank()
        if (!blob.isNullOrBlank()) {
            try {
                val parsed = Json.decodeFromString<Blob>(blob)
                bank.load(parsed.relays, parsed.zones)
            } catch (_: Exception) {
                // Corrupt blob → start empty rather than crash; next mutation rewrites it.
            }
        }
        return bank
    }

    private fun persist(relaysJson: String, zonesJson: String) {
        tokens.saveZoneBank(Json.encodeToString(Blob(relaysJson, zonesJson)))
    }
}
