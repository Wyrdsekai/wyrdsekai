package org.wyrdsekai.server.hermod;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.AdmissionGate;
import org.wyrdsekai.hermod.Mesh;
import org.wyrdsekai.hermod.SignedGrant;
import org.wyrdsekai.hermod.TaskEnvelope;
import org.wyrdsekai.hermod.TaskExecutor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the door wire: envelopes (with and without grants) and answers round-trip; the server half is total. */
class AKnockAndItsAnswerSurviveTheWireTest {

    private TaskEnvelope envelope(Optional<SignedGrant> grant) {
        return new TaskEnvelope("e9", "hh1", "phone", "inference.chat", 
            grant.isPresent() ? "photos" : "none", "llm.a1b",
            Map.of("model", "m", "prompt", "p"), 64,
            Instant.parse("2026-08-14T00:00:00Z"), Instant.parse("2026-08-14T00:01:00Z"),
            grant, new byte[]{7});
    }

    @Test
    void anEnvelopeWithAGrantRoundTrips() throws Exception {
        var grant = new SignedGrant("g1", "hh1", "photos", "node",
            Instant.parse("2026-08-14T00:00:00Z"), Instant.parse("2026-08-14T01:00:00Z"),
            "v3", new byte[]{9});
        var e = envelope(Optional.of(grant));
        var back = DoorWire.decodeEnvelope(DoorWire.encodeEnvelope(e));
        assertEquals(e.envelopeId(), back.envelopeId());
        assertEquals("photos", back.dataDomain());
        assertEquals("g1", back.grant().orElseThrow().grantId());
    }

    @Test
    void aGrantlessEnvelopeRoundTripsWithEmptyOptional() throws Exception {
        var back = DoorWire.decodeEnvelope(DoorWire.encodeEnvelope(envelope(Optional.empty())));
        assertTrue(back.grant().isEmpty());
    }

    @Test
    void theServerHalfAnswersAKnockEndToEnd() throws Exception {
        TaskExecutor echo = new TaskExecutor() {
            public boolean handles(String t) { return true; }
            public TaskResult execute(TaskEnvelope e) { return TaskResult.ok(e.envelopeId(), "carried home"); }
        };
        var door = Mesh.local(e -> AdmissionGate.Decision.admit(), echo);
        var reply = DoorWire.answer(DoorWire.encodeEnvelope(envelope(Optional.empty())), door);
        var outcome = DoorWire.decodeAnswer(reply, "e9");
        assertInstanceOf(Mesh.DoorProtocol.Completed.class, outcome);
        assertEquals("carried home", ((Mesh.DoorProtocol.Completed) outcome).result().output());
    }

    @Test
    void aRefusingDoorDeclinesOverTheWire() throws Exception {
        var door = Mesh.closed("not charging");
        var reply = DoorWire.answer(DoorWire.encodeEnvelope(envelope(Optional.empty())), door);
        var outcome = DoorWire.decodeAnswer(reply, "e9");
        assertEquals("not charging", ((Mesh.DoorProtocol.Declined) outcome).reason());
    }

    @Test
    void aMalformedKnockDeclinesInsteadOfExploding() throws Exception {
        var reply = DoorWire.answer("not json".getBytes(), Mesh.closed("unused"));
        var outcome = DoorWire.decodeAnswer(reply, "e9");
        assertTrue(((Mesh.DoorProtocol.Declined) outcome).reason().startsWith("malformed knock"));
    }
}
