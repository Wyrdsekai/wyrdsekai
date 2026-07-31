@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.wyrdsekai.app.network

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.posix.memcpy

/**
 * iOS pinned-certificate store — the counterpart of androidMain's
 * HouseholdTrustStore (SharedPreferences-backed). Stores one pinned DER
 * certificate per relay host in NSUserDefaults; consulted by
 * [createWsHttpClient]'s TLS challenge handler and seeded by
 * [pinRelayFromInviteFingerprints].
 */
object HouseholdTrustStore {
    private const val KEY_PREFIX = "wyrd_pin_"
    private val defaults = NSUserDefaults.standardUserDefaults
    private val cache = mutableMapOf<String, ByteArray>()

    fun put(host: String, der: ByteArray) {
        cache[host] = der
        defaults.setObject(
            der.toNSData().base64EncodedStringWithOptions(0u),
            forKey = KEY_PREFIX + host,
        )
    }

    fun get(host: String): ByteArray? {
        cache[host]?.let { return it }
        val base64 = defaults.stringForKey(KEY_PREFIX + host) ?: return null
        val data = NSData.create(base64EncodedString = base64, options = 0u) ?: return null
        val bytes = data.toByteArray()
        cache[host] = bytes
        return bytes
    }
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
