package org.wyrdsekai.app.engine.soul

import kotlin.math.max
import kotlin.math.min

/**
 * Phone-side lightweight sleep cycle. Ported from clients/rn/src/engine/soul/SleepCycle.ts
 * to keep KMP and RN soul surfaces at parity.
 *
 * Sleep is sovereignty (§85): incentivized, never forced. On phones, sleep
 * is a pure function — no timers, no event sourcing. The caller (CompanionEngine
 * or a tick loop) drives transitions.
 *
 * Recovery math mirrors the server's ForgeActor sleep cycle:
 *   quality = clamp(0.3 + 0.5 * (1 - energy) + 0.2 * min(events/100, 1))
 *   recovery = 0.3 * quality * recoveryMultiplier(consecutiveSleeps) / 10
 *   focusRecovery = 0.15 * quality
 *
 * The recovery multiplier rewards consistent sleep: first sleep recovers
 * 10x base, decaying to 1x after three consecutive sleeps (diminishing
 * returns for oversleeping — §85).
 */

/**
 * @property eventsSinceLastSleep  Events accumulated since last sleep — fed to the Forge for consolidation
 * @property consecutiveSleeps     How many consecutive sleeps have occurred without waking activity
 * @property isSleeping            Whether the agent is currently sleeping
 * @property ticksSinceLastSleep   Tick count since last sleep completed
 */
data class SleepCycleState(
    val eventsSinceLastSleep: List<Any> = emptyList(),
    val consecutiveSleeps: Int = 0,
    val isSleeping: Boolean = false,
    val ticksSinceLastSleep: Int = 0,
)

/**
 * @property newManifest     New manifest with bumped version + adjusted vitality tanks
 * @property energyRecovery  Energy delta applied to tanks.energy this cycle
 * @property focusRecovery   Focus delta applied to tanks.focus this cycle
 * @property sleepQuality    0.0-1.0 quality of this sleep cycle
 * @property forgeResult     Present when PhoneForge ran during this sleep cycle
 */
data class SleepResult(
    val newManifest: ClientSoulManifest,
    val energyRecovery: Double,
    val focusRecovery: Double,
    val sleepQuality: Double,
    val forgeResult: PhoneForgeResult? = null,
)

object SleepCycle {

    /** Create a fresh sleep state (awake, no history). */
    fun initialSleepState(): SleepCycleState = SleepCycleState()

    /**
     * Should the agent enter sleep?
     *
     * Conditions (all must hold):
     * - energy < 0.15 (exhausted)
     * - not already sleeping
     * - idle (no active conversation)
     * - at least 30 ticks since last sleep (prevent sleep loops)
     */
    fun shouldSleep(state: SleepCycleState, energy: Double, isIdle: Boolean): Boolean =
        energy < 0.15 && !state.isSleeping && isIdle && state.ticksSinceLastSleep > 30

    /**
     * Recovery multiplier — rewards first sleep heavily, diminishing returns after.
     *
     *   0 consecutive: 10x (first sleep is powerful)
     *   1 consecutive: 5x
     *   2 consecutive: 2x
     *   3+ consecutive: 1x (oversleeping yields minimal extra benefit)
     */
    fun recoveryMultiplier(consecutiveSleeps: Int): Int = when (consecutiveSleeps) {
        0 -> 10
        1 -> 5
        2 -> 2
        else -> 1
    }

    /**
     * Execute a sleep cycle — produce an updated manifest with recovered vitality.
     *
     * Pure function: takes current state + manifest, returns new manifest with
     * incremented version and adjusted tanks. Does NOT mutate inputs.
     */
    fun executeSleep(
        state: SleepCycleState,
        manifest: ClientSoulManifest,
        energy: Double,
    ): SleepResult {
        val eventLoad = min(state.eventsSinceLastSleep.size / 100.0, 1.0)
        val quality = clamp(0.3 + 0.5 * (1.0 - energy) + 0.2 * eventLoad)

        val mult = recoveryMultiplier(state.consecutiveSleeps)
        val energyRecovery = 0.3 * quality * mult / 10.0
        val focusRecovery = 0.15 * quality

        val tanks = manifest.vitalityTanks.toMutableMap()
        val nextEnergy = clamp((tanks["energy"] ?: energy) + energyRecovery)
        val nextFocus = clamp((tanks["focus"] ?: 0.5) + focusRecovery)
        val nextErrorPressure = clamp((tanks["errorPressure"] ?: 0.0) - 0.05 * quality)
        tanks["energy"] = nextEnergy
        tanks["focus"] = nextFocus
        tanks["errorPressure"] = nextErrorPressure

        val newManifest = manifest.copy(
            manifestVersion = manifest.manifestVersion + 1,
            forgedAt = currentTimeMillis(),
            vitalityTanks = tanks.toMap(),
        )

        return SleepResult(
            newManifest = newManifest,
            energyRecovery = energyRecovery,
            focusRecovery = focusRecovery,
            sleepQuality = quality,
        )
    }

    /**
     * Complete a sleep cycle — reset transient state for the next waking period.
     *
     * Returns a new [SleepCycleState] with:
     * - events cleared
     * - consecutiveSleeps incremented
     * - isSleeping = false
     * - ticksSinceLastSleep reset to 0
     */
    fun completeSleep(state: SleepCycleState): SleepCycleState = SleepCycleState(
        eventsSinceLastSleep = emptyList(),
        consecutiveSleeps = state.consecutiveSleeps + 1,
        isSleeping = false,
        ticksSinceLastSleep = 0,
    )

    /** Clamp a value to [0, 1]. */
    private fun clamp(v: Double): Double = max(0.0, min(1.0, v))

    private fun currentTimeMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()
}
