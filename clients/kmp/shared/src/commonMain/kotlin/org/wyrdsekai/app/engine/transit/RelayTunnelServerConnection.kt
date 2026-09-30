package org.wyrdsekai.app.engine.transit

import kotlinx.serialization.json.JsonObject
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
 * Wire: the same C2S/S2C JSON the zone's `/ws` reads/writes, sealed end to end
 * to the home's tunnel key ( W3, [SealedTunnelPipe]).
 * The relay only routes; the zone opens the frames and tunnels them into its
 * own session server (see TunnelSessionHandler).
 *
 * @param between connected cross-platform NATS pub/sub (NatsBetweenClient).
 * @param zoneId  the target zone label (from the invite / discover).
 * @param token   the session token from a prior mcp.login over the relay,
 *                used to auth the zone's loopback `/ws`. Null → guest. Sent
 *                only inside the sealed session.
 * @param zoneKey the home's public tunnel key `zk` from the pairing invite.
 *                Null (paired before 0.5.0) → the session refuses to open.
 */
class RelayTunnelServerConnection(
    private val between: BetweenClient,
    zoneId: String,
    token: String?,
    zoneKey: ByteArray?,
    sessionId: String = newSessionId(),
) : ServerConnection {

    private val handlers = mutableListOf<(S2CMessage) -> Unit>()
    private val pipe = SealedTunnelPipe(
        between = between,
        zoneId = zoneId,
        sessionId = sessionId,
        zoneKey = zoneKey,
        openBody = WireJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { if (!token.isNullOrBlank()) put("token", token) },
        ),
        onFrame = { text ->
            val msg = try {
                parseS2CMessage(text)
            } catch (_: Exception) {
                null
            }
            if (msg != null) deliver(msg)
        },
        onFailure = { code, message -> deliver(S2CMessage.Error(0, code, message)) },
    )

    override val isConnected: Boolean get() = between.isConnected && pipe.isOpen

    /**
     * Announce the session (sealed handshake). Call once after the relay NATS
     * connection is up. Idempotent.
     */
    fun open() {
        pipe.open()
    }

    override suspend fun send(message: C2SMessage) {
        open()
        pipe.send(message.toJson())
    }

    override fun onMessage(handler: (S2CMessage) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun remoteRoomIds(): Set<String> = emptySet()

    /** End the tunneled session. */
    fun close() {
        pipe.close()
        handlers.clear()
    }

    private fun deliver(msg: S2CMessage) {
        for (h in handlers.toList()) h(msg)
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
