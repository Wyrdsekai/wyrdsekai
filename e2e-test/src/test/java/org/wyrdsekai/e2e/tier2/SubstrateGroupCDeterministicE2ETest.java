package org.wyrdsekai.e2e.tier2;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.release.AttestationPublishScheduler;
import org.wyrdsekai.core.release.AttestationPublishState;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.soul.RepairLedger;
import org.wyrdsekai.core.soul.RepairMode;
import org.wyrdsekai.core.soul.RepairModeTracker;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Group C deterministic e2e: exercises the runtime wires landed
 * 2026-05-17 (auto-handoff, attestation cadence, substrate state
 * persistence) via direct state injection + sleep trigger — no model
 * probing. Complements {@link SubstrateArcE2ETest} which tests whether
 * the model knows how to USE the substrate; this file tests whether
 * the runtime correctly handles the substrate when state is explicitly
 * seeded.
 *
 * <p>Why same backend gate as SubstrateArc: the companion actor only
 * spawns when an inference backend is available. The model is never
 * actually called in these tests — assertions are against in-process
 * singleton trackers.
 *
 * <p>Run on home-server: {@code WYRDSEKAI_E2E_BACKEND=llama-server
 * WYRDSEKAI_INFERENCE_URL=http://localhost:8200
 * ./gradlew :e2e-test:test --tests "*SubstrateGroupCDeterministicE2ETest"}
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND",
    matches = "sglang|llama-server|llama")
class SubstrateGroupCDeterministicE2ETest {

    private static TestServerBootstrap server;
    private static String agentDid;

    @BeforeAll
    static void setUp() throws Exception {
        RepairModeTracker.get().clearForTests();
        RepairLedger.get().clearForTests();
        AttestationPublishState.get().clearForTests();

        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();

        // Wait for the seed-timeout-spawned companion to register.
        long deadline = System.currentTimeMillis() + 30_000;
        ActorRef<CompanionActor.Command> ref = null;
        while (System.currentTimeMillis() < deadline) {
            ref = ZoneGuardian.getCompanionRef(null, "companion-wyrd");
            if (ref != null) break;
            Thread.sleep(500);
        }
        assertNotNull(ref, "companion-wyrd must register within 30s");

        agentDid = queryAgentDid();
        assertNotNull(agentDid, "Companion must surface its agentDid");
        System.out.println("[GroupC-Det] agentDid=" + agentDid);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        RepairModeTracker.get().clearForTests();
        RepairLedger.get().clearForTests();
        AttestationPublishState.get().clearForTests();
    }

