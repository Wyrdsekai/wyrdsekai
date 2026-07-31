package org.wyrdsekai.app.engine.between

/**
 * Platform-independent crypto operations for Between communication.
 * Implemented per-platform: JDK Ed25519 (Android/Desktop), SecKey (iOS).
 */
interface CryptoProvider {
    /** Generate a new Ed25519 keypair. Returns (publicKey, privateKey). */
    fun generateKeyPair(): Pair<ByteArray, ByteArray>

    /** Sign data with a private key. Returns Ed25519 signature (64 bytes). */
    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray

    /** Verify a signature against a public key. */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}

/**
 * Node identity — Ed25519 keypair for Between authentication.
 * Generated on first launch, stored encrypted in platform keystore.
 *
 * and server's NodeIdentity.java.
 */
class NodeIdentity(
    val nodeId: String,
    private val privateKey: ByteArray,
    val publicKey: ByteArray,
    private val crypto: CryptoProvider,
) {
    /** Sign data with this node's private key. */
    fun sign(data: ByteArray): ByteArray = crypto.sign(privateKey, data)

    /** Verify a signature against a peer's public key. */
    fun verify(peerPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        crypto.verify(peerPublicKey, data, signature)

    /** Public key as base64 string. */
    fun publicKeyBase64(): String = publicKey.encodeBase64()

    companion object {
        /** Generate a new identity with a random node ID. */
        fun generate(crypto: CryptoProvider): NodeIdentity {
            val nodeId = generateNodeId()
            val (publicKey, privateKey) = crypto.generateKeyPair()
            return NodeIdentity(nodeId, privateKey, publicKey, crypto)
        }

        private fun generateNodeId(): String {
            // Simple random UUID-like ID
            val chars = "0123456789abcdef"
            val random = kotlin.random.Random
            return buildString {
                repeat(8) { append(chars[random.nextInt(chars.length)]) }
                append('-')
                repeat(4) { append(chars[random.nextInt(chars.length)]) }
                append('-')
                repeat(4) { append(chars[random.nextInt(chars.length)]) }
                append('-')
                repeat(12) { append(chars[random.nextInt(chars.length)]) }
            }
        }
    }
}

/**
 * Test-only crypto provider using HMAC-SHA256 as a stand-in for Ed25519.
 * Real implementations use platform Ed25519 (JDK / SecKey / Web Crypto).
 */
class TestCryptoProvider : CryptoProvider {
    override fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        // Fake 32-byte keys for testing
        val random = kotlin.random.Random
        val publicKey = ByteArray(32) { random.nextInt(256).toByte() }
        val privateKey = ByteArray(32) { random.nextInt(256).toByte() }
        return publicKey to privateKey
    }

    override fun sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        // Simple XOR-based "signature" for testing only
        val sig = ByteArray(64)
        for (i in sig.indices) {
            sig[i] = (data[i % data.size].toInt() xor privateKey[i % privateKey.size].toInt()).toByte()
        }
        return sig
    }

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        // Test verification: just check signature length
        return signature.size == 64
    }
}
