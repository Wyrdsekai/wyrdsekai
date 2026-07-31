package org.wyrdsekai.app.platform

import kotlin.math.abs
import kotlin.math.round
import kotlin.time.Clock

/**
 * Process-global string properties — the app's runtime config bus
 * (server URL, auth token, companion name, …).
 *
 * JVM targets back this with java.lang.System properties so platform
 * code that still reads System.getProperty sees the same values; iOS
 * keeps an in-memory map with the same process lifetime.
 */
expect object AppProps {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/**
 * Minimal file operations for the engine's flat-file stores
 * (soul items, offline queue, debug logs). Paths are plain strings.
 */
expect object AppFiles {
    fun mkdirs(dir: String)
    fun exists(path: String): Boolean
    /** Returns null when the file is missing or unreadable. */
    fun readText(path: String): String?
    /** Atomic: temp + rename on JVM, NSString atomically on iOS. */
    fun writeTextAtomic(path: String, text: String)
    fun appendText(path: String, text: String)
    /** File names (not paths) in [dir]; empty when missing. */
    fun listFileNames(dir: String): List<String>
    fun delete(path: String)
}

expect fun sha256(bytes: ByteArray): ByteArray

/**
 * [n] bytes from the platform CSPRNG. For anything that acts as a capability
 * (relay tunnel session ids — see RelayTunnelServerConnection), NOT
 * kotlin.random.Random, which is neither seeded for nor intended for secrets.
 */
expect fun secureRandomBytes(n: Int): ByteArray

/** Lowercase hex of [n] CSPRNG bytes. */
fun secureRandomHex(n: Int): String =
    secureRandomBytes(n).joinToString("") {
        val v = it.toInt() and 0xFF
        val hex = v.toString(16)
        if (hex.length == 1) "0$hex" else hex
    }

/** Non-loopback IPv4 addresses of this device, for LAN discovery. */
expect fun localIpv4Addresses(): List<String>

/** Bundled text resource — classpath on JVM targets, NSBundle on iOS. */
expect fun readBundledText(resourcePath: String): String?

/** Open a URL in the system browser. Best-effort; false when unavailable. */
expect fun openUrlInBrowser(url: String): Boolean

fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** "%.2f"-style fixed-decimal formatting without JVM String.format. */
fun formatFixed(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = round(abs(value) * factor).toLong()
    val intPart = scaled / factor
    val fracPart = (scaled % factor).toString().padStart(decimals, '0')
    val sign = if (value < 0) "-" else ""
    return if (decimals == 0) "$sign$intPart" else "$sign$intPart.$fracPart"
}

/** RFC 3986 percent-encoding (UTF-8) for URL query components. */
fun percentEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val i = b.toInt() and 0xff
        val c = i.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
            append(c)
        } else {
            append('%')
            append(i.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
