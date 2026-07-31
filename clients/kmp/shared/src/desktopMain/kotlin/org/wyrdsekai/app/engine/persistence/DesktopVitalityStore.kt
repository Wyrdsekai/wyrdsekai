package org.wyrdsekai.app.engine.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.wyrdsekai.app.engine.agent.VitalityState
import java.io.File

/**
 * Desktop file-backed VitalityStore.
 *
 * Stores vitality state as JSON files in `~/.wyrdsekai/vitality/`,
 * one file per entity ID. Atomic writes via temp file + rename.
 *
 * Same pattern as [AndroidVitalityStore] but targeting desktop
 * with a default data directory under the user's home.
 */
class DesktopVitalityStore(
    baseDir: String = "${System.getProperty("user.home")}/.wyrdsekai",
) : VitalityStore {
    private val storeDir = File(baseDir, "vitality")
    private val json = Json { ignoreUnknownKeys = true }

    private fun ensureDir() {
        if (!storeDir.exists()) storeDir.mkdirs()
    }

    override suspend fun save(entityId: String, state: VitalityState) {
        ensureDir()
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
