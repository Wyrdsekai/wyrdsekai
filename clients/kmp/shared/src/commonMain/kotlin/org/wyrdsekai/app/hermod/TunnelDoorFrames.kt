package org.wyrdsekai.app.hermod

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.engine.transit.SealedTunnelPipe
import org.wyrdsekai.app.platform.secureRandomHex
import org.wyrdsekai.app.protocol.WireJson

/**
 * The relay leg of the phone's hermod door: the SAME sealed tunnel a remote
 * session rides (wyrd.tunnel.{zone}.{session}.{open,up,down,close}, see
 * SealedTunnelPipe), except the first sealed frame selects the hermod door and
 * the frames are PhoneDoorWire JSON instead of C2S/S2C. The zone's
 * TunnelSessionHandler loopbacks it into /ws/hermod — so a relay phone arrives
 * at the very same PhoneDoorProxy a LAN phone does, and roaming is just a
 * channel supersede on the zone.
 */
class TunnelDoorFrames(
    between: BetweenClient,
    zoneId: String,
    deviceToken: String,
    zoneKey: ByteArray?,
    sessionId: String = secureRandomHex(16),
) {
    /** Down-frames, completed (closed) when the tunnel reports an error. */
    val inbound = Channel<String>(64)

    private val pipe = SealedTunnelPipe(
        between = between,
        zoneId = zoneId,
        sessionId = sessionId,
        zoneKey = zoneKey,
        openBody = WireJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("door", "hermod")
                put("deviceToken", deviceToken)
            },
        ),
        onFrame = { frame ->
            // Tunnel-level error frames (tunnel_auth / tunnel_busy /
            // tunnel_connect_failed / tunnel_closed) end the session —
            // they are transport truth, not door protocol.
            if (frame.contains("\"type\":\"error\"")) inbound.close() else inbound.trySend(frame)
        },
        onFailure = { _, _ -> inbound.close() },
    )

    fun open() {
        pipe.open()
    }

    fun send(frame: String) {
        pipe.send(frame)
    }

    fun close() {
        pipe.close()
        inbound.close()
    }
}
