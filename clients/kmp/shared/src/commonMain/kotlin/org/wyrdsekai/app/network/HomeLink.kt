package org.wyrdsekai.app.network

import io.ktor.client.plugins.api.createClientPlugin
import org.wyrdsekai.app.i18n.currentUiStrings

/**
 * How the phone reaches its home without anything readable on the way
 * ( W2).
 *
 * On the home network the phone uses the address the invite carried
 * (`lan_https`, https://host:7443) and the home's bus (the invite's
 * `home_bus`, or the pairing reply's `natsUrl`: wss://host:<bus port + 1>),
 * both pinned to the household CA (`home_ca_fp`). Plain http:// or ws:// is
 * used only to this device itself (a local node). A phone paired before home
 * TLS has only a plain http:// address; it does not use it — it goes through
 * the relay and is told once to pair again.
 *
 * The relay and the home's bus are different places: the relay's address
 * comes only from the invite's `relays`, the bus address only from the
 * invite's `home_bus` or a pairing reply, and neither is ever a loopback
 * address (on a phone that is the phone itself).
 */
object HomeLink {
    /** The home bus websocket port when neither the invite nor a pairing named one (a home on the default bus port). */
    const val HOME_BUS_PORT = 4223

    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return h == "localhost" || h == "::1" || LOOPBACK_V4.matches(h) || h in devLoopbackAliases()
    }

    /** 127.0.0.0/8 as a literal address only: a name such as 127.example.org can point anywhere. */
    private val LOOPBACK_V4 = Regex("""^127(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

    /** host of `scheme://host[:port]/…` or of a bare `host[:port]`; null when there is none. */
    fun hostOf(url: String): String? {
        val rest = url.trim().substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringAfter('@')
        if (authority.isEmpty()) return null
        if (authority.startsWith("[")) return authority.substringBefore(']') + "]"
        return authority.substringBefore(':').ifEmpty { null }
    }

    /** True when [url] would carry the home's traffic unencrypted off this device. A bare host means http. */
    fun isPlaintextOffDevice(url: String): Boolean {
        val t = url.trim()
        val scheme = if (t.contains("://")) t.substringBefore("://").lowercase() else "http"
        if (scheme == "https" || scheme == "wss") return false
        val host = hostOf(t) ?: return true
        return !isLoopbackHost(host)
    }

    /**
     * The base URL for the home's HTTP API. The invite's `lan_https` when the
     * phone has it (with the CA fingerprint it pins); otherwise [savedServerUrl]
     * only when it is encrypted or local; otherwise null (use the relay).
     */
    fun homeBase(savedServerUrl: String?, lanHttps: String?, homeCaFp: String?): String? {
        if (!lanHttps.isNullOrBlank() && !homeCaFp.isNullOrBlank()) return lanHttps.trim().trimEnd('/')
        val saved = savedServerUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (isPlaintextOffDevice(saved)) null else saved
    }

    /** True when the phone holds a plain http:// home address from before home TLS and nothing newer. */
    fun needsRepairForLan(savedServerUrl: String?, lanHttps: String?, homeCaFp: String?): Boolean =
        homeBase(savedServerUrl, lanHttps, homeCaFp) == null && !savedServerUrl.isNullOrBlank()

    /**
     * A literal loopback address (127.0.0.0/8, localhost, ::1): this device
     * itself. Unlike [isLoopbackHost] it does not count the emulator's alias
     * for the host machine, which is another machine.
     */
    fun isDeviceLoopback(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return h == "localhost" || h == "::1" || LOOPBACK_V4.matches(h)
    }

    private fun schemeOf(url: String): String? =
        url.trim().takeIf { it.contains("://") }?.substringBefore("://")?.lowercase()

    /**
     * [url] when it can be the home's bus for this phone: wss:// to another
     * machine. Null for a plain address (the phone never sends the bus in the
     * clear), a loopback one (a home that named its own loopback, e.g. an old
     * pairing reply's nats://127.0.0.1:4222) or anything without a host.
     */
    fun usableHomeBus(url: String?): String? {
        val t = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        if (schemeOf(t) != "wss") return null
        val host = hostOf(t) ?: return null
        return if (isDeviceLoopback(host)) null else t
    }

    /**
     * [url] when it can be the relay leg: wss:// (ws:// only to the emulator's
     * host alias in debug builds) to another machine. A pairing reply's
     * `natsUrl` is the home's bus, never a relay; only the invite's `relays`
     * name one.
     */
    fun usableRelayUrl(url: String?): String? {
        val t = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = schemeOf(t) ?: return null
        if (scheme != "wss" && scheme != "ws") return null
        if (isPlaintextOffDevice(t)) return null
        val host = hostOf(t) ?: return null
        return if (isDeviceLoopback(host)) null else t
    }

    /**
     * The home's bus for this phone: the address the home named (the
     * invite's `home_bus` or the pairing reply's `natsUrl`, see
     * [usableHomeBus]); for an invite from a home that named none,
     * wss://<lan_https host>:[HOME_BUS_PORT]. Null when neither exists.
     */
    fun homeBusUrl(homeBus: String?, lanHttps: String?): String? {
        usableHomeBus(homeBus)?.let { return it }
        val host = lanHttps?.let { hostOf(it) } ?: return null
        if (isDeviceLoopback(host)) return null
        return "wss://$host:$HOME_BUS_PORT"
    }
}

/** Thrown instead of sending the home's traffic unencrypted off the device. */
class PlaintextRefusedException(val host: String) : IllegalStateException(currentUiStrings().secNeedsInvite)

/**
 * Installed on every client that talks to the home: a request over http:// or
 * ws:// to anything but this device is refused before it is sent.
 */
val RefusePlaintextOffDevice = createClientPlugin("RefusePlaintextOffDevice") {
    onRequest { request, _ ->
        val scheme = request.url.protocol.name.lowercase()
        if ((scheme == "http" || scheme == "ws") && !HomeLink.isLoopbackHost(request.url.host)) {
            throw PlaintextRefusedException(request.url.host)
        }
    }
}

/**
 * Loopback aliases a platform adds: the Android emulator's 10.0.2.2 (the
 * host's own loopback) in debuggable builds only, for the emulator e2e flows.
 */
expect fun devLoopbackAliases(): Set<String>
