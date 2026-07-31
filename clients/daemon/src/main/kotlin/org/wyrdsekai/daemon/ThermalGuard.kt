package org.wyrdsekai.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log

/**
 * Monitors battery and thermal state to prevent overheating.
 * Critical for a daemon that runs continuously on a phone.
 *
 * Throttle levels match daemon-common's ThermalState:
 *   NORMAL   → full speed (4 threads, accept all)
 *   WARM     → reduce to 2 threads, reject DEFERRED
 *   HOT      → 1 thread, reject AGENT_INITIATED + DEFERRED
 *   CRITICAL → pause inference, drain queue, announce 0 slots
 */
class ThermalGuard(
    private val context: Context,
    private val onStateChange: (ThermalStateKt) -> Unit,
) {
    companion object {
        private const val TAG = "ThermalGuard"
    }

    @Volatile
    private var currentState = ThermalStateKt(
        ThrottleLevelKt.NORMAL, true, 100, 25f
    )

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f

            evaluate(isCharging, level, temp)
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val level = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> ThrottleLevelKt.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE -> ThrottleLevelKt.WARM
            PowerManager.THERMAL_STATUS_SEVERE -> ThrottleLevelKt.HOT
            else -> ThrottleLevelKt.CRITICAL
        }
        if (level.ordinal > currentState.level.ordinal) {
            currentState = currentState.copy(level = level)
            onStateChange(currentState)
        }
    }

    fun start() {
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val pm = context.getSystemService(PowerManager::class.java)
        pm.addThermalStatusListener(thermalListener)

        Log.i(TAG, "Thermal guard started")
    }

    fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}

        val pm = context.getSystemService(PowerManager::class.java)
        pm.removeThermalStatusListener(thermalListener)

        Log.i(TAG, "Thermal guard stopped")
    }

    fun currentState(): ThermalStateKt = currentState

    private fun evaluate(isCharging: Boolean, batteryPercent: Int, tempCelsius: Float) {
        val level = when {
            tempCelsius > 45 -> ThrottleLevelKt.CRITICAL
            tempCelsius > 40 -> ThrottleLevelKt.HOT
            tempCelsius > 38 -> ThrottleLevelKt.WARM
            !isCharging && batteryPercent < 20 -> ThrottleLevelKt.CRITICAL
            !isCharging && batteryPercent < 50 -> ThrottleLevelKt.HOT
            else -> ThrottleLevelKt.NORMAL
        }

        val newState = ThermalStateKt(level, isCharging, batteryPercent, tempCelsius)
        if (newState.level != currentState.level) {
            Log.i(TAG, "Throttle: ${currentState.level} → ${newState.level} " +
                "(temp=${tempCelsius}°C, bat=${batteryPercent}%, charging=$isCharging)")
        }
        currentState = newState
        onStateChange(newState)
    }
}

enum class ThrottleLevelKt { NORMAL, WARM, HOT, CRITICAL }

data class ThermalStateKt(
    val level: ThrottleLevelKt,
    val isCharging: Boolean,
    val batteryPercent: Int,
    val temperatureCelsius: Float,
) {
    fun acceptsRequests(): Boolean = level != ThrottleLevelKt.CRITICAL

    fun effectiveSlots(configured: Int): Int = when (level) {
        ThrottleLevelKt.NORMAL, ThrottleLevelKt.WARM -> configured
        ThrottleLevelKt.HOT -> minOf(1, configured)
        ThrottleLevelKt.CRITICAL -> 0
    }

    fun recommendedThreads(max: Int): Int = when (level) {
        ThrottleLevelKt.NORMAL -> max
        ThrottleLevelKt.WARM -> maxOf(1, max / 2)
        ThrottleLevelKt.HOT -> 1
        ThrottleLevelKt.CRITICAL -> 0
    }
}
