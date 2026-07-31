package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.agent.VitalityState

/**
 * Persistence for agent vitality state.
 * Platform-specific implementations: SQLite on iOS, stubs elsewhere.
 */
interface VitalityStore {
    suspend fun save(entityId: String, state: VitalityState)
    suspend fun load(entityId: String): VitalityState?
}
