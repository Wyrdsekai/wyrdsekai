package org.wyrdsekai.app.engine.tier

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Platform abstraction for querying device resources.
 * Implemented per-platform (iOS: ProcessInfo, Android: ActivityManager, etc.)
 */
interface ResourceProbe {
    fun snapshot(): ResourceSnapshot
}

/**
 * Default probe for testing — reports comfortable T1 resources.
 */
class DefaultResourceProbe : ResourceProbe {
    override fun snapshot() = ResourceSnapshot(
        availableMemoryMb = 2000,
        totalMemoryMb = 4000,
        batteryPercent = 80,
        isCharging = false,
        thermalState = ThermalState.NOMINAL,
        hasWifi = true,
    )
}

/**
 * Manages tier transitions based on device resources.
 *
 * Probes resources periodically and recommends tier changes.
 * Does NOT directly start/stop rooms — emits tier change events
 * for PhoneNode to act on.
 *
 */
class TierManager(
    private val probe: ResourceProbe,
    private val probeIntervalMs: Long = 30_000,
    private val scope: CoroutineScope,
) {
    private val _currentTier = MutableStateFlow(Tier.T0)
    val currentTier: StateFlow<Tier> = _currentTier.asStateFlow()

    private val _config = MutableStateFlow(TierConfig.forTier(Tier.T0))
    val config: StateFlow<TierConfig> = _config.asStateFlow()

    private val _transitions = MutableSharedFlow<TierTransition>(extraBufferCapacity = 8)
    val transitions: SharedFlow<TierTransition> = _transitions.asSharedFlow()

    private var monitorJob: Job? = null

    /** Probe once and set initial tier. */
    fun initialize() {
        val snapshot = probe.snapshot()
        val recommended = snapshot.recommendTier()
        applyTier(recommended, snapshot, reason = "initial")
    }

    /** Start periodic resource monitoring. */
    fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                delay(probeIntervalMs)
                val snapshot = probe.snapshot()
                val recommended = snapshot.recommendTier()
                if (recommended != _currentTier.value) {
                    applyTier(recommended, snapshot, reason = "resource_change")
                }
            }
        }
    }

    /** Stop periodic monitoring. */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /** Force a specific tier (for testing or manual override). */
    fun forceTier(tier: Tier) {
        val snapshot = probe.snapshot()
        applyTier(tier, snapshot, reason = "forced")
    }

    private fun applyTier(newTier: Tier, snapshot: ResourceSnapshot, reason: String) {
        val oldTier = _currentTier.value
        if (newTier == oldTier && reason != "initial") return

        _currentTier.value = newTier
        _config.value = TierConfig.forTier(newTier)

        // tryEmit is non-suspending; extraBufferCapacity=8 ensures it succeeds
        _transitions.tryEmit(TierTransition(
            from = oldTier,
            to = newTier,
            reason = reason,
            snapshot = snapshot,
        ))
    }
}

data class TierTransition(
    val from: Tier,
    val to: Tier,
    val reason: String,
    val snapshot: ResourceSnapshot,
) {
    val isPromotion: Boolean get() = to > from
    val isDemotion: Boolean get() = to < from
}
