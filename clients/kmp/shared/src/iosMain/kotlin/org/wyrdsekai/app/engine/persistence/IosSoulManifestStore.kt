@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.soul.ClientSoulManifest
import platform.Foundation.*

/**
 * iOS file-backed SoulManifestStore.
 *
 * Stores soul manifests as JSON files using NSString I/O, one per DID.
 * Same pattern as [IosEventJournal] and [IosVitalityStore].
 */
class IosSoulManifestStore(dataDir: String) : SoulManifestStore {
    private val storeDir = "$dataDir/soul-manifests"

    init {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(storeDir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override suspend fun save(manifest: ClientSoulManifest) {
        val path = "$storeDir/${manifest.did}.json"
        val content = manifest.toJson()
        (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    override suspend fun load(did: String): ClientSoulManifest? {
        val path = "$storeDir/$did.json"
        val content = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            ?: return null
        return try {
            ClientSoulManifest.fromJson(content)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun delete(did: String) {
        val path = "$storeDir/$did.json"
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    override suspend fun listDids(): List<String> {
        val fm = NSFileManager.defaultManager
        val contents = fm.contentsOfDirectoryAtPath(storeDir, error = null) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val files = contents as List<String>
        return files
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
    }
}
