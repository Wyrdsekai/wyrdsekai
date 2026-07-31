package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementEngineTest {

    private PlacementEngine engine;

    @BeforeEach void setUp() {
        engine = new PlacementEngine();
    }

    private NodeCapabilities.Snapshot makeSnapshot(String nodeId, long gpuVram, long ramFreeMb,
                                                    double cpuIdlePct, boolean modelLoaded,
                                                    Set<String> caps, List<String> companions) {
        return new NodeCapabilities.Snapshot(
            nodeId, caps, 8, 32768, ramFreeMb,
            gpuVram > 0 ? "Test GPU" : null, gpuVram,
            100_000, cpuIdlePct,
            modelLoaded ? "llama-server" : null, modelLoaded,
            companions, List.of(),
            -1, // plugged in
            "HEALTHY",
            Instant.now()
        );
    }

    @Test void score_higherGpuVramScoresHigher() {
        var low = makeSnapshot("a", 2048, 8000, 80, false, Set.of("inference"), List.of());
        var high = makeSnapshot("b", 8192, 8000, 80, false, Set.of("inference"), List.of());
        assertThat(engine.score(high)).isGreaterThan(engine.score(low));
    }

    @Test void score_modelLoadedBonus() {
        var noModel = makeSnapshot("a", 4096, 8000, 80, false, Set.of("inference"), List.of());
        var withModel = makeSnapshot("b", 4096, 8000, 80, true, Set.of("inference"), List.of());
        assertThat(engine.score(withModel)).isGreaterThan(engine.score(noModel));
        assertThat(engine.score(withModel) - engine.score(noModel)).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test void score_companionPenalty() {
        var empty = makeSnapshot("a", 4096, 8000, 80, true, Set.of("inference"), List.of());
        var hosting = makeSnapshot("b", 4096, 8000, 80, true, Set.of("inference"),
            List.of("companion-1", "companion-2"));
        assertThat(engine.score(empty)).isGreaterThan(engine.score(hosting));
    }

    @Test void score_downNodeReturnsNegative() {
        var down = new NodeCapabilities.Snapshot(
            "a", Set.of("inference"), 8, 32768, 8000,
            "Test GPU", 4096, 100000, 80, "llama-server", true,
            List.of(), List.of(), -1, "DOWN", Instant.now());
        assertThat(engine.score(down)).isEqualTo(-1);
    }

    @Test void score_maintenanceNodeReturnsNegative() {
        var maint = new NodeCapabilities.Snapshot(
            "a", Set.of("inference"), 8, 32768, 8000,
            "Test GPU", 4096, 100000, 80, "llama-server", true,
            List.of(), List.of(), -1, "MAINTENANCE", Instant.now());
        assertThat(engine.score(maint)).isEqualTo(-1);
    }

    @Test void score_lowBatteryPenalty() {
        var pluggedIn = new NodeCapabilities.Snapshot(
            "a", Set.of("inference"), 8, 32768, 8000,
            "Test GPU", 4096, 100000, 80, "llama-server", true,
            List.of(), List.of(), -1, "HEALTHY", Instant.now());
        var lowBattery = new NodeCapabilities.Snapshot(
            "b", Set.of("inference"), 8, 32768, 8000,
            "Test GPU", 4096, 100000, 80, "llama-server", true,
            List.of(), List.of(), 15, "HEALTHY", Instant.now());
        assertThat(engine.score(pluggedIn)).isGreaterThan(engine.score(lowBattery));
    }

    // ── Companion placement ──

    @Test void companionPlacement_unclaimedClaimsBestNode() {
        engine.updateNodeSnapshot(makeSnapshot("a", 2048, 8000, 80, false,
            Set.of("inference"), List.of()));
        engine.updateNodeSnapshot(makeSnapshot("b", 8192, 16000, 90, true,
            Set.of("inference", "gpu"), List.of()));

        var decision = engine.evaluateCompanionPlacement("companion-wyrd");
        assertThat(decision.action()).isEqualTo(PlacementEngine.PlacementDecision.Action.CLAIM);
        assertThat(decision.targetNodeId()).isEqualTo("b");
    }

    @Test void companionPlacement_currentHostGoodEnoughKeeps() {
        var snapA = makeSnapshot("a", 4096, 8000, 80, true, Set.of("inference"), List.of());
        var snapB = makeSnapshot("b", 4096, 8000, 80, true, Set.of("inference"), List.of());
        engine.updateNodeSnapshot(snapA);
        engine.updateNodeSnapshot(snapB);
        engine.recordCompanionClaim("companion-wyrd", "a");

        var decision = engine.evaluateCompanionPlacement("companion-wyrd");
        assertThat(decision.action()).isEqualTo(PlacementEngine.PlacementDecision.Action.KEEP);
    }

    @Test void companionPlacement_significantlyBetterNodeTriggersMigration() {
        // Node A: no GPU
        var snapA = makeSnapshot("a", 0, 4000, 50, false, Set.of("inference"), List.of());
        // Node B: powerful GPU + model loaded
        var snapB = makeSnapshot("b", 16384, 32000, 95, true, Set.of("inference", "gpu"), List.of());

        engine.updateNodeSnapshot(snapA);
        engine.recordCompanionClaim("companion-wyrd", "a");
        engine.updateNodeSnapshot(snapB);

        var decision = engine.evaluateCompanionPlacement("companion-wyrd");
        assertThat(decision.action()).isEqualTo(PlacementEngine.PlacementDecision.Action.MIGRATE);
        assertThat(decision.targetNodeId()).isEqualTo("b");
    }

    @Test void companionPlacement_hostDownTriggersFailover() {
        var snapA = makeSnapshot("a", 4096, 8000, 80, true, Set.of("inference"), List.of());
        var snapB = makeSnapshot("b", 4096, 8000, 80, true, Set.of("inference"), List.of());
        engine.updateNodeSnapshot(snapA);
        engine.updateNodeSnapshot(snapB);
        engine.recordCompanionClaim("companion-wyrd", "a");

        // Node A goes down
        engine.markNodeDown("a");

        var decision = engine.evaluateCompanionPlacement("companion-wyrd");
        assertThat(decision.action()).isEqualTo(PlacementEngine.PlacementDecision.Action.FAILOVER);
        assertThat(decision.targetNodeId()).isEqualTo("b");
    }

    // ── Room primary placement ──

    @Test void roomPrimary_matchesCapabilityRequirements() {
        engine.setRoomRequirements("docks", Set.of("internet"));
        engine.updateNodeSnapshot(makeSnapshot("a", 0, 4000, 50, false,
            Set.of("inference"), List.of())); // no internet
        engine.updateNodeSnapshot(makeSnapshot("b", 0, 4000, 50, false,
            Set.of("inference", "internet"), List.of()));

        var decision = engine.evaluateRoomPrimary("docks");
        assertThat(decision.action()).isEqualTo(PlacementEngine.PlacementDecision.Action.CLAIM);
        assertThat(decision.targetNodeId()).isEqualTo("b");
    }

    @Test void roomPrimary_noCapableNodeReturnsKeep() {
        engine.setRoomRequirements("gpu-chamber", Set.of("gpu"));
        engine.updateNodeSnapshot(makeSnapshot("a", 0, 4000, 50, false,
            Set.of("inference"), List.of())); // no GPU

        var decision = engine.evaluateRoomPrimary("gpu-chamber");
        assertThat(decision.action()).isEqualTo(PlacementEngine.PlacementDecision.Action.KEEP);
        assertThat(decision.targetNodeId()).isNull();
    }

    // ── Conflict resolution ──

    @Test void conflictResolution_earliestTimestampWins() {
        engine.updateNodeSnapshot(makeSnapshot("a", 4096, 8000, 80, true,
            Set.of("inference"), List.of()));
        engine.updateNodeSnapshot(makeSnapshot("b", 4096, 8000, 80, true,
            Set.of("inference"), List.of()));

        var t1 = Instant.now().minusSeconds(10);
        var t2 = Instant.now();
        assertThat(engine.resolveClaimConflict("a", t1, "b", t2)).isEqualTo("a");
    }

    @Test void conflictResolution_tiedTimestampHigherScoreWins() {
        engine.updateNodeSnapshot(makeSnapshot("a", 2048, 8000, 80, false,
            Set.of("inference"), List.of())); // lower score
        engine.updateNodeSnapshot(makeSnapshot("b", 8192, 16000, 90, true,
            Set.of("inference", "gpu"), List.of())); // higher score

        var now = Instant.now();
        assertThat(engine.resolveClaimConflict("a", now, "b", now)).isEqualTo("b");
    }

    @Test void conflictResolution_tiedEverythingLexicographicWins() {
        engine.updateNodeSnapshot(makeSnapshot("alpha", 4096, 8000, 80, true,
            Set.of("inference"), List.of()));
        engine.updateNodeSnapshot(makeSnapshot("beta", 4096, 8000, 80, true,
            Set.of("inference"), List.of()));

        var now = Instant.now();
        assertThat(engine.resolveClaimConflict("beta", now, "alpha", now)).isEqualTo("alpha");
    }

    // ── Capability matching ──

    @Test void capabilitySnapshot_satisfiesRequirements() {
        var snap = makeSnapshot("a", 4096, 8000, 80, true,
            Set.of("inference", "gpu", "internet"), List.of());

        assertThat(snap.satisfiesRequirements(Set.of("inference"))).isTrue();
        assertThat(snap.satisfiesRequirements(Set.of("inference", "gpu"))).isTrue();
        assertThat(snap.satisfiesRequirements(Set.of("soulstore"))).isFalse();
        assertThat(snap.satisfiesRequirements(Set.of())).isTrue();
        assertThat(snap.satisfiesRequirements(null)).isTrue();
    }
}
