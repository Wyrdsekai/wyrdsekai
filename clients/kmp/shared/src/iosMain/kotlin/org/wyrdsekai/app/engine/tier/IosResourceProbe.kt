@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.wyrdsekai.app.engine.tier

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithAddress
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsIsWWAN
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.posix.AF_INET
import platform.posix.sockaddr_in

/**
 * iOS resource probe using platform.Foundation, platform.UIKit, and
 * platform.SystemConfiguration APIs.
 *
 * Memory: [NSProcessInfo.processInfo.physicalMemory] with conservative 30% availability estimate.
 * Thermal: [NSProcessInfo.processInfo.thermalState] mapped to our [ThermalState].
 * Battery: [UIDevice.currentDevice.batteryLevel] (-1.0 if monitoring not enabled, treated as 100%).
 * WiFi: [SCNetworkReachability] — reachable AND not WWAN means WiFi.
 *
 * WiFi detection approach:
 * NWPathMonitor (from Network framework) would be ideal for continuous monitoring,
 * but it requires an ongoing dispatch queue and callback setup that doesn't map well
 * to a synchronous snapshot() call. Instead, we use SCNetworkReachability from
 * SystemConfiguration, which provides a synchronous query: if the network is reachable
 * and NOT over WWAN (cellular), we consider it WiFi. This is a conservative heuristic
 * that also treats Ethernet as "WiFi" — acceptable for our tier system since wired
 * connections are at least as capable as WiFi.
 *
 */
class IosResourceProbe : ResourceProbe {

    override fun snapshot(): ResourceSnapshot {
        val processInfo = NSProcessInfo.processInfo

        // Physical memory in bytes -> MB
        val totalMb = (processInfo.physicalMemory.toLong()) / (1024L * 1024L)
        // Conservative estimate: 30% of physical memory available
        val availableMb = (totalMb * 3) / 10

        // NSProcessInfo.thermalState is not reliably exposed in KMP cinterop bindings.
        // Default to NOMINAL — thermal throttling is a secondary tier signal.
        val thermalState = ThermalState.NOMINAL

        // Enable battery monitoring to get level; returns -1.0 if not enabled
        val device = UIDevice.currentDevice
        val wasBatteryMonitoringEnabled = device.isBatteryMonitoringEnabled()
        device.setBatteryMonitoringEnabled(true)

        val batteryLevel = device.batteryLevel // 0.0 to 1.0, or -1.0
        val batteryPercent = if (batteryLevel < 0.0f) {
            100 // monitoring unavailable (e.g. simulator), assume full
        } else {
            (batteryLevel * 100).toInt().coerceIn(0, 100)
        }

        val isCharging = device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull

        // Restore previous monitoring state
        if (!wasBatteryMonitoringEnabled) {
            device.setBatteryMonitoringEnabled(false)
        }

        return ResourceSnapshot(
            availableMemoryMb = availableMb,
            totalMemoryMb = totalMb,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            thermalState = thermalState,
            hasWifi = getIsOnWifi(),
        )
    }

    /**
     * Detect WiFi using SCNetworkReachability.
     *
     * Creates a reachability reference for a zero address (general internet reachability)
     * and checks flags:
     * - kSCNetworkReachabilityFlagsReachable: network is reachable
     * - kSCNetworkReachabilityFlagsIsWWAN: reachable via cellular (not WiFi)
     *
     * Returns true if reachable AND not WWAN (i.e., WiFi or Ethernet).
     * Returns true as default if reachability check fails (conservative — don't
     * penalize tier due to detection failure).
     */
    private fun getIsOnWifi(): Boolean {
        return try {
            memScoped {
                val zeroAddr = alloc<sockaddr_in>()
                zeroAddr.sin_len = sizeOf<sockaddr_in>().toUByte()
                zeroAddr.sin_family = AF_INET.toUByte()

                val reachability = SCNetworkReachabilityCreateWithAddress(
                    null,
                    zeroAddr.ptr.reinterpret(),
                ) ?: return true // Can't create — default to true

                val flags = alloc<platform.SystemConfiguration.SCNetworkReachabilityFlagsVar>()
                val gotFlags = SCNetworkReachabilityGetFlags(reachability, flags.ptr)

                if (!gotFlags) return true // Can't get flags — default to true

                val flagsValue = flags.value
                val isReachable = (flagsValue and kSCNetworkReachabilityFlagsReachable) != 0u
                val isWWAN = (flagsValue and kSCNetworkReachabilityFlagsIsWWAN) != 0u

                // Reachable but not cellular = WiFi (or Ethernet, which is fine)
                isReachable && !isWWAN
            }
        } catch (_: Exception) {
            true // Default to true on any error — don't penalize tier
        }
    }

    // Note: NSProcessInfo.thermalState and NSProcessInfoThermalState* constants
    // are not available in current KMP cinterop bindings. When they become available,
    // add: private fun mapThermalState(state: Long): ThermalState = when (state) {
    //     0L -> NOMINAL, 1L -> FAIR, 2L -> SERIOUS, 3L -> CRITICAL, else -> NOMINAL
    // }
}
