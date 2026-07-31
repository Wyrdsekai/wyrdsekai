package org.wyrdsekai.app.network

import kotlinx.serialization.json.Json
import org.wyrdsekai.app.state.TokenStore

/**
 * TokenStoreZonePasswordStore — the real [ZonePasswordStore] backing
 * Persists the per-device zone→password map as ONE
 * serialized JSON blob in the encrypted TokenStore. NEVER synced — passwords
 * stay on this device; only the zone bank (zones + relay creds) crosses devices.
 *
 * The whole map is read/rewritten on each mutation: the map is tiny (one entry
 * per banked server) so this is cheaper than N secure-store keys, and it keeps
 * a single clear-on-logout surface.
 */
class TokenStoreZonePasswordStore(
    private val tokens: TokenStore = TokenStore(),
) : ZonePasswordStore {

    private fun load(): MutableMap<String, String> {
        val blob = tokens.loadZonePasswords() ?: return mutableMapOf()
        return try {
            Json.decodeFromString<Map<String, String>>(blob).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun persist(map: Map<String, String>) {
        tokens.saveZonePasswords(Json.encodeToString(map))
    }

    override fun getPassword(zoneId: String): String? = load()[zoneId]

    override fun setPassword(zoneId: String, password: String) {
        val map = load()
        map[zoneId] = password
        persist(map)
    }

    override fun forgetPassword(zoneId: String) {
        val map = load()
        if (map.remove(zoneId) != null) persist(map)
    }
}
