package org.wyrdsekai.app.engine.transit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.crypto.SealCrypto
import org.wyrdsekai.app.crypto.SealedException
import org.wyrdsekai.app.crypto.SealedTunnel
import org.wyrdsekai.app.crypto.X25519KeyPair
import org.wyrdsekai.app.crypto.X25519_KEY_LEN
import org.wyrdsekai.app.crypto.b64urlDecode
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.i18n.currentUiStrings

/**
 * One sealed tunnel session over the relay ( W3):
 * `wyrd.tunnel.{zone}.{session}.{open,up,down,close}`, end-to-end encrypted to
 * the home's key [zoneKey] so the relay only routes.
 *
 * `.open` carries only the phone's ephemeral key. The home's first `.down`
 * frame carries its own; from then on every frame both ways is sealed, and the
 * first sealed frame up is what the plaintext open used to carry ([openBody]:
 * the session token or the hermod door). Frames [send] before the handshake
 * completes are held and sealed in order once it does.
 *
 * Without the home's key (a phone paired before 0.5.0) nothing is sent: the
 * pipe reports `tunnel_key_missing` and the person is told to pair again.
 */
class SealedTunnelPipe(
    private val between: BetweenClient,
    zoneId: String,
    private val sessionId: String,
    private val zoneKey: ByteArray?,
    private val openBody: String,
    /** A frame from the home, opened. */
    private val onFrame: (String) -> Unit,
    /** The session cannot go on: the home refused it, or a frame did not verify. */
    private val onFailure: (code: String, message: String) -> Unit,
) {
    private enum class State { NEW, OPENING, OPEN, CLOSED }

    private val base = "wyrd.tunnel.$zoneId.$sessionId"
    private val upSubject = "$base.up"
    private val downSubject = "$base.down"
    private var state = State.NEW
    private var ephemeral: X25519KeyPair? = null
    private var keys: SealedTunnel.Keys? = null
    private val held = mutableListOf<String>()
    private var unsubscribe: (() -> Unit)? = null
    /** Sealing and publishing happen under one lock, so frames leave in counter order. */
    private val lock = PipeLock()

    val isOpen: Boolean get() = state == State.OPENING || state == State.OPEN

    /** Announces the session. False (and [onFailure]) when it cannot be sealed. */
    fun open(): Boolean = lock.locked {
        if (state != State.NEW) return@locked isOpen
        if (!SealCrypto.available) {
            state = State.CLOSED
            onFailure("tunnel_unsupported", currentUiStrings().secUnsupportedBuild)
            return@locked false
        }
        if (zoneKey == null || zoneKey.size != X25519_KEY_LEN) {
            state = State.CLOSED
            onFailure("tunnel_key_missing", currentUiStrings().secRepairTunnel)
            return@locked false
        }
        val e = X25519KeyPair.generate()
        ephemeral = e
        state = State.OPENING
        unsubscribe = between.subscribe(downSubject) { _, data -> onDown(data) }
        between.publish("$base.open", SealedTunnel.openPayload(e.pub).encodeToByteArray())
        true
    }

    /** Seals and sends one frame up; held until the handshake completes. */
    fun send(text: String) = lock.locked {
        when (state) {
            State.OPEN -> sealUp(text)
            State.OPENING -> if (held.size < MAX_HELD) held += text
            State.NEW, State.CLOSED -> {}
        }
    }

    fun close() = lock.locked {
        if (isOpen) runCatching { between.publish("$base.close", ByteArray(0)) }
        finish()
    }

    private fun onDown(data: ByteArray) = lock.locked {
        when (state) {
            State.OPENING -> handshake(data)
            State.OPEN -> {
                val plain = try {
                    keys!!.down.open(data, downSubject.encodeToByteArray())
                } catch (_: SealedException) {
                    fail("tunnel_integrity", currentUiStrings().secTunnelBroken)
                    return@locked
                }
                onFrame(plain.decodeToString())
            }
            State.NEW, State.CLOSED -> {}
        }
    }

    private fun handshake(data: ByteArray) {
        val obj = try {
            Json.parseToJsonElement(data.decodeToString()).jsonObject
        } catch (_: Exception) {
            return
        }
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "error") {
            // Refusals before the handshake arrive in the clear (tunnel_busy,
            // tunnel_key_refused, tunnel_plaintext_refused); they carry no data.
            fail(obj["code"]?.jsonPrimitive?.contentOrNull ?: "tunnel_error",
                obj["message"]?.jsonPrimitive?.contentOrNull ?: currentUiStrings().secTunnelBroken)
            return
        }
        val zoneEphemeral = b64urlDecode(obj["e"]?.jsonPrimitive?.contentOrNull)
        if (obj["v"]?.jsonPrimitive?.contentOrNull != "2" || zoneEphemeral == null || zoneEphemeral.size != X25519_KEY_LEN) return
        keys = try {
            SealedTunnel.complete(ephemeral!!, zoneKey!!, zoneEphemeral, sessionId)
        } catch (_: SealedException) {
            fail("tunnel_key_refused", currentUiStrings().secTunnelBroken)
            return
        }
        ephemeral = null
        state = State.OPEN
        sealUp(openBody)
        for (t in held) sealUp(t)
        held.clear()
    }

    private fun sealUp(text: String) {
        between.publish(upSubject, keys!!.up.seal(text.encodeToByteArray(), upSubject.encodeToByteArray()))
    }

    private fun fail(code: String, message: String) {
        if (isOpen) runCatching { between.publish("$base.close", ByteArray(0)) }
        finish()
        onFailure(code, message)
    }

    private fun finish() {
        state = State.CLOSED
        unsubscribe?.invoke()
        unsubscribe = null
        held.clear()
        keys = null
        ephemeral = null
    }

    private companion object {
        /** The home buffers at most 64 frames per session before its loopback is up; so do we. */
        const val MAX_HELD = 64
    }
}

/** Mutual exclusion for [SealedTunnelPipe]: a reentrant monitor on the JVM targets. */
internal expect class PipeLock() {
    fun <T> locked(block: () -> T): T
}
