package org.wyrdsekai.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519

actual object SealCrypto {
    actual val available: Boolean = true

    actual fun x25519PublicKey(priv: ByteArray): ByteArray {
        requireKey(priv)
        val pub = ByteArray(X25519_KEY_LEN)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return pub
    }

    actual fun x25519(priv: ByteArray, pub: ByteArray): ByteArray {
        requireKey(priv)
        requireKey(pub)
        val shared = ByteArray(X25519_KEY_LEN)
        if (!X25519.calculateAgreement(priv, 0, pub, 0, shared, 0)) {
            throw SealedException("X25519 result is all zero (low-order public key)")
        }
        return shared
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    actual fun chaCha20Poly1305Seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        run(true, key, nonce, aad, plaintext)

    actual fun chaCha20Poly1305Open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray =
        try {
            run(false, key, nonce, aad, sealed)
        } catch (_: InvalidCipherTextException) {
            throw SealedException("sealed data did not verify")
        }

    private fun run(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        if (key.size != 32 || nonce.size != 12) throw SealedException("ChaCha20-Poly1305 takes a 32-byte key and a 12-byte nonce")
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(input.size))
        var n = c.processBytes(input, 0, input.size, out, 0)
        n += c.doFinal(out, n)
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun requireKey(k: ByteArray) {
        if (k.size != X25519_KEY_LEN) throw SealedException("X25519 keys are 32 bytes")
    }
}
