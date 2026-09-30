package org.wyrdsekai.app.crypto

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The phone reproduces the sealed tunnel and sealed request interop vectors
 * (tunnel-v2-vectors.json and request-v2-vectors.json from core/src/test/resources/crypto, copied here) byte for
 * byte, the same files the home's Java tests check. W3.
 */
class SealedVectorsTest {

    /** From the test classpath (desktop), else from the source tree (Android unit tests run in the module dir). */
    private fun vectors(name: String): JsonObject {
        val text = SealedVectorsTest::class.java.classLoader?.getResource("crypto/$name")?.readText()
            ?: File("src/jvmSharedTest/resources/crypto/$name").readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.hex(k: String): ByteArray = unhex(this[k]!!.jsonPrimitive.content)
    private fun JsonObject.str(k: String): String = this[k]!!.jsonPrimitive.content

    // ── tunnel ──

    @Test
    fun tunnelKeysAndFirstFramesMatchTheVectorsByteForByte() {
        val v = vectors("tunnel-v2-vectors.json")
        val phone = X25519KeyPair(v.hex("phone_ephemeral_priv"), v.hex("phone_ephemeral_pub"))
        assertContentEquals(v.hex("zone_static_pub"), SealCrypto.x25519PublicKey(v.hex("zone_static_priv")))
        assertContentEquals(v.hex("phone_ephemeral_pub"), SealCrypto.x25519PublicKey(phone.priv))
        assertContentEquals(v.hex("zone_ephemeral_pub"), SealCrypto.x25519PublicKey(v.hex("zone_ephemeral_priv")))

        val dhStatic = SealCrypto.x25519(phone.priv, v.hex("zone_static_pub"))
        val dhEphemeral = SealCrypto.x25519(phone.priv, v.hex("zone_ephemeral_pub"))
        assertContentEquals(v.hex("dh_static"), dhStatic)
        assertContentEquals(v.hex("dh_ephemeral"), dhEphemeral)

        val (kUp, kDown) = SealedTunnel.derive(dhStatic, dhEphemeral, v.str("session"), phone.pub, v.hex("zone_ephemeral_pub"))
        assertContentEquals(v.hex("k_up"), kUp)
        assertContentEquals(v.hex("k_down"), kDown)

        val keys = SealedTunnel.complete(phone, v.hex("zone_static_pub"), v.hex("zone_ephemeral_pub"), v.str("session"))
        val up = keys.up.seal(v.str("up_plain").encodeToByteArray(), v.str("up_subject").encodeToByteArray())
        assertContentEquals(v.hex("up_sealed"), up)
        val down = keys.down.open(v.hex("down_sealed"), v.str("down_subject").encodeToByteArray())
        assertEquals(v.str("down_plain"), down.decodeToString())
    }

    @Test
    fun theHomeSideOfTheVectorOpensWhatThePhoneSealed() {
        val v = vectors("tunnel-v2-vectors.json")
        val home = TestHome.accept(
            X25519KeyPair(v.hex("zone_static_priv"), v.hex("zone_static_pub")),
            X25519KeyPair(v.hex("zone_ephemeral_priv"), v.hex("zone_ephemeral_pub")),
            v.hex("phone_ephemeral_pub"), v.str("session"),
        )
        assertEquals(v.str("up_plain"), home.up.open(v.hex("up_sealed"), v.str("up_subject").encodeToByteArray()).decodeToString())
        assertContentEquals(v.hex("down_sealed"), home.down.seal(v.str("down_plain").encodeToByteArray(), v.str("down_subject").encodeToByteArray()))
    }

    @Test
    fun theOpenPayloadCarriesOnlyTheEphemeralKey() {
        val v = vectors("tunnel-v2-vectors.json")
        assertEquals("{\"v\":2,\"e\":\"hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo\"}", SealedTunnel.openPayload(v.hex("phone_ephemeral_pub")))
    }

    // ── request ──

    @Test
    fun requestKeysCiphertextAndWireMatchTheVectorsByteForByte() {
        val v = vectors("request-v2-vectors.json")
        val phone = X25519KeyPair(v.hex("phone_ephemeral_priv"), v.hex("phone_ephemeral_pub"))
        val zk = v.hex("zone_static_pub")
        val n = v.hex("n")
        assertContentEquals(v.hex("dh"), SealCrypto.x25519(phone.priv, zk))
        assertContentEquals(v.hex("prk"), SealedRequest.prk(phone.priv, zk, n))
        val (kReq, kRep) = SealedRequest.keys(phone.priv, phone.pub, zk, n)
        assertContentEquals(v.hex("k_req"), kReq)
        assertContentEquals(v.hex("k_rep"), kRep)

        val plain = v.str("request_plain")
        val body = plain.substringAfter(",\"body\":").dropLast(1)
        val sealed = SealedRequest.seal(zk, v.str("subject"), body, 1790000000000L, phone, n)
        assertEquals(v.str("request_wire"), sealed.wire)
        val c = b64urlDecode(Json.parseToJsonElement(sealed.wire).jsonObject.str("c"))!!
        assertContentEquals(v.hex("request_ct"), c)

        assertEquals(v.str("reply_plain"), sealed.openReply(v.str("reply_wire")))
    }

    @Test
    fun theHomeSideOfTheVectorOpensTheRequestAndSealsTheReply() {
        val v = vectors("request-v2-vectors.json")
        val opened = TestHome.openRequest(v.hex("zone_static_priv"), v.str("subject"), v.str("request_wire"))
        assertEquals(v.str("request_plain"), opened.plain)
        assertEquals(v.str("reply_wire"), opened.sealReply(v.str("reply_plain")))
    }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun aMissingZoneKeyIsRefusedBeforeAnythingIsSent() {
        assertFailsWith<SealedException> { SealedRequest.seal(ByteArray(31), "s", "{}", 0) }
    }
}
