package org.wyrdsekai.app.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.engine.between.BetweenClient

/**
 * The home's side of the sealed tunnel and sealed requests, for tests only —
 * the same derivations as the home's Java SealedTunnel / sealed request handler.
 */
object TestHome {
    private val ZERO_NONCE = ByteArray(12)

    fun accept(zoneStatic: X25519KeyPair, zoneEphemeral: X25519KeyPair, phonePub: ByteArray, session: String): SealedTunnel.Keys {
        val (up, down) = SealedTunnel.derive(
            SealCrypto.x25519(zoneStatic.priv, phonePub), SealCrypto.x25519(zoneEphemeral.priv, phonePub),
            session, phonePub, zoneEphemeral.pub,
        )
        return SealedTunnel.Keys(SealedTunnel.Channel(up), SealedTunnel.Channel(down))
    }

    class OpenedRequest(val plain: String, private val kRep: ByteArray, private val subject: String) {
        fun sealReply(replyJson: String, aadSubject: String = subject): String {
            val c = SealCrypto.chaCha20Poly1305Seal(kRep, ZERO_NONCE, aadSubject.encodeToByteArray(), replyJson.encodeToByteArray())
            return "{\"v\":2,\"c\":\"${b64url(c)}\"}"
        }
    }

    fun openRequest(zonePriv: ByteArray, subject: String, wire: String): OpenedRequest {
        val o = Json.parseToJsonElement(wire).jsonObject
        val e = b64urlDecode(o["e"]!!.jsonPrimitive.content)!!
        val n = b64urlDecode(o["n"]!!.jsonPrimitive.content)!!
        val c = b64urlDecode(o["c"]!!.jsonPrimitive.content)!!
        val prk = hkdfExtract(n, SealCrypto.x25519(zonePriv, e))
        val okm = hkdfExpand(prk, "wyrd-request-v2".encodeToByteArray() + e, 64)
        val plain = SealCrypto.chaCha20Poly1305Open(okm.copyOfRange(0, 32), ZERO_NONCE, subject.encodeToByteArray(), c)
        return OpenedRequest(plain.decodeToString(), okm.copyOfRange(32, 64), subject)
    }
}

/**
 * A home answering sealed tunnels on an in-memory bus, the way the home's
 * TunnelSessionHandler does: `.open` {"v":2,"e"} → `.down` {"v":2,"e"}; then
 * every `.up` / `.down` frame sealed. Records what it opened.
 */
class FakeSealedHome(
    private val between: BetweenClient,
    private val zoneId: String,
    val zoneStatic: X25519KeyPair = X25519KeyPair.generate(),
) {
    val sessions = mutableMapOf<String, SealedTunnel.Keys>()
    /** Per session: the first sealed frame up (the old open payload), then every later frame. */
    val received = mutableMapOf<String, MutableList<String>>()
    val openPayloads = mutableListOf<String>()
    var refuseWith: String? = null
    /** Frames up that did not open. */
    var rejected = 0

    fun start(): FakeSealedHome {
        between.subscribe("wyrd.tunnel.$zoneId.*.open") { subject, data ->
            val session = subject.removePrefix("wyrd.tunnel.$zoneId.").removeSuffix(".open")
            val text = data.decodeToString()
            openPayloads += text
            val down = "wyrd.tunnel.$zoneId.$session.down"
            refuseWith?.let {
                between.publish(down, "{\"type\":\"error\",\"seq\":0,\"code\":\"$it\",\"message\":\"refused\"}".encodeToByteArray())
                return@subscribe
            }
            val e = Json.parseToJsonElement(text).jsonObject["e"]?.jsonPrimitive?.contentOrNull ?: return@subscribe
            val eph = X25519KeyPair.generate()
            sessions[session] = TestHome.accept(zoneStatic, eph, b64urlDecode(e)!!, session)
            between.publish(down, "{\"v\":2,\"e\":\"${b64url(eph.pub)}\"}".encodeToByteArray())
        }
        between.subscribe("wyrd.tunnel.$zoneId.*.up") { subject, data ->
            val session = subject.removePrefix("wyrd.tunnel.$zoneId.").removeSuffix(".up")
            val keys = sessions[session] ?: return@subscribe
            val plain = try {
                keys.up.open(data, subject.encodeToByteArray()).decodeToString()
            } catch (_: SealedException) {
                rejected++
                return@subscribe
            }
            received.getOrPut(session) { mutableListOf() } += plain
        }
        return this
    }

    fun sendDown(session: String, text: String) {
        val subject = "wyrd.tunnel.$zoneId.$session.down"
        between.publish(subject, sessions[session]!!.down.seal(text.encodeToByteArray(), subject.encodeToByteArray()))
    }
}
