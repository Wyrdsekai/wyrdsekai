package org.wyrdsekai.app.engine.tier

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Android resource probe using live system queries via [Context].
 *
 * Memory: [ActivityManager.MemoryInfo] for system-wide available/total RAM.
 * Battery: [BatteryManager] for live battery percentage and charging state.
 * Thermal: [PowerManager.getCurrentThermalStatus] on API 29+, CPU heuristic fallback on API 28.
 * WiFi: [ConnectivityManager] with [NetworkCapabilities.TRANSPORT_WIFI].
 *
 * All queries are live — no cached/constructor parameters. Each call to [snapshot]
 * reflects the device's current state.
 *
 */
class AndroidResourceProbe(private val context: Context) : ResourceProbe {

    override fun snapshot(): ResourceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val availableMb = memInfo.availMem / (1024L * 1024L)

        return ResourceSnapshot(
            availableMemoryMb = availableMb,
            totalMemoryMb = totalMb,
            batteryPercent = getBatteryPercent(),
            isCharging = getIsCharging(),
            thermalState = getThermalState(),
            hasWifi = getIsOnWifi(),
        )
    }

    /**
     * Live battery percentage via BatteryManager.
     * Returns 0-100, or 100 if unavailable (e.g. emulator without battery).
     */
    private fun getBatteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return 100
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // Returns -1 if the property is not available
        return if (level >= 0) level.coerceIn(0, 100) else 100
    }

    /**
     * Live charging state via BatteryManager.isCharging (API 23+, minSdk is 28).
     */
    private fun getIsCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return false
        return bm.isCharging
    }

    /**
     * Thermal state from PowerManager (API 29+) or CPU load heuristic (API 28).
     *
     * API 29+ provides getCurrentThermalStatus() which maps directly to our
     * ThermalState enum. On API 28, we fall back to a simple CPU load heuristic
     * using system load average (same approach as the old probe).
     */
    private fun getThermalState(): ThermalState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: PowerManager thermal status
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                return when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.NOMINAL
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
                    PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                    else -> ThermalState.NOMINAL
                }
            }
        }

        // API 28 fallback: CPU load heuristic
        return getCpuLoadThermalEstimate()
    }

    /**
     * Fallback thermal estimate based on system CPU load (for API 28).
     * Uses the same heuristic as the previous JVM-based probe.
     */
    private fun getCpuLoadThermalEstimate(): ThermalState {
        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            val loadAvg = osBean.systemLoadAverage
            if (loadAvg < 0.0) return ThermalState.NOMINAL // unavailable
            val processors = osBean.availableProcessors.coerceAtLeast(1)
            val cpuLoad = (loadAvg / processors).coerceIn(0.0, 1.0)
            when {
                cpuLoad > 0.95 -> ThermalState.SERIOUS
                cpuLoad > 0.80 -> ThermalState.FAIR
                else -> ThermalState.NOMINAL
            }
        } catch (_: Exception) {
            ThermalState.NOMINAL
        }
    }

    /**
     * WiFi detection via ConnectivityManager + NetworkCapabilities.
     *
     * Uses the modern getNetworkCapabilities API (available since our minSdk 28).
     * Returns false if connectivity info is unavailable.
     */
    private fun getIsOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