    @BeforeEach
    void respawn() throws Exception {
        if (server != null) server.respawnCompanion();
        RepairModeTracker.get().clearForTests();
        AttestationPublishState.get().clearForTests();
        Thread.sleep(1_500);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 1: HandoffThresholdEngine — Self→Bonded on max cycles
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void autoHandoff_self_to_bonded_on_max_cycles() throws Exception {
        var tracker = RepairModeTracker.get();
        tracker.transition(agentDid, RepairMode.SELF, "cycle1");
        tracker.transition(agentDid, RepairMode.SELF, "cycle2");
        tracker.transition(agentDid, RepairMode.SELF, "cycle3");
        assertThat(tracker.currentMode(agentDid)).isEqualTo(RepairMode.SELF);

        forceSleepAndWait();

        assertThat(tracker.currentMode(agentDid))
            .as("§7.1.1 SELF→BONDED handoff on 3 cycles without improvement")
            .isEqualTo(RepairMode.BONDED);
        var last = tracker.lastHandoff(agentDid);
        assertThat(last).isPresent();
        assertThat(last.get().to()).isEqualTo(RepairMode.BONDED);
        assertThat(last.get().reason())
            .containsAnyOf("max_cycles", "cycles", "improvement");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 2: HandoffThresholdEngine — no handoff when not in repair mode
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void autoHandoff_noops_when_not_in_repair() throws Exception {
        var tracker = RepairModeTracker.get();
        assertThat(tracker.currentMode(agentDid)).isEqualTo(RepairMode.NONE);

        forceSleepAndWait();

        assertThat(tracker.currentMode(agentDid))
            .as("sleep must not gratuitously enter repair mode")
            .isEqualTo(RepairMode.NONE);
        assertThat(tracker.history(agentDid)).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 3: AttestationPublishScheduler — cadence check fires every sleep
    //
    // The recordDecision affordance captures the scheduler's output so
    // tests can verify it ran. Either reason is acceptable — both prove
    // the scheduler executed.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void attestationCadence_runs_on_sleep() throws Exception {
        forceSleepAndWait();

        var lastDecision = AttestationPublishState.get().lastDecision(agentDid);
        assertThat(lastDecision)
            .as("cadence check must run on every sleep and record its decision")
            .isPresent();
        var reason = lastDecision.get().reason();
        assertThat(reason).isIn(
            AttestationPublishScheduler.Reason.NO_PUBLISH_NEEDED,
            AttestationPublishScheduler.Reason.FIRST_THIS_SESSION);
        System.out.println("[GroupC-Det] attestation reason=" + reason
            + " shouldPublish=" + lastDecision.get().shouldPublish());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 4: AttestationPublishState — recordPublished gates future publishes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void attestationCadence_skips_when_recently_published() throws Exception {
        var state = AttestationPublishState.get();
        state.recordPublished(agentDid, Instant.now(),
            Optional.of("test-manifest-hash"));

        forceSleepAndWait();

        var lastDecision = state.lastDecision(agentDid);
        assertThat(lastDecision).isPresent();
        assertThat(lastDecision.get().shouldPublish())
            .as("recent publish + unchanged hash must not re-publish")
            .isFalse();
        assertThat(state.latest(agentDid))
            .as("publish bookkeeping must survive a sleep cycle")
            .isPresent();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 5: RepairModeTracker — BONDED persists if conditions don't fire
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void substrate_state_survives_sleep_cycle() throws Exception {
        var tracker = RepairModeTracker.get();
        tracker.transition(agentDid, RepairMode.BONDED, "test-seed");
        assertThat(tracker.currentMode(agentDid)).isEqualTo(RepairMode.BONDED);

        forceSleepAndWait();

        // BONDED with single cycle + empty bondholder context: no §7.1.2
        // trigger fires, so the agent should remain BONDED.
        assertThat(tracker.currentMode(agentDid))
            .as("BONDED with single cycle + empty bondholder context must persist")
            .isEqualTo(RepairMode.BONDED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static String queryAgentDid() throws Exception {
        var companionRef = ZoneGuardian.getCompanionRef(null, "companion-wyrd");
        if (companionRef == null) {
            throw new IllegalStateException("companion-wyrd not registered");
        }
        var fut = AskPattern.ask(
            companionRef,
            (ActorRef<
                    CompanionActor.TestStateResponse> replyTo) ->
                new CompanionActor.QueryTestState(replyTo),
            Duration.ofSeconds(10),
            server.system().scheduler());
        var resp = fut.toCompletableFuture()
            .get(15, TimeUnit.SECONDS);
        return resp.agentDid();
    }

    private static void forceSleepAndWait() throws Exception {
        var companionRef = ZoneGuardian.getCompanionRef(null, "companion-wyrd");
        assertNotNull(companionRef);
        companionRef.tell(new CompanionActor.ForceSleep());
        // completeSleep runs asynchronously after the Forge cycle completes
        // (which can take 10-20s when an inference backend is wired). Block
        // until cadence decision lands — that's the last step in completeSleep
        // and signals all Group C wires have fired.
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            if (AttestationPublishState.get().lastDecision(agentDid).isPresent()) {
                // Cadence decision recorded — completeSleep finished.
                Thread.sleep(300); // small grace for any tail writes
                return;
            }
        }
        throw new AssertionError("sleep cycle did not complete within 30s");
    }
}
