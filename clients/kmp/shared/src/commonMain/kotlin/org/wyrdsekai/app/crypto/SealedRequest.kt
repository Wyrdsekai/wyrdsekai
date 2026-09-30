package org.wyrdsekai.app.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.platform.secureRandomBytes

/**
 * Sealed requests (v2), W3: the phone's NATS
 * request/reply subjects (mcp.login, auth.*, study.journal, …) sealed to the
 * home's static key `zk`, so the relay only routes. Noise N shape: a fresh
 * ephemeral key and 16 random bytes `n` per request.
 *
 * dh = X25519(e_p, zk); prk = HKDF-Extract(salt = n, dh);
 * okm = HKDF-Expand(prk, "wyrd-request-v2" || e_p.pub, 64) → k_req || k_rep.
 * Request: ChaCha20-Poly1305(k_req, 12 zero bytes, aad = subject,
 * {"ts":<ms>,"body":<old request>}), sent as {"v":2,"e","n","c"}.
 * Reply: ChaCha20-Poly1305(k_rep, zero nonce, aad = subject, reply JSON) as
 * {"v":2,"c"}. Each key seals exactly one message. Checked against
 * request-v2-vectors.json.
 */
object SealedRequest {
    private val INFO = "wyrd-request-v2".encodeToByteArray()
    private val ZERO_NONCE = ByteArray(12)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One sealed request in flight: its wire body, and the key its reply must be sealed with. */
    class Sealed internal constructor(val subject: String, val wire: String, private val kRep: ByteArray) {
        /**
         * The reply's plaintext JSON. A refusal the home sends in the clear
         * (`{"ok":false,"error":"sealed_refused"|"sealed_required"}`) is passed
         * through as it is. Any other unsealed reply, or a sealed one that does
         * not verify, throws: only the home can seal a reply to this request.
         */
        fun openReply(replyWire: String): String {
            val obj = try {
                json.parseToJsonElement(replyWire).jsonObject
            } catch (_: Exception) {
                throw SealedException("reply is not JSON")
            }
            if (obj["v"]?.jsonPrimitive?.contentOrNull == "2") {
                val c = b64urlDecode(obj["c"]?.jsonPrimitive?.contentOrNull)
                    ?: throw SealedException("sealed reply has no body")
                return SealCrypto.chaCha20Poly1305Open(kRep, ZERO_NONCE, subject.encodeToByteArray(), c).decodeToString()
            }
            if (isClearRefusal(obj)) return replyWire
            throw SealedException("the reply was not sealed by the home")
        }
    }

    /** A clear refusal of a sealed request: ok false and one of the two sealed_* codes, nothing else. */
    fun isClearRefusal(obj: JsonObject): Boolean {
        val ok = obj["ok"] as? JsonPrimitive
        val err = obj["error"]?.jsonPrimitive?.contentOrNull
        return ok != null && ok.contentOrNull == "false" && (err == "sealed_refused" || err == "sealed_required")
    }

    /** Seals [bodyJson] (the old plaintext request object, compact JSON) for [subject]. */
    fun seal(
        zoneKey: ByteArray,
        subject: String,
        bodyJson: String,
        nowMs: Long,
        ephemeral: X25519KeyPair = X25519KeyPair.generate(),
        n: ByteArray = secureRandomBytes(16),
    ): Sealed {
        if (zoneKey.size != X25519_KEY_LEN) throw SealedException("the home's key is not 32 bytes")
        val keys = keys(ephemeral.priv, ephemeral.pub, zoneKey, n)
        val plain = "{\"ts\":$nowMs,\"body\":$bodyJson}".encodeToByteArray()
        val c = SealCrypto.chaCha20Poly1305Seal(keys.first, ZERO_NONCE, subject.encodeToByteArray(), plain)
        val wire = "{\"v\":2,\"e\":\"${b64url(ephemeral.pub)}\",\"n\":\"${b64url(n)}\",\"c\":\"${b64url(c)}\"}"
        return Sealed(subject, wire, keys.second)
    }

    /** k_req to k_rep. */
    internal fun keys(ephemeralPriv: ByteArray, ephemeralPub: ByteArray, zoneKey: ByteArray, n: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hkdfExtract(n, SealCrypto.x25519(ephemeralPriv, zoneKey))
        val okm = hkdfExpand(prk, INFO + ephemeralPub, 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    internal fun prk(ephemeralPriv: ByteArray, zoneKey: ByteArray, n: ByteArray): ByteArray =
        hkdfExtract(n, SealCrypto.x25519(ephemeralPriv, zoneKey))
}
