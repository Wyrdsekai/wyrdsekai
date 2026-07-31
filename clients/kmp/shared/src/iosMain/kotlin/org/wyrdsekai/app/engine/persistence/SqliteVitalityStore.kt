@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.wyrdsekai.app.engine.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.agent.VitalityState
import platform.Foundation.*

/**
 * File-backed vitality store for iOS using JSON files in NSDocumentDirectory.
 */
class IosVitalityStore(dataDir: String) : VitalityStore {
    private val storeDir = "$dataDir/vitality"
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(storeDir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override suspend fun save(entityId: String, state: VitalityState) {
        val path = "$storeDir/$entityId.json"
        val content = json.encodeToString(state)
        (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    override suspend fun load(entityId: String): VitalityState? {
        val path = "$storeDir/$entityId.json"
        val content = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            ?: return null
        return try {
            json.decodeFromString<VitalityState>(content)
        } catch (_: Exception) {
            null
        }
    }
}
