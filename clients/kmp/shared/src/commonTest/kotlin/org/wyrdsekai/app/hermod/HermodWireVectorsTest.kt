package org.wyrdsekai.app.hermod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language wire vectors for the zone⇄phone hermod leg. These are
 * the SAME literal JSON strings as the server module's
 * ThePhoneWireSpeaksOneTongueTest.java — both codecs must read them,
 * and each side's writes must be readable by the other. Field ORDER is
 * free; names and types (ISO-8601 instants, base64 byte arrays) are the
 * contract. Change a vector here and you must change it there in the
 * same commit.
 */
class HermodWireVectorsTest {

    // ── VECTOR 1: knock with a grant-free envelope ──────────────────
    private val knockPlain = """
        {"type":"knock","knockId":"phone-7/k1","envelope":{
          "envelopeId":"env-1","householdId":"hh1","originDeviceId":"origin-node",
          "taskType":"inference.chat","dataDomain":"none","capabilityClass":"llm.phone-npu",
          "params":{"prompt":"hi"},"tokenBudget":256,
          "issuedAt":"2026-08-14T12:00:00Z","expiresAt":"2026-08-14T12:01:00Z",
          "originSignature":"AQ=="}}"""

    // ── VECTOR 2: knock whose envelope carries a signed grant ───────
    private val knockGranted = """
        {"type":"knock","knockId":"phone-7/k2","envelope":{
          "envelopeId":"env-2","householdId":"hh1","originDeviceId":"origin-node",
          "taskType":"inference.chat","dataDomain":"photos","capabilityClass":"llm.phone-npu",
          "params":{},"tokenBudget":512,
          "issuedAt":"2026-08-14T12:00:00Z","expiresAt":"2026-08-14T12:01:00Z",
          "grant":{"grantId":"g-1","householdId":"hh1","dataDomain":"photos",
            "grantedToDeviceClass":"llm.phone-npu",
            "issuedAt":"2026-08-14T00:00:00Z","expiresAt":"2026-08-21T00:00:00Z",
            "policyVersion":"v1","authoritySignature":"AgM="},
          "originSignature":"AQ=="}}"""

    // ── VECTOR 3: completed answer ──────────────────────────────────
    private val answerCompleted = """
        {"type":"answer","knockId":"phone-7/k1",
         "answer":{"completed":true,"ok":true,"output":"the drafted line"}}"""

    // ── VECTOR 4: declined answer ───────────────────────────────────
    private val answerDeclined = """
        {"type":"answer","knockId":"phone-7/k2",
         "answer":{"completed":false,"ok":false,"declineReason":"not charging"}}"""

    // ── VECTOR 5: heartbeat (no identity fields BY DESIGN) ──────────
    private val heartbeat = """
        {"type":"heartbeat","capabilityClass":"llm.phone-npu","models":["lfm2-8b-a1b"],
         "residentDataDomains":["photos"],"charging":true,"idle":true,"loadFactor":0.1}"""

    // ── VECTOR 6: hello ─────────────────────────────────────────────
    private val hello = """
        {"type":"hello","deviceId":"phone-7","householdId":"hh1"}"""

    @Test
    fun plainKnockDecodes() {
        val knock = assertIs<HermodMessage.Knock>(decodeHermod(knockPlain))
        assertEquals("phone-7/k1", knock.knockId)
        val e = knock.envelope
        assertEquals("env-1", e.envelopeId)
        assertEquals("inference.chat", e.taskType)
        assertEquals("2026-08-14T12:00:00Z", e.issuedAt)
        assertEquals("hi", e.params["prompt"])
        assertEquals("AQ==", e.originSignature)
        assertNull(e.grant)
    }

    @Test
    fun grantedKnockDecodes() {
        val knock = assertIs<HermodMessage.Knock>(decodeHermod(knockGranted))
        val g = assertNotNull(knock.envelope.grant)
        assertEquals("g-1", g.grantId)
        assertEquals("photos", g.dataDomain)
        assertEquals("AgM=", g.authoritySignature)
    }

    @Test
    fun answersDecode() {
        val done = assertIs<HermodMessage.Answer>(decodeHermod(answerCompleted))
        assertEquals("phone-7/k1", done.knockId)
        assertTrue(done.answer.completed)
        assertEquals("the drafted line", done.answer.output)

        val declined = assertIs<HermodMessage.Answer>(decodeHermod(answerDeclined))
        assertFalse(declined.answer.completed)
        assertEquals("not charging", declined.answer.declineReason)
    }

    @Test
    fun heartbeatAndHelloDecode() {
        val hb = assertIs<HermodMessage.Heartbeat>(decodeHermod(heartbeat))
        assertEquals("llm.phone-npu", hb.capabilityClass)
        assertTrue(hb.charging)
        assertEquals(0.1, hb.loadFactor, 1e-9)

        val h = assertIs<HermodMessage.Hello>(decodeHermod(hello))
        assertEquals("phone-7", h.deviceId)
        assertEquals("hh1", h.householdId)
    }

    @Test
    fun unknownTypesAndGarbageDecodeToNullNeverThrow() {
        assertNull(decodeHermod("""{"type":"future-thing","x":1}"""))
        assertNull(decodeHermod("not json at all"))
    }

    @Test
    fun ourWritesStayInsideTheContract() {
        val declined = encodeHermod(
            HermodMessage.Answer("phone-7/k9", AnswerBody.declined("not charging")))
        assertTrue(declined.contains("\"type\":\"answer\""), declined)
        assertTrue(declined.contains("\"declineReason\":\"not charging\""), declined)
        // Jackson NON_NULL parity: absent fields stay OFF the wire.
        assertFalse(declined.contains("\"output\""), declined)
        assertFalse(declined.contains("null"), declined)
        // And our own reader accepts our own writes.
        val back = assertIs<HermodMessage.Answer>(decodeHermod(declined))
        assertEquals("not charging", back.answer.declineReason)

        val beat = encodeHermod(HermodMessage.Heartbeat(
            capabilityClass = "llm.phone", charging = true, idle = false))
        assertTrue(beat.contains("\"type\":\"heartbeat\""), beat)
        // The heartbeat can never claim an identity — the zone stamps it.
        assertFalse(beat.contains("deviceId"), beat)
        assertFalse(beat.contains("householdId"), beat)
    }
}
