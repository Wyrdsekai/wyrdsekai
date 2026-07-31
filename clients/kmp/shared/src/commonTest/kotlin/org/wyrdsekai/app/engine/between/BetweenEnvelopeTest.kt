package org.wyrdsekai.app.engine.between

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BetweenEnvelopeTest {

    private val crypto = TestCryptoProvider()

    @Test
    fun signingDataFormatMatchesSpec() {
        val payload = buildJsonObject { put("type", "headline") }
        val data = BetweenEnvelope.signingData("node-1", "node-2", 1234567890L, payload)
        val str = data.decodeToString()
        // Format: src:dst:ts:payload
        assertTrue(str.startsWith("node-1:node-2:1234567890:"))
        assertTrue(str.contains("\"type\":\"headline\""))
    }

    @Test
    fun signingDataUsesAsterikForBroadcast() {
        val payload = JsonPrimitive("test")
        val data = BetweenEnvelope.signingData("node-1", null, 100L, payload)
        assertTrue(data.decodeToString().contains("node-1:*:100:"))
    }

    @Test
    fun createAndSerialize() {
        val identity = NodeIdentity.generate(crypto)
        val payload = buildJsonObject { put("type", "headline"); put("summary", "all is well") }

        val envelope = BetweenEnvelope.create("node-1", null, payload, identity)

        assertEquals(1, envelope.v)
        assertEquals("node-1", envelope.src)
        assertEquals(null, envelope.dst)
        assertTrue(envelope.sig.isNotEmpty())
        assertTrue(envelope.ts > 0)

        // Round-trip serialization
        val bytes = envelope.toBytes()
        val restored = BetweenEnvelope.fromBytes(bytes)
        assertEquals(envelope.src, restored.src)
        assertEquals(envelope.dst, restored.dst)
        assertEquals(envelope.ts, restored.ts)
        assertEquals(envelope.sig, restored.sig)
    }

    @Test
    fun verifyWithTestCrypto() {
        val identity = NodeIdentity.generate(crypto)
        val payload = buildJsonObject { put("test", true) }

        val envelope = BetweenEnvelope.create("node-1", "node-2", payload, identity)
        assertTrue(envelope.verify(identity.publicKey, crypto))
    }

    @Test
    fun envelopeVersionIsOne() {
        val identity = NodeIdentity.generate(crypto)
        val envelope = BetweenEnvelope.create("n1", null, JsonPrimitive("p"), identity)
        assertEquals(1, envelope.v)
    }
}
