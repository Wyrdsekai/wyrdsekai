package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.engine.persistence.SoulManifestStore
import org.wyrdsekai.app.network.SoulClient
import org.wyrdsekai.app.platform.epochMillis

/**
 * SoulSyncManager — coordinates soul manifest synchronization between
 * the local device and the household server.
 *
 * Offline-first: all server communication is best-effort. If the server
 * is unreachable, the local manifest remains authoritative.
 *
 * Key behavior:
 * - Pull: if the server has a newer manifest version, replace local —
 *   but PRESERVE the user-chosen companion name (the user named Ma,
 *   she stays Ma even if the server says "Wyrd").
 * - Push: after a Forge/sleep cycle, upload the new manifest to the server.
 * - Bootstrap detection: manifests with "did:key:bootstrap-" DIDs are
 *   considered bootstrap (not yet replaced by a real Forge).
 */
class SoulSyncManager(
    private val soulClient: SoulClient,
    private val soulManifestStore: SoulManifestStore,
    private val serverUrl: String,
    private val token: String? = null,
) {
    /** Last successful sync time as epoch millis, or null if never synced. */
    var lastSyncTime: Long? = null
        private set

    /** Last synced manifest version, or null if never synced. */
    var lastSyncVersion: Int? = null
        private set

    /**
     * Try to pull a newer manifest from the server.
     *
     * If the server has a newer version, replaces local — but preserves
     * the user-chosen companion name (the user named Ma, she stays Ma
     * even if the server says "Wyrd").
     *
     * @param currentDid   DID to query on the server
     * @param currentName  User-chosen companion name to preserve
     * @return The updated manifest, or null if no update was needed/available
     */
    suspend fun tryPullFromServer(
        currentDid: String,
        currentName: String? = null,
    ): ClientSoulManifest? {
        // Fetch latest from server (returns null on failure via Result)
        val serverManifest = soulClient.getLatest(currentDid, token ?: "")
            .getOrNull() ?: return null

        // Check if server has a newer version than local
        val localManifest = try { soulManifestStore.load(currentDid) } catch (_: Exception) { null }
        val localVersion = localManifest?.manifestVersion ?: -1
        if (serverManifest.manifestVersion <= localVersion) return null

        // Preserve the user-chosen companion name if it differs from server's
        val merged = if (currentName != null && currentName != serverManifest.agentName) {
            serverManifest.copy(agentName = currentName)
        } else {
            serverManifest
        }

        // Save to local store
        try { soulManifestStore.save(merged) } catch (_: Exception) { /* non-fatal */ }

        // Update sync metadata
        lastSyncTime = epochMillis()
        lastSyncVersion = merged.manifestVersion

        return merged
    }

    /**
     * Push local manifest to server after a Forge/sleep cycle.
     * @return true if the server accepted the manifest, false otherwise
     */
    suspend fun pushToServer(manifest: ClientSoulManifest): Boolean {
        val response = soulClient.syncManifest(manifest.did, manifest, token ?: "")
            .getOrNull() ?: return false

        lastSyncTime = epochMillis()
        lastSyncVersion = response.version

        return response.accepted
    }

    /**
     * Check if a manifest is a bootstrap (not yet replaced by a real Forge).
     * Bootstrap manifests have DIDs matching "did:key:bootstrap-*".
     */
    fun isBootstrap(manifest: ClientSoulManifest): Boolean =
        manifest.did.startsWith("did:key:bootstrap-")
}
