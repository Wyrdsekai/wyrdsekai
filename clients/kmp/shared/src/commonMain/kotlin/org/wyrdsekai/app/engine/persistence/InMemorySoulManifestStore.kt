package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.soul.ClientSoulManifest

/**
 * In-memory implementation of SoulManifestStore.
 * Suitable for testing and short-lived sessions. Not persistent across restarts.
 */
class InMemorySoulManifestStore : SoulManifestStore {
    private val store = mutableMapOf<String, ClientSoulManifest>()

    override suspend fun save(manifest: ClientSoulManifest) {
        store[manifest.did] = manifest
    }

    override suspend fun load(did: String): ClientSoulManifest? = store[did]

    override suspend fun delete(did: String) {
        store.remove(did)
    }

    override suspend fun listDids(): List<String> = store.keys.toList()
}
