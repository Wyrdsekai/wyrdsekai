package org.wyrdsekai.app.engine.tier

/**
 * Resource tiers for the phone node. T0 is the floor — the companion always exists.
 * Higher tiers unlock more rooms, local inference, and Between connectivity.
 *
 */
enum class Tier { T0, T1, T2, T3 }

enum class InferenceMode { LOCAL, REMOTE, HYBRID }

data class TierConfig(
    val maxRooms: Int,
    val maxConcurrentInference: Int,
    val maxContextTokens: Int,
    val scriptTimeoutMs: Long,
    val betweenEnabled: Boolean,
    val inferenceMode: InferenceMode,
) {
    companion object {
        val T0 = TierConfig(
            maxRooms = 0,
            maxConcurrentInference = 1,
            maxContextTokens = 2048,
            scriptTimeoutMs = 0,
            betweenEnabled = false,
            inferenceMode = InferenceMode.REMOTE,
        )

        val T1 = TierConfig(
            maxRooms = 1,
            maxConcurrentInference = 1,
            maxContextTokens = 4096,
            scriptTimeoutMs = 500,
            betweenEnabled = false,
            inferenceMode = InferenceMode.LOCAL,
        )

        val T2 = TierConfig(
            maxRooms = 4,
            maxConcurrentInference = 1,
            maxContextTokens = 8192,
            scriptTimeoutMs = 1000,
            betweenEnabled = true,
            inferenceMode = InferenceMode.LOCAL,
        )

        val T3 = TierConfig(
            maxRooms = 16,
            maxConcurrentInference = 2,
            maxContextTokens = 16384,
            scriptTimeoutMs = 2000,
            betweenEnabled = true,
            inferenceMode = InferenceMode.LOCAL,
        )

        fun forTier(tier: Tier): TierConfig = when (tier) {
            Tier.T0 -> T0
            Tier.T1 -> T1
            Tier.T2 -> T2
            Tier.T3 -> T3
        }
    }
}

/**
 * Snapshot of device resource state at a point in time.
 * Platform-specific implementations provide actual values.
 */
data class ResourceSnapshot(
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalState: ThermalState,
    val hasWifi: Boolean,
) {
    /** Recommend a tier based on current resources. */
    fun recommendTier(): Tier {
        // Thermal throttling always forces T0
        if (thermalState == ThermalState.CRITICAL) return Tier.T0

        // Battery critical forces T0
        if (batteryPercent < 10 && !isCharging) return Tier.T0

        // T3 requires charger + WiFi + comfortable resources
        if (isCharging && hasWifi && availableMemoryMb >= 3000 && thermalState == ThermalState.NOMINAL) {
            return Tier.T3
        }

        // T2 requires WiFi + good resources
        if (hasWifi && availableMemoryMb >= 2000 && batteryPercent >= 30) {
            return Tier.T2
        }

        // T1 is default — one room, local inference
        // Charging bypasses battery threshold
        if (availableMemoryMb >= 1200 && (batteryPercent >= 20 || isCharging)) {
            return Tier.T1
        }

        return Tier.T0
    }
}

enum class ThermalState { NOMINAL, FAIR, SERIOUS, CRITICAL }
