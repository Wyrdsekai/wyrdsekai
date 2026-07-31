package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.soul.ClientSoulManifest
import java.io.File

/**
 * Desktop file-backed SoulManifestStore.
 *
 * Stores soul manifests as JSON files in `~/.wyrdsekai/soul/`,
 * one file per DID. Atomic writes via temp file + rename.
 *
 * Same pattern as [AndroidSoulManifestStore] but targeting desktop
 * with a default data directory under the user's home.
 */
class DesktopSoulManifestStore(
    baseDir: String = "${System.getProperty("user.home")}/.wyrdsekai",
) : SoulManifestStore {
    private val storeDir = File(baseDir, "soul")

    private fun ensureDir() {
        if (!storeDir.exists()) storeDir.mkdirs()
    }

    override suspend fun save(manifest: ClientSoulManifest) {
        ensureDir()
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
