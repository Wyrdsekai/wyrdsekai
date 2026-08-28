package org.wyrdsekai.hermod;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration: two devices, two doors, one mesh loop. Pins refusal
 * failover, data-gravity placement, and the honest no-candidate answer.
 */
class ARefusedDoorSendsTheErrandOnwardTest {

    private final Clock clock = Clock.systemUTC();

    private Capability dev(String id, String cls, List<String> domains, double load) {
        return new Capability(id, "hh1", cls, List.of("m"), domains, true, true, load, Instant.now());
    }

    private TaskEnvelope task(String domain, Optional<SignedGrant> grant) {
        return new TaskEnvelope("e1", "hh1", "origin", "test.echo", domain, "llm.a1b",
            Map.of(), 100, Instant.now(), Instant.now().plusSeconds(30), grant, new byte[]{1});
    }

    private TaskExecutor echo(String tag) {
        return new TaskExecutor() {
            public boolean handles(String t) { return "test.echo".equals(t); }
            public TaskResult execute(TaskEnvelope e) { return TaskResult.ok(e.envelopeId(), tag); }
        };
    }

    private Mesh mesh(CapabilityTable table, Map<String, Mesh.DoorProtocol> doors) {
        return new Mesh(new DefaultRouter(table, clock), (e, cap) -> doors.get(cap.deviceId()));
    }

    @Test
    void firstDoorRefusesSecondAdmits() {
        var table = new CapabilityTable(Duration.ofMinutes(1));
        table.merge(dev("busy-node", "llm.a1b", List.of(), 0.0));
        table.merge(dev("free-node", "llm.a1b", List.of(), 0.9));
        var doors = Map.of(
            "busy-node", Mesh.local(e -> AdmissionGate.Decision.refuse("draining"), echo("busy")),
            "free-node", Mesh.local(e -> AdmissionGate.Decision.admit(), echo("free")));
        var result = mesh(table, doors).submit(task("none", Optional.empty()));
        assertTrue(result.ok());
        assertEquals("free", result.output(), "the errand moved on to the door that admitted it");
    }

    @Test
    void aPhotosTaskOnlyGoesWhereThePhotosLive() {
        var table = new CapabilityTable(Duration.ofMinutes(1));
        table.merge(dev("big-node", "llm.a1b", List.of(), 0.0));       // stronger, but no photos
        table.merge(dev("phone", "llm.a1b", List.of("photos"), 0.5));
        var grant = new SignedGrant("g1", "hh1", "photos", "node", Instant.now(),
            Instant.now().plusSeconds(60), "v1", new byte[]{2});
        var router = new DefaultRouter(table, clock);
        var cands = router.candidates(task("photos", Optional.of(grant)));
        assertEquals(1, cands.size());
        assertEquals("phone", cands.get(0).deviceId(), "compute travels to the data");
    }

    @Test
    void nobodyCapableGetsAnHonestAnswer() {
        var table = new CapabilityTable(Duration.ofMinutes(1));
        var result = mesh(table, Map.of()).submit(task("none", Optional.empty()));
        assertFalse(result.ok());
        assertTrue(result.error().contains("no device advertises capability"), result.error());
    }

    @Test
    void theGateRefusesAnUngrantedDomainTask() {
        var gate = new LocalAdmissionGate(clock, 1000, g -> true, e -> false);
        var d = gate.consider(task("photos", Optional.empty()));
        assertEquals(AdmissionGate.Verdict.REFUSE, d.verdict());
        assertTrue(d.reason().contains("no grant"), d.reason());
    }

    @Test
    void theGateVerifiesTheGrantAtTheData() {
        var badSig = new LocalAdmissionGate(clock, 1000, g -> false, e -> false);
        var grant = new SignedGrant("g1", "hh1", "photos", "node", Instant.now(),
            Instant.now().plusSeconds(60), "v1", new byte[]{2});
        var d = badSig.consider(task("photos", Optional.of(grant)));
        assertEquals(AdmissionGate.Verdict.REFUSE, d.verdict());
        assertTrue(d.reason().contains("signature"), d.reason());
    }
}
