package org.wyrdsekai.app.network

/**
 * ZoneBankSync — cross-device sync of the zone bank
 * KMP parity (P5) with the RN `zoneBankSync` module.
 *
 * The bank (the address book of zones) is sovereign account state mirrored on
 * the user's home zone. Each authenticated device pulls it on connect and pushes
 * its merged view back, so every device converges on the union of the user's
 * zones. Only the zones[] address book travels — held-relay credentials and zone
 * passwords are per-device secrets that never enter the blob (§4.4).
 *
 * Merge is per-entry last-write-wins by zoneId, "newest" = max(lastUsedAt,
 * addedAt). Symmetric and idempotent: pull → merge newest-wins → push the merged
 * superset. The server is a dumb blob store keyed by account; the LWW logic lives
 * here, identical to RN so the two clients interoperate.
 */

/** Server's stored bank + its stamp (null bank = nothing stored yet). */
data class ZoneBankFetch(val bank: String?, val updatedAt: Long)

/** The slice of the NATS client this module needs — keeps it test-mockable. */
interface ZoneBankSyncClient {
    /** @return the stored bank, or null on transport/auth failure. */
    suspend fun getZoneBank(): ZoneBankFetch?
    /** @return true on success. */
    suspend fun putZoneBank(bankJson: String, updatedAt: Long): Boolean
}

data class SyncResult(
    val ok: Boolean,
    val pulled: Int,    // entries merged in from the server
    val pushed: Boolean, // whether we uploaded the merged bank
    val error: String? = null,
)

object ZoneBankSync {

    private fun entryTs(z: ZoneBankEntry): Long = maxOf(z.lastUsedAt ?: 0L, z.addedAt)

    /**
     * Merge a remote zones list into [bank], per-entry newest-wins. Returns the
     * number of entries added or updated. Relay credentials are not in the blob,
     * so a synced entry may name relayUrls this device hasn't pinned yet — fine;
     * relaysForZone falls back to all held relays.
     */
    fun mergeRemoteZones(bank: ZoneBank, remote: List<ZoneBankEntry>): Int {
        var changed = 0
        for (r in remote) {
            if (r.zoneId.isBlank()) continue
            val local = bank.getZone(r.zoneId)
            if (local == null || entryTs(r) > entryTs(local)) {
                bank.addOrUpdateZone(r)
                changed++
            }
        }
        return changed
    }

    private fun parseRemote(bank: ZoneBank, raw: String?): List<ZoneBankEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            // Reuse ZoneBank's decoder by round-tripping through a scratch bank.
            ZoneBank().also { it.load(null, raw) }.zones
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Full sync against the connected zone: pull → merge newest-wins into [bank]
     * → push the merged superset so the server (and the user's other devices)
     * converges. Best-effort — a failure never blocks the session; the local bank
     * stays authoritative on this device.
     */
    suspend fun syncZoneBank(bank: ZoneBank, client: ZoneBankSyncClient, now: Long): SyncResult {
        val fetched = client.getZoneBank()
            ?: return SyncResult(ok = false, pulled = 0, pushed = false, error = "zonebank get failed")
        val pulled = mergeRemoteZones(bank, parseRemote(bank, fetched.bank))

        // Push even when pulled==0: this device may hold zones the server hasn't
        // seen. Skip only when the local bank is empty AND nothing came down.
        if (bank.zones.isEmpty()) {
            return SyncResult(ok = true, pulled = pulled, pushed = false)
        }
        val ok = client.putZoneBank(bank.serializeZones(), now)
        return SyncResult(ok = true, pulled = pulled, pushed = ok, error = if (ok) null else "zonebank put failed")
    }
}
