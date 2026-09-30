package org.wyrdsekai.app.crypto

/**
 * The phone's side of the sealed tunnel (v2), W3.
 * Byte-compatible with the home's core/.../crypto/SealedTunnel.java; both are
 * checked against tunnel-v2-vectors.json.
 *
 * The phone knows the home's static key `zk` from pairing. Per session it sends
 * an ephemeral key on `.open`; the home answers on `.down` with its own. Keys:
 * HKDF-SHA256(salt = session id, ikm = X25519(e_p, zk) || X25519(e_p, e_z),
 * info = "wyrd-tunnel-v2" || e_p.pub || e_z.pub) → k_up || k_down. Every later
 * frame is nonce(12) || ChaCha20-Poly1305 ciphertext || tag, nonce = 4 zero
 * bytes || 64-bit big-endian counter per direction, AAD = the NATS subject.
 */
object SealedTunnel {
    private val INFO = "wyrd-tunnel-v2".encodeToByteArray()
    private const val NONCE_LEN = 12
    private const val TAG_LEN = 16

    /** One direction of a session. Frames must arrive in order; a gap or a replay throws. */
    class Channel internal constructor(private val key: ByteArray) {
        var counter: Long = 0
            private set

        fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val nonce = nonce(counter)
            val sealed = SealCrypto.chaCha20Poly1305Seal(key, nonce, aad, plaintext)
            counter++
            return nonce + sealed
        }

        fun open(frame: ByteArray, aad: ByteArray): ByteArray {
            if (frame.size < NONCE_LEN + TAG_LEN) throw SealedException("sealed frame too short")
            val nonce = frame.copyOfRange(0, NONCE_LEN)
            if (!nonce.contentEquals(nonce(counter))) {
                throw SealedException("sealed frame out of order (expected frame $counter)")
            }
            val plain = SealCrypto.chaCha20Poly1305Open(key, nonce, aad, frame.copyOfRange(NONCE_LEN, frame.size))
            counter++
            return plain
        }
    }

    class Keys(val up: Channel, val down: Channel)

    /** What `.open` carries: `{"v":2,"e":<the phone's ephemeral public key>}`. No token in the clear. */
    fun openPayload(phoneEphemeralPub: ByteArray): String = "{\"v\":2,\"e\":\"${b64url(phoneEphemeralPub)}\"}"

    /** The phone's keys once the home's ephemeral key has arrived. */
    fun complete(
        phoneEphemeral: X25519KeyPair,
        zoneStaticPub: ByteArray,
        zoneEphemeralPub: ByteArray,
        sessionId: String,
    ): Keys {
        val k = derive(
            SealCrypto.x25519(phoneEphemeral.priv, zoneStaticPub),
            SealCrypto.x25519(phoneEphemeral.priv, zoneEphemeralPub),
            sessionId, phoneEphemeral.pub, zoneEphemeralPub,
        )
        return Keys(Channel(k.first), Channel(k.second))
    }

    /** k_up to k_down. */
    internal fun derive(
        dhStatic: ByteArray,
        dhEphemeral: ByteArray,
        sessionId: String,
        phoneEphemeralPub: ByteArray,
        zoneEphemeralPub: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val prk = hkdfExtract(sessionId.encodeToByteArray(), dhStatic + dhEphemeral)
        val okm = hkdfExpand(prk, INFO + phoneEphemeralPub + zoneEphemeralPub, 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    internal fun nonce(counter: Long): ByteArray {
        val n = ByteArray(NONCE_LEN)
        for (i in 0 until 8) n[NONCE_LEN - 1 - i] = (counter ushr (8 * i)).toByte()
        return n
    }
}
