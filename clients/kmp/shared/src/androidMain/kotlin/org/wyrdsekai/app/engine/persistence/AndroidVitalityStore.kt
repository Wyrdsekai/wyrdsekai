package org.wyrdsekai.app.engine.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.wyrdsekai.app.engine.agent.VitalityState
import java.io.File

/**
 * Android file-backed VitalityStore.
 *
 * Stores vitality state as JSON files, one per entity.
 * Same pattern as IosVitalityStore but using java.io.File.
 */
class AndroidVitalityStore(dataDir: String) : VitalityStore {
    private val storeDir = File(dataDir, "vitality")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        storeDir.mkdirs()
    }

    override suspend fun save(entityId: String, state: VitalityState) {
        val file = File(storeDir, "$entityId.json")
        val tmp = File(storeDir, "$entityId.json.tmp")
        tmp.writeText(json.encodeToString(state), Charsets.UTF_8)
        tmp.renameTo(file)
    }

    override suspend fun load(entityId: String): VitalityState? {
        val file = File(storeDir, "$entityId.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<VitalityState>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
