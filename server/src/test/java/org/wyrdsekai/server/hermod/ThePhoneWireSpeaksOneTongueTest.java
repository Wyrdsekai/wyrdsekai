package org.wyrdsekai.server.hermod;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.Mesh;
import org.wyrdsekai.hermod.SignedGrant;
import org.wyrdsekai.hermod.TaskEnvelope;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-language wire vectors for the zone⇄phone hermod leg. The SAME
 * literal JSON strings live in the KMP commonTest
 * (HermodWireVectorsTest.kt) — both codecs must read these, and each
 * side's writes must be readable by the other. Field ORDER is free;
 * names, types (ISO-8601 instants, base64 byte arrays) are the contract.
 * Change a vector here and you must change it there in the same commit.
 */
class ThePhoneWireSpeaksOneTongueTest {

    // ── VECTOR 1: knock with a grant-free envelope ──────────────────
    static final String KNOCK_PLAIN = """
        {"type":"knock","knockId":"phone-7/k1","envelope":{
          "envelopeId":"env-1","householdId":"hh1","originDeviceId":"origin-node",
          "taskType":"inference.chat","dataDomain":"none","capabilityClass":"llm.phone-npu",
          "params":{"prompt":"hi"},"tokenBudget":256,
          "issuedAt":"2026-08-14T12:00:00Z","expiresAt":"2026-08-14T12:01:00Z",
          "originSignature":"AQ=="}}""";

    // ── VECTOR 2: knock whose envelope carries a signed grant ───────
    static final String KNOCK_GRANTED = """
        {"type":"knock","knockId":"phone-7/k2","envelope":{
          "envelopeId":"env-2","householdId":"hh1","originDeviceId":"origin-node",
          "taskType":"inference.chat","dataDomain":"photos","capabilityClass":"llm.phone-npu",
          "params":{},"tokenBudget":512,
          "issuedAt":"2026-08-14T12:00:00Z","expiresAt":"2026-08-14T12:01:00Z",
          "grant":{"grantId":"g-1","householdId":"hh1","dataDomain":"photos",
            "grantedToDeviceClass":"llm.phone-npu",
            "issuedAt":"2026-08-14T00:00:00Z","expiresAt":"2026-08-21T00:00:00Z",
            "policyVersion":"v1","authoritySignature":"AgM="},
          "originSignature":"AQ=="}}""";

    // ── VECTOR 3: completed answer ──────────────────────────────────
    static final String ANSWER_COMPLETED = """
        {"type":"answer","knockId":"phone-7/k1",
         "answer":{"completed":true,"ok":true,"output":"the drafted line"}}""";

    // ── VECTOR 4: declined answer ───────────────────────────────────
    static final String ANSWER_DECLINED = """
        {"type":"answer","knockId":"phone-7/k2",
         "answer":{"completed":false,"ok":false,"declineReason":"not charging"}}""";

    // ── VECTOR 5: heartbeat (no identity fields BY DESIGN) ──────────
    static final String HEARTBEAT = """
        {"type":"heartbeat","capabilityClass":"llm.phone-npu","models":["lfm2-8b-a1b"],
         "residentDataDomains":["photos"],"charging":true,"idle":true,"loadFactor":0.1}""";

    // ── VECTOR 6: hello ─────────────────────────────────────────────
    static final String HELLO = """
        {"type":"hello","deviceId":"phone-7","householdId":"hh1"}""";

    @Test
    void plainKnockDecodes() throws Exception {
        var knock = DoorWire.JSON.readValue(KNOCK_PLAIN, PhoneDoorWire.Knock.class);
        assertEquals("phone-7/k1", knock.knockId());
        var e = knock.envelope().toEnvelope();
        assertEquals("env-1", e.envelopeId());
        assertEquals(Instant.parse("2026-08-14T12:00:00Z"), e.issuedAt());
        assertEquals("hi", e.params().get("prompt"));
        assertArrayEquals(new byte[]{1}, e.originSignature());
        assertTrue(e.grant().isEmpty());
        assertFalse(e.requiresGrant());
    }

    @Test
    void grantedKnockDecodes() throws Exception {
        var knock = DoorWire.JSON.readValue(KNOCK_GRANTED, PhoneDoorWire.Knock.class);
        var e = knock.envelope().toEnvelope();
        assertTrue(e.requiresGrant());
        var g = e.grant().orElseThrow();
        assertEquals("g-1", g.grantId());
        assertEquals("photos", g.dataDomain());
        assertArrayEquals(new byte[]{2, 3}, g.authoritySignature());
    }

    @Test
    void answersDecode() throws Exception {
        var done = PhoneDoorWire.decodeAnswer(ANSWER_COMPLETED);
        assertEquals("phone-7/k1", done.knockId());
        var outcome = PhoneDoorWire.outcomeOf(done, "env-1");
        assertEquals("the drafted line",
            ((Mesh.DoorProtocol.Completed) outcome).result().output());

        var declined = PhoneDoorWire.decodeAnswer(ANSWER_DECLINED);
        assertEquals("not charging",
            ((Mesh.DoorProtocol.Declined) PhoneDoorWire.outcomeOf(declined, "env-2")).reason());
    }

    @Test
    void heartbeatAndHelloDecode() throws Exception {
        var hb = PhoneDoorWire.decodeHeartbeat(HEARTBEAT);
        assertEquals("llm.phone-npu", hb.capabilityClass());
        assertTrue(hb.charging());
        assertEquals(0.1, hb.loadFactor(), 1e-9);
        assertEquals("hello", PhoneDoorWire.typeOf(HELLO));
        assertEquals("knock", PhoneDoorWire.typeOf(KNOCK_PLAIN));
        assertEquals("answer", PhoneDoorWire.typeOf(ANSWER_DECLINED));
        assertEquals("heartbeat", PhoneDoorWire.typeOf(HEARTBEAT));
    }

    @Test
    void ourWritesStayInsideTheContract() throws Exception {
        var envelope = new TaskEnvelope("env-9", "hh1", "origin-node", "inference.chat",
            "photos", "llm.phone-npu", Map.of("k", "v"), 128,
            Instant.parse("2026-08-14T12:00:00Z"), Instant.parse("2026-08-14T12:01:00Z"),
            Optional.of(new SignedGrant("g-9", "hh1", "photos", "llm.phone-npu",
                Instant.parse("2026-08-14T00:00:00Z"), Instant.parse("2026-08-21T00:00:00Z"),
                "v1", new byte[]{2, 3})),
            new byte[]{1});
        var json = PhoneDoorWire.encode(PhoneDoorWire.Knock.of("phone-7/k9", envelope));
        // ISO instants and base64 bytes on the wire — never numeric epochs.
        assertTrue(json.contains("\"2026-08-14T12:00:00Z\""), json);
        assertTrue(json.contains("\"AQ==\""), json);
        assertTrue(json.contains("\"AgM=\""), json);
        // And our own reader accepts our own writes.
        var back = DoorWire.JSON.readValue(json, PhoneDoorWire.Knock.class).envelope().toEnvelope();
        assertEquals("env-9", back.envelopeId());
        assertEquals("g-9", back.grant().orElseThrow().grantId());
    }
}
