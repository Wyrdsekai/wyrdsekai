package org.wyrdsekai.app.crypto

import kotlin.io.encoding.Base64
import org.wyrdsekai.app.platform.secureRandomBytes

/** A sealed frame or request could not be made or opened. Never carries key material. */
class SealedException(message: String) : Exception(message)

/**
 * The three primitives the sealed tunnel and sealed requests are built from
 * ( W3): X25519, HMAC-SHA256 (for HKDF) and
 * ChaCha20-Poly1305. Android and desktop share one implementation
 * (jvmSharedMain, BouncyCastle); a target without it reports [available] false
 * and every caller refuses to connect instead of falling back to plaintext.
 */
expect object SealCrypto {
    val available: Boolean

    /** The public key for a 32-byte X25519 private key (RFC 7748 encoding). */
    fun x25519PublicKey(priv: ByteArray): ByteArray

    /** X25519(priv, pub). Throws [SealedException] on an all-zero result (a low-order public key). */
    fun x25519(priv: ByteArray, pub: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** ciphertext || tag(16). */
    fun chaCha20Poly1305Seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** Throws [SealedException] when the tag does not verify. */
    fun chaCha20Poly1305Open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray
}

const val X25519_KEY_LEN = 32

/** An X25519 key pair as raw 32-byte values. */
class X25519KeyPair(val priv: ByteArray, val pub: ByteArray) {
    companion object {
        fun generate(): X25519KeyPair {
            val priv = secureRandomBytes(X25519_KEY_LEN)
            return X25519KeyPair(priv, SealCrypto.x25519PublicKey(priv))
        }
    }
}

internal fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
    SealCrypto.hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)

internal fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    val out = ByteArray(length)
    var t = ByteArray(0)
    var pos = 0
    var i = 1
    while (pos < length) {
        t = SealCrypto.hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
        val n = minOf(t.size, length - pos)
        t.copyInto(out, pos, 0, n)
        pos += n
        i++
    }
    return out
}

private val B64URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private val B64URL_LENIENT = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** base64url without padding, as keys and sealed bodies travel. */
fun b64url(bytes: ByteArray): String = B64URL.encode(bytes)

/** Reads base64url or standard base64, with or without padding; null when it is not base64. */
fun b64urlDecode(s: String?): ByteArray? {
    if (s == null) return null
    val t = s.trim().replace('+', '-').replace('/', '_')
    return try {
        B64URL_LENIENT.decode(t)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** The home's public tunnel key `zk` from an invite, or null when it is missing or not 32 bytes. */
fun decodeZoneKey(zk: String?): ByteArray? =
    b64urlDecode(zk)?.takeIf { it.size == X25519_KEY_LEN }
