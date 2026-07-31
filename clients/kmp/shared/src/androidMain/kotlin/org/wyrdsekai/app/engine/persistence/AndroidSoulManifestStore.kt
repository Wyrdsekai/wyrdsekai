package org.wyrdsekai.app.engine.persistence

import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.soul.ClientSoulManifest
import java.io.File

/**
 * Android file-backed SoulManifestStore.
 *
 * Stores soul manifests as JSON files, one per DID.
 * Atomic writes via temp file + rename. Same pattern as [AndroidEventJournal]
 * and [AndroidVitalityStore].
 */
class AndroidSoulManifestStore(dataDir: String) : SoulManifestStore {
    private val storeDir = File(dataDir, "soul-manifests")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        storeDir.mkdirs()
    }

    override suspend fun save(manifest: ClientSoulManifest) {
        val file = File(storeDir, "${manifest.did}.json")
        val tmp = File(storeDir, "${manifest.did}.json.tmp")
        tmp.writeText(manifest.toJson(), Charsets.UTF_8)
        tmp.renameTo(file)
    }

    override suspend fun load(did: String): ClientSoulManifest? {
        val file = File(storeDir, "$did.json")
        if (!file.exists()) return null
        return try {
            ClientSoulManifest.fromJson(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun delete(did: String) {
        val file = File(storeDir, "$did.json")
        file.delete()
    }

    override suspend fun listDids(): List<String> {
        if (!storeDir.exists()) return emptyList()
        return storeDir.listFiles()
            ?.filter { it.extension == "json" && !it.name.endsWith(".tmp") }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
