package org.wyrdsekai.app.engine.between

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Wire format for all Between messages.
 * Signed with Ed25519 — signature covers src:dst:ts:payload.
 *
 * Mirrors server's BetweenEnvelope.java.
 */
@Serializable
data class BetweenEnvelope(
    val v: Int = 1,
    val src: String,
    val dst: String? = null,
    val ts: Long,
    val sig: String,
    val payload: JsonElement,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Build the data that gets signed: "src:dst:ts:payload". */
        fun signingData(src: String, dst: String?, ts: Long, payload: JsonElement): ByteArray {
            val dstStr = dst ?: "*"
            val payloadStr = json.encodeToString(payload)
            return "$src:$dstStr:$ts:$payloadStr".encodeToByteArray()
        }

        /** Create and sign an envelope. */
        fun create(
            src: String,
            dst: String?,
            payload: JsonElement,
            identity: NodeIdentity,
        ): BetweenEnvelope {
            val ts = currentTimeMillis()
            val data = signingData(src, dst, ts, payload)
            val sig = identity.sign(data).encodeBase64()
            return BetweenEnvelope(v = 1, src = src, dst = dst, ts = ts, sig = sig, payload = payload)
        }

        /** Deserialize from JSON bytes. */
        fun fromBytes(data: ByteArray): BetweenEnvelope =
            json.decodeFromString(data.decodeToString())

        /** Current time in epoch milliseconds. */
        private fun currentTimeMillis(): Long =
            kotlin.time.Clock.System.now().toEpochMilliseconds()
    }

    /** Verify this envelope's signature against a peer's public key. */
    fun verify(peerPublicKey: ByteArray, crypto: CryptoProvider): Boolean {
        val data = signingData(src, dst, ts, payload)
        val sigBytes = sig.decodeBase64()
        return crypto.verify(peerPublicKey, data, sigBytes)
    }

    /** Serialize to JSON bytes. */
    fun toBytes(): ByteArray = json.encodeToString(this).encodeToByteArray()
}

// Base64 helpers (Kotlin Multiplatform)
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun ByteArray.encodeBase64(): String =
    kotlin.io.encoding.Base64.encode(this)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun String.decodeBase64(): ByteArray =
    kotlin.io.encoding.Base64.decode(this)
