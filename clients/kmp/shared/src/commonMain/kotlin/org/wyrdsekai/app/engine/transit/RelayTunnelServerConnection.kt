package org.wyrdsekai.app.engine.transit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage
import org.wyrdsekai.app.protocol.WireJson
import org.wyrdsekai.app.protocol.parseS2CMessage
import org.wyrdsekai.app.protocol.toJson
import org.wyrdsekai.app.platform.secureRandomHex

/**
 * a [ServerConnection] tunneled through the relay.
 *
 * The phone interface is a terminal that speaks the C2S/S2C session protocol to
 * a [ServerConnection]. Offline mode points it at [LocalServerConnection];
 * remote-over-relay points it HERE. The terminal can't tell the difference —
 * that's the whole point. It still only sends [C2SMessage] and renders
 * [S2CMessage]; this transport just carries those frames over the relay's dumb
 * pipe instead of an in-process node.
 *
 * Wire: the same C2S/S2C JSON the zone's `/ws` reads/writes. We publish the
 * phone's C2S frames to `wyrd.tunnel.{zone}.{session}.up` and subscribe the
 * zone's S2C frames on `...down`. The relay only shuffles bytes; the zone
 * tunnels them into its own session server (see TunnelSessionHandler).
 *
 * @param between connected cross-platform NATS pub/sub (NatsBetweenClient).
 * @param zoneId  the target zone label (from the invite / discover).
 * @param token   the session token from a prior mcp.login over the relay,
 *                used to auth the zone's loopback `/ws`. Null → guest.
 */
class RelayTunnelServerConnection(
    private val between: BetweenClient,
    private val zoneId: String,
    private val token: String?,
    private val sessionId: String = newSessionId(),
) : ServerConnection {

    private val base = "wyrd.tunnel.$zoneId.$sessionId"
    private val handlers = mutableListOf<(S2CMessage) -> Unit>()
    private var downUnsub: (() -> Unit)? = null
    private var opened = false

    override val isConnected: Boolean get() = between.isConnected && opened

    /**
     * Subscribe the downlink and announce the session. Call once after the
     * relay NATS connection is up. Idempotent.
     */
    fun open() {
        if (opened) return
        downUnsub = between.subscribe("$base.down") { _, data ->
            val msg = try {
                parseS2CMessage(data.decodeToString())
            } catch (e: Exception) {
                null
            }
            if (msg != null) for (h in handlers.toList()) h(msg)
        }
        val openPayload = WireJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject { if (!token.isNullOrBlank()) put("token", token) },
        )
        between.publish("$base.open", openPayload.encodeToByteArray())
        opened = true
    }

    override suspend fun send(message: C2SMessage) {
        if (!opened) open()
        between.publish("$base.up", message.toJson().encodeToByteArray())
    }

    override fun onMessage(handler: (S2CMessage) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun remoteRoomIds(): Set<String> = emptySet()

    /** End the tunneled session. */
    fun close() {
        if (opened) {
            try { between.publish("$base.close", ByteArray(0)) } catch (_: Exception) {}
        }
        downUnsub?.invoke()
        downUnsub = null
        handlers.clear()
        opened = false
    }

    companion object {
        /**
         * The session id is a CAPABILITY, not just a correlation key (audit F1
         * residual, 2026-07-25). Household phones share one relay NATS account,
         * and static NATS ACLs cannot express "only the sessions you own" — so
         * knowing a sibling's session id is enough to inject `.up` frames into
         * their session or read their `.down` stream. It must therefore be
         * unguessable: 128 bits from the platform CSPRNG, hex, no dots (the zone
         * splits the subject on the last dot). The old value — millis-hex plus
         * 32 bits of `kotlin.random.Random` — was both low-entropy and largely
         * predictable from the clock.
         */
        fun newSessionId(): String = secureRandomHex(16)
    }
}
