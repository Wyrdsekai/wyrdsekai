package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.soul.ClientSoulManifest
import org.wyrdsekai.app.network.SoulClient

/**
 * [SoulManifestStore] backed by the server's soul REST API via [SoulClient].
 *
 * This enables client-side soul sync (Gap 5): the phone's soul manifest is
 * persisted to the household server so that other nodes (desktop, other phones,
 * server-side agents) can see the latest state.
 *
 * Operations:
 * - [save] syncs the manifest to the server via POST
 * - [load] fetches the latest manifest from the server via GET
 * - [delete] is a no-op (the server does not expose a delete endpoint yet)
 * - [listDids] returns an empty list (would need a server listing endpoint)
 *
 * For offline-first resilience, pair this with a local store (e.g.
 * [InMemorySoulManifestStore] or platform-specific file store) and use
 * this HTTP store as a sync target, not the sole source of truth.
 */
class HttpSoulManifestStore(
    private val soulClient: SoulClient,
    private val token: String,
) : SoulManifestStore {

    /**
     * Sync the manifest to the server. Throws on network failure so
     * callers (typically CompanionEngine) can catch and retry later.
     */
    override suspend fun save(manifest: ClientSoulManifest) {
        soulClient.syncManifest(manifest.did, manifest, token).getOrThrow()
    }

    /**
     * Fetch the latest manifest for the given DID from the server.
     * Returns null if the server has no manifest for this DID or on error.
     */
    override suspend fun load(did: String): ClientSoulManifest? {
        return soulClient.getLatest(did, token).getOrNull()
    }

    /**
     * No-op — the server does not expose a delete endpoint yet.
     * When it does, this will call DELETE /api/soul/{did}.
     */
    override suspend fun delete(did: String) {
        // Server delete endpoint not yet available
    }

    /**
     * Returns an empty list — the server does not expose a listing endpoint.
     * When it does, this will call GET /api/soul and return all DIDs.
     */
    override suspend fun listDids(): List<String> = emptyList()
}
