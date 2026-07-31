package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.agent.VitalityState

/**
 * In-memory vitality store for desktop.
 * Desktop runs the full server subprocess with its own persistence.
 */
class InMemoryVitalityStore : VitalityStore {
    private val store = mutableMapOf<String, VitalityState>()

    override suspend fun save(entityId: String, state: VitalityState) {
        store[entityId] = state
    }

    override suspend fun load(entityId: String): VitalityState? {
        return store[entityId]
    }
}
