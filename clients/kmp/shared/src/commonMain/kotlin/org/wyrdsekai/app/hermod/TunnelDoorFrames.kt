package org.wyrdsekai.app.hermod

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.platform.secureRandomHex
import org.wyrdsekai.app.protocol.WireJson

/**
 * The relay leg of the phone's hermod door: the SAME dumb tunnel pipe a
 * remote session rides (wyrd.tunnel.{zone}.{session}.{open,up,down,close}),
 * except the open payload selects the hermod door and the frames are
 * PhoneDoorWire JSON instead of C2S/S2C. The zone's TunnelSessionHandler
 * loopbacks it into /ws/hermod — so a relay phone arrives at the very
 * same PhoneDoorProxy a LAN phone does, and roaming is just a channel
 * supersede on the zone.
 */
class TunnelDoorFrames(
    private val between: BetweenClient,
    zoneId: String,
    private val deviceToken: String,
    sessionId: String = secureRandomHex(16),
) {
    private val base = "wyrd.tunnel.$zoneId.$sessionId"

    /** Down-frames, completed (closed) when the tunnel reports an error. */
    val inbound = Channel<String>(64)
    private var unsub: (() -> Unit)? = null

    fun open() {
        unsub = between.subscribe("$base.down") { _, data ->
            val frame = data.decodeToString()
            // Tunnel-level error frames (tunnel_auth / tunnel_busy /
            // tunnel_connect_failed / tunnel_closed) end the session —
            // they are transport truth, not door protocol.
            if (frame.contains("\"type\":\"error\"")) {
                inbound.close()
            } else {
                inbound.trySend(frame)
            }
        }
        val payload = WireJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("door", "hermod")
                put("deviceToken", deviceToken)
            },
        )
        between.publish("$base.open", payload.encodeToByteArray())
    }

    fun send(frame: String) {
        between.publish("$base.up", frame.encodeToByteArray())
    }

    fun close() {
        runCatching { between.publish("$base.close", ByteArray(0)) }
        unsub?.invoke()
        unsub = null
        inbound.close()
    }
}
