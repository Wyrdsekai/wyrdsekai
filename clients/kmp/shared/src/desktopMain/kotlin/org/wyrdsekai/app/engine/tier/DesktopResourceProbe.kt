package org.wyrdsekai.app.engine.tier

import java.lang.management.ManagementFactory

/**
 * Desktop resource probe using JVM Runtime + OperatingSystemMXBean.
 *
 * Memory: [Runtime.getRuntime] freeMemory/totalMemory/maxMemory.
 * CPU: [com.sun.management.OperatingSystemMXBean.getCpuLoad] (Java 17+), normalized 0.0-1.0.
 * Thermal: always NOMINAL (desktop machines don't thermal-throttle in the way phones do).
 * Battery: always 100 / charging (desktop assumed plugged in).
 */
class DesktopResourceProbe : ResourceProbe {

    override fun snapshot(): ResourceSnapshot {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.maxMemory() / (1024 * 1024)
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val availableMb = totalMb - usedMb

        return ResourceSnapshot(
            availableMemoryMb = availableMb,
            totalMemoryMb = totalMb,
            batteryPercent = 100,
            isCharging = true,
            thermalState = ThermalState.NOMINAL,
            hasWifi = true,
        )
    }

    /**
     * Returns CPU load via com.sun.management.OperatingSystemMXBean (Java 17+).
     * Falls back to system load average if the cast fails.
     * Not currently used in snapshot() (desktop is always NOMINAL) but available
     * for tier recommendation if needed in the future.
     */
    fun getCpuLoad(): Double {
        return try {
            val osBean = ManagementFactory.getOperatingSystemMXBean()
                as com.sun.management.OperatingSystemMXBean
            val load = osBean.cpuLoad
            if (load < 0.0) 0.5 else load.coerceIn(0.0, 1.0)
        } catch (_: Exception) {
            // Fallback: system load average normalized by processor count
            val osBean = ManagementFactory.getOperatingSystemMXBean()
            val loadAvg = osBean.systemLoadAverage
            if (loadAvg < 0.0) return 0.5
            val processors = osBean.availableProcessors.coerceAtLeast(1)
            (loadAvg / processors).coerceIn(0.0, 1.0)
        }
    }
}
