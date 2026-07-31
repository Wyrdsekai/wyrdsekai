package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.soul.ClientSoulManifest

/**
 * Persistence for soul manifests on the client.
 * Platform-specific implementations can use SQLite, files, etc.
 * The in-memory implementation is provided for testing.
 */
interface SoulManifestStore {
    suspend fun save(manifest: ClientSoulManifest)
    suspend fun load(did: String): ClientSoulManifest?
    suspend fun delete(did: String)
    suspend fun listDids(): List<String>
}
