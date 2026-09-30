package org.wyrdsekai.app.crypto

/**
 * The iOS app is the React Native client (clients/rn); this KMP iOS target is
 * not shipped and has no sealed-tunnel crypto. [available] is false, so every
 * relay and home connection on it refuses instead of sending plaintext.
 */
actual object SealCrypto {
    actual val available: Boolean = false

    actual fun x25519PublicKey(priv: ByteArray): ByteArray = unavailable()
    actual fun x25519(priv: ByteArray, pub: ByteArray): ByteArray = unavailable()
    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = unavailable()
    actual fun chaCha20Poly1305Seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = unavailable()
    actual fun chaCha20Poly1305Open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray = unavailable()

    private fun unavailable(): Nothing =
        throw SealedException("encrypted connections are not built into this iOS target; use the Wyrdsekai iOS app")
}
