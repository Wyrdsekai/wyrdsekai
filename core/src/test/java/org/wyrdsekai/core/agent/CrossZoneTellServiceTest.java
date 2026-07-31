package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CrossZoneTellServiceTest {

    @BeforeEach
    void setup() {
        EntityRegistry.init();
        CrossZoneTellService.init("alpha");
        // Reset cross-test singleton state — contractLookup and relayPublisher
        // persist across tests within the same JVM and would carry over a
        // previous test's wiring. init() is a no-op once the service exists,
        // so we null the injections directly.
        var svc = CrossZoneTellService.get();
        svc.setContractLookup(null);
        svc.setRelayPublisher(null);
        svc.setPlayerDeliverer(null);
    }

    @AfterEach
    void cleanup() {
        // Shared-JVM hygiene: a leaked deliverer would silently reroute tell
        // delivery in unrelated CompanionActor tests (they check in-room speech).
        var svc = CrossZoneTellService.get();
        if (svc != null) svc.setPlayerDeliverer(null);
    }

    // --- Session tell-back (second-node 2026-07-11 #27) ---

    @Test
    void deliverToPlayerSession_routesFormattedLineToDeliverer() {
        var svc = CrossZoneTellService.get();
        var captured = new AtomicReference<String>();
        var capturedPlayer = new AtomicReference<String>();
        svc.setPlayerDeliverer((playerId, text) -> {
            capturedPlayer.set(playerId);
            captured.set(text);
            return true;
        });

        assertTrue(svc.hasPlayerDeliverer());
        assertTrue(svc.deliverToPlayerSession("player-7", "Mia", "the garden is east of the nexus"));
        assertEquals("player-7", capturedPlayer.get());
        assertEquals("[from Mia] the garden is east of the nexus", captured.get());
    }

    @Test
    void deliverToPlayerSession_falseWhenUnwiredOrBlankPlayer() {
        var svc = CrossZoneTellService.get();
        // Unwired (single-node boot before Main wiring): must report false so
        // the caller falls back to in-room delivery instead of losing the reply.
        assertFalse(svc.hasPlayerDeliverer());
        assertFalse(svc.deliverToPlayerSession("player-7", "Mia", "hello"));

        svc.setPlayerDeliverer((p, t) -> true);
        assertFalse(svc.deliverToPlayerSession(null, "Mia", "hello"));
        assertFalse(svc.deliverToPlayerSession("  ", "Mia", "hello"));
    }

    @Test
    void deliverToPlayerSession_falseWhenNoLiveSessionTookDelivery() {
        // #29: a WIRED deliverer that reaches no live session (e.g. the old
        // WS-only fan-out while the player is on SSH) must surface false so
        // CompanionActor's teleport-and-speak fallback fires instead of the
        // reply silently vanishing.
        var svc = CrossZoneTellService.get();
        svc.setPlayerDeliverer((p, t) -> false);
        assertTrue(svc.hasPlayerDeliverer());
        assertFalse(svc.deliverToPlayerSession("player-7", "Mia", "hello"));
    }

    @Test
    void deliverToPlayerSession_swallowsDelivererFailure() {
        var svc = CrossZoneTellService.get();
        svc.setPlayerDeliverer((p, t) -> { throw new IllegalStateException("session gone"); });
        assertFalse(svc.deliverToPlayerSession("player-7", "Mia", "hello"));
    }

    // --- Zone prefix parsing ---

    @Test
    void parseZonePrefix_noPrefix() {
        var parsed = CrossZoneTellService.parseZonePrefix("wyrd");
        assertNull(parsed.zone());
        assertEquals("wyrd", parsed.name());
    }

    @Test
    void parseZonePrefix_zoneDotName() {
        var parsed = CrossZoneTellService.parseZonePrefix("beta.wyrd");
        assertEquals("beta", parsed.zone());
        assertEquals("wyrd", parsed.name());
    }

    @Test
    void parseZonePrefix_myPrefix() {
        var parsed = CrossZoneTellService.parseZonePrefix("my wyrd");
        assertEquals("my", parsed.zone());
        assertEquals("wyrd", parsed.name());
    }

    @Test
    void parseZonePrefix_mudOrdinalNotConfused() {
        var parsed = CrossZoneTellService.parseZonePrefix("2.sword");
        assertNull(parsed.zone());
        assertEquals("2.sword", parsed.name());
    }

    @Test
    void parseZonePrefix_emptyInput() {
        var parsed = CrossZoneTellService.parseZonePrefix(null);
        assertNull(parsed.zone());
        assertEquals("", parsed.name());
    }

    // --- Local delivery ---

    @Test
    void tell_localAgentDelivered() {
        // Set up a local agent
        AgentEventStream.init();
        EntityRegistry.get().enter("agent-wyrd", "Wyrd", "agent", "nexus");

        var service = CrossZoneTellService.get();
        var result = service.tell("player-1", "Alice", "alpha", "wyrd", "hello");

        // Without actually having subscribers, publishAgentMessage returns false
        // But the lookup should work
        assertNotNull(result);
    }

    @Test
    void tell_noTarget_returnsNotDelivered() {
        var service = CrossZoneTellService.get();
        var result = service.tell("player-1", "Alice", "alpha", "nobody", "hello");

        assertFalse(result.delivered());
        assertNotNull(result.errorMessage());
    }

    // --- Cross-zone routing ---

    @Test
    void tell_explicitZonePrefix_routesViaRelay() {
        var service = CrossZoneTellService.get();
        var captured = new AtomicReference<String>();
        service.setRelayPublisher((subject, data) -> captured.set(subject));

        var result = service.tell("player-1", "Alice", "alpha", "beta.wyrd", "hello");

        assertTrue(result.delivered());
        assertEquals("federation.beta.tell", captured.get());
    }

    @Test
    void tell_myPrefix_routesToHomeZone() {
        var service = CrossZoneTellService.get();
        var captured = new AtomicReference<String>();
        service.setRelayPublisher((subject, data) -> captured.set(subject));

        // Set player's home zone to gamma
        EntityRegistry.get().setHomeZone("player-1", "gamma");

        var result = service.tell("player-1", "Alice", "alpha", "my wyrd", "hello");

        assertTrue(result.delivered());
        assertEquals("federation.gamma.tell", captured.get());
    }

    @Test
    void tell_myPrefix_homeIsLocal_noRelay() {
        var service = CrossZoneTellService.get();
        var captured = new AtomicReference<String>();
        service.setRelayPublisher((subject, data) -> captured.set(subject));

        // Player's home zone is local
        EntityRegistry.get().setHomeZone("player-1", "alpha");

        var result = service.tell("player-1", "Alice", "alpha", "my wyrd", "hello");

        // Should try local first (wyrd isn't registered → not delivered, but no relay call)
        assertNull(captured.get()); // no relay publish
    }

    @Test
    void tell_noRelayConfigured_returnsError() {
        var service = CrossZoneTellService.get();
        // No relayPublisher set

        var result = service.tell("player-1", "Alice", "alpha", "beta.wyrd", "hello");

        assertFalse(result.delivered());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("relay not connected"));
    }

    // --- Presence state ---

    @Test
    void presenceState_defaultsToOffline() {
        var registry = EntityRegistry.get();
        assertEquals(EntityRegistry.PresenceState.OFFLINE, registry.presenceOf("unknown"));
    }

    @Test
    void presenceState_setTraveling() {
        var registry = EntityRegistry.get();
        registry.enter("player-1", "Alice", "player", "nexus");
        registry.setTraveling("player-1", "beta");

        assertEquals(EntityRegistry.PresenceState.TRAVELING, registry.presenceOf("player-1"));
        assertEquals("beta", registry.travelDestinationOf("player-1").orElse(null));
    }

    @Test
    void presenceState_setReturned() {
        var registry = EntityRegistry.get();
        registry.enter("player-1", "Alice", "player", "nexus");
        registry.setTraveling("player-1", "beta");
        registry.setReturned("player-1");

        assertEquals(EntityRegistry.PresenceState.PRESENT, registry.presenceOf("player-1"));
        assertTrue(registry.travelDestinationOf("player-1").isEmpty());
    }

    @Test
    void homeZone_persists() {
        var registry = EntityRegistry.get();
        registry.setHomeZone("player-1", "alpha");
        assertEquals("alpha", registry.homeZoneOf("player-1").orElse(null));
    }

    @Test
    void remove_clearsAllState() {
        var registry = EntityRegistry.get();
        registry.enter("player-1", "Alice", "player", "nexus");
        registry.setTraveling("player-1", "beta");
        registry.setHomeZone("player-1", "alpha");

        registry.remove("player-1");

        assertEquals(EntityRegistry.PresenceState.OFFLINE, registry.presenceOf("player-1"));
        assertTrue(registry.travelDestinationOf("player-1").isEmpty());
        assertTrue(registry.homeZoneOf("player-1").isEmpty());
    }

    // ── Wave 7: handleIncomingTell scope enforcement (§6.9) ──────────

    @Test
    void handleIncomingTell_withoutContractLookup_delivers() {
        // Pre-Wave-7 behaviour — no contract lookup wired, tell flows through
        // unimpeded. Protects existing deployments from a silent behaviour
        // change when they upgrade but haven't yet opted into enforcement.
        var registry = EntityRegistry.get();
        AgentEventStream.init();
        registry.enter("target-id", "target", "agent", "nexus");

        var delivered = new AtomicReference<String>();
        AgentEventStream.get().subscribe("target-id", ev -> {
            if (ev instanceof AgentEvent.AgentMessage am) delivered.set(am.message());
        });

        var service = CrossZoneTellService.get();
        // Explicitly NOT calling setContractLookup.
        service.handleIncomingTell("stranger", "Stranger", "beta", "target", "hello");

        awaitDelivered(delivered);
        assertNotNull(delivered.get(),
            "tell must have been delivered");
        assertTrue(delivered.get().contains("hello"),
            "tell must deliver when no contract lookup is wired (pre-Wave-7 compat)");
    }

    @Test
    void handleIncomingTell_contractLookupAllows_delivers() {
        var registry = EntityRegistry.get();
        AgentEventStream.init();
        registry.enter("target-id", "target", "agent", "nexus");

        var delivered = new AtomicReference<String>();
        AgentEventStream.get().subscribe("target-id", ev -> {
            if (ev instanceof AgentEvent.AgentMessage am) delivered.set(am.message());
        });

        var service = CrossZoneTellService.get();
        // Allow lookup simulates an active bilateral agreement.
        service.setContractLookup((sz, tz, te) -> true);
        service.handleIncomingTell("stranger", "Stranger", "beta", "target", "hello");

        awaitDelivered(delivered);
        assertNotNull(delivered.get());
        assertTrue(delivered.get().contains("hello"));
    }

    @Test
    void handleIncomingTell_contractLookupDenies_drops() {
        var registry = EntityRegistry.get();
        AgentEventStream.init();
        registry.enter("target-id", "target", "agent", "nexus");

        var delivered = new AtomicReference<String>();
        AgentEventStream.get().subscribe("target-id", ev -> {
            if (ev instanceof AgentEvent.AgentMessage am) delivered.set(am.message());
        });

        var service = CrossZoneTellService.get();
        // Deny lookup — sender's zone has no contract with us.
        service.setContractLookup(TellScopeGate.DENY_ALL);
        service.handleIncomingTell("stranger", "Stranger", "beta", "target", "hello");

        // Short wait before asserting null — the drain thread is async, so if
        // the deny check leaks through, we'd see a late delivery. 200ms is
        // plenty for a loopback rate-limiter run.
        try { Thread.sleep(200); } catch (InterruptedException ignore) {}
        assertNull(delivered.get(),
            "tell must be dropped when scope gate denies (§6.9 inbox-spam protection)");
    }

    @Test
    void handleIncomingTell_intraZoneAlwaysAllowed() {
        // A same-zone tell isn't really "cross-zone" — the intra-zone rule in
        // TellScopeGate treats targetZone == senderZone as allowed. Guards
        // against accidental rejection if the loopback path hits
        // handleIncomingTell (e.g. during local testing).
        var registry = EntityRegistry.get();
        AgentEventStream.init();
        registry.enter("target-id", "target", "agent", "nexus");

        var delivered = new AtomicReference<String>();
        AgentEventStream.get().subscribe("target-id", ev -> {
            if (ev instanceof AgentEvent.AgentMessage am) delivered.set(am.message());
        });

        var service = CrossZoneTellService.get();
        service.setContractLookup(TellScopeGate.DENY_ALL);
        // fromZone = "alpha" (same as localZoneId set in @BeforeEach).
        service.handleIncomingTell("buddy", "Buddy", "alpha", "target", "hi");

        awaitDelivered(delivered);
        assertNotNull(delivered.get(),
            "intra-zone tell must pass the gate regardless of contract lookup");
        assertTrue(delivered.get().contains("hi"));
    }

    /**
     * AgentEventStream uses a per-subscriber {@code event-drain-<agentId>}
     * daemon thread that polls an ArrayBlockingQueue. Delivery is async, so a
     * bare {@code assertNotNull(delivered.get())} immediately after
     * {@code handleIncomingTell} races the drain thread and flakes ~1/3 of
     * runs. Bound the wait tightly (1s) so failures still surface quickly.
     */
    private static void awaitDelivered(AtomicReference<String> ref) {
        long deadline = System.nanoTime() + 1_000_000_000L; // 1s
        while (ref.get() == null && System.nanoTime() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void routeToZone_withoutContractLookup_publishes() {
        // Pre-Wave-7 compat: no lookup wired → outbound tell passes through.
        var published = new AtomicReference<String>();
        var service = CrossZoneTellService.get();
        service.setRelayPublisher((subject, data) -> published.set(subject));
        service.tell("alice", "Alice", "alpha", "beta.wyrd", "hello");
        assertNotNull(published.get(),
            "relay publish must happen when no contract lookup is wired");
        assertTrue(published.get().contains("beta"));
    }

    @Test
    void routeToZone_contractLookupAllows_publishes() {
        var published = new AtomicReference<String>();
        var service = CrossZoneTellService.get();
        service.setRelayPublisher((subject, data) -> published.set(subject));
        service.setContractLookup((sz, tz, te) -> true);
        service.tell("alice", "Alice", "alpha", "beta.wyrd", "hello");
        assertNotNull(published.get(),
            "relay publish must happen when contract lookup allows");
    }

    @Test
    void routeToZone_contractLookupDenies_suppressesPublish() {
        var published = new AtomicReference<String>();
        var service = CrossZoneTellService.get();
        service.setRelayPublisher((subject, data) -> published.set(subject));
        service.setContractLookup(TellScopeGate.DENY_ALL);
        var result = service.tell("alice", "Alice", "alpha", "beta.wyrd", "hello");
        assertFalse(result.delivered(),
            "outbound tell must be rejected when contract lookup denies");
        assertNull(published.get(),
            "relay publish must NOT happen when scope gate denies — don't burn relay bandwidth");
        assertTrue(result.errorMessage() != null
            && result.errorMessage().contains("contract"),
            "error message must explain: " + result.errorMessage());
    }

    @Test
    void handleIncomingTell_sameRoomAlwaysAllowed() {
        var registry = EntityRegistry.get();
        AgentEventStream.init();
        // Unique id to isolate from other suites that register "target-id"
        // agents into the shared AgentEventStream during a full :core:test run.
        var uniqueId = "same-room-target-" + UUID.randomUUID();
        // Sender and target in the same room — same-room rule short-circuits
        // the contract check.
        registry.enter("stranger-sr", "Stranger", "player", "parlor-public");
        registry.enter(uniqueId, "sr-target", "agent", "parlor-public");

        var delivered = new AtomicReference<String>();
        AgentEventStream.get().subscribe(uniqueId, ev -> {
            if (ev instanceof AgentEvent.AgentMessage am) delivered.set(am.message());
        });

        var service = CrossZoneTellService.get();
        service.setContractLookup(TellScopeGate.DENY_ALL);
        service.handleIncomingTell("stranger-sr", "Stranger", "beta", "sr-target", "hi");

        awaitDelivered(delivered);
        assertNotNull(delivered.get(),
            "same-room tell must be delivered even when contract lookup denies");
        assertTrue(delivered.get().contains("hi"));
    }
}
