package org.wyrdsekai.core.embodiment;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.InnerImprint;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.core.agent.PostureHoldEffect;
import org.wyrdsekai.core.empathy.MirrorResonance;
import org.wyrdsekai.core.forge.AffinityLearner;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.story.Beat;
import org.wyrdsekai.core.story.BeatTrigger;
import org.wyrdsekai.core.story.Scene;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for Phase E/F primitives.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link PostureHoldEffect} — affinity-modulated per-tick deltas with
 *       atObject/verb/prefix/default cascade.</li>
 *   <li>{@link MirrorResonance#posturalEcho} — drive-modulated echo formula
 *       with RelationalState multiplier.</li>
 *   <li>{@link AffinityLearner#drift} — sleep-pass drift with clamp/step.</li>
 *   <li>{@link Scene#latestRevisions} — revision-chain filter.</li>
 * </ul>
 */
class EmbodimentPrimitivesTest {

    private Posture leatherChairSit() {
        var imprint = InnerImprint.ofTanks(Map.of("equanimity", 0.02, "soothing", 0.01));
        return new Posture("sat", "leather_chair",
            "settled into the worn leather chair, facing the hearth",
            Instant.now(), imprint);
    }

    @Test
    void postureHoldDefaultAffinityIsOne() {
        var deltas = PostureHoldEffect.tankDeltas(leatherChairSit(), null);
        assertEquals(0.02, deltas.get("equanimity"), 1e-9);
        assertEquals(0.01, deltas.get("soothing"), 1e-9);
    }

    @Test
    void postureHoldAffinityNegativeFlipsSign() {
        var deltas = PostureHoldEffect.tankDeltas(
            leatherChairSit(), Map.of("leather_chair", -0.5));
        assertEquals(-0.01, deltas.get("equanimity"), 1e-9);
        assertEquals(-0.005, deltas.get("soothing"), 1e-9);
    }

    @Test
    void postureHoldCascadeAtObjectBeatsVerb() {
        var deltas = PostureHoldEffect.tankDeltas(
            leatherChairSit(),
            Map.of("leather_chair", 1.5, "sat", 0.5));
        // atObject "leather_chair" wins → 1.5×
        assertEquals(0.03, deltas.get("equanimity"), 1e-9);
    }

    @Test
    void postureHoldCascadeFallsBackToVerb() {
        var deltas = PostureHoldEffect.tankDeltas(
            leatherChairSit(), Map.of("sat", 0.5));
        assertEquals(0.01, deltas.get("equanimity"), 1e-9);
    }

    @Test
    void postureHoldCascadePrefixMatchesClass() {
        var deltas = PostureHoldEffect.tankDeltas(
            leatherChairSit(), Map.of("chair", 2.0));
        // "chair" is a substring of "leather_chair" but not equal → class match
        assertEquals(0.04, deltas.get("equanimity"), 1e-9);
    }

    @Test
    void postureHoldNoImprintReturnsEmpty() {
        var posture = new Posture("stood", null, "standing by the window",
            Instant.now(), null);
        assertTrue(PostureHoldEffect.tankDeltas(posture, Map.of()).isEmpty());
    }

    @Test
    void posturalEchoFormulaMatchesSpec() {
        // base=0.2, care=0.5, equanimity=0.4, OPEN=1.0, arc=1.0
        // → 0.2 × 0.5 × 0.4 × 1.0 × 1.0 = 0.04
        double echo = MirrorResonance.posturalEcho(
            0.5, 0.4, Bond.RelationalState.OPEN, 1.0);
        assertEquals(0.04, echo, 1e-9);
    }

    @Test
    void posturalEchoGuardedCutsByMultiplier() {
        // 0.2 × 1.0 × 1.0 × 0.4 × 1.0 = 0.08
        double open = MirrorResonance.posturalEcho(
            1.0, 1.0, Bond.RelationalState.OPEN, 1.0);
        double guarded = MirrorResonance.posturalEcho(
            1.0, 1.0, Bond.RelationalState.GUARDED, 1.0);
        assertEquals(open * 0.4, guarded, 1e-9);
    }

    @Test
    void posturalEchoBrokenIsZero() {
        double echo = MirrorResonance.posturalEcho(
            1.0, 1.0, Bond.RelationalState.BROKEN, 1.0);
        assertEquals(0.0, echo, 1e-9);
    }

    @Test
    void posturalEchoNullRelationalStateDefaultsOpen() {
        double withNull = MirrorResonance.posturalEcho(1.0, 1.0, null, 1.0);
        double withOpen = MirrorResonance.posturalEcho(
            1.0, 1.0, Bond.RelationalState.OPEN, 1.0);
        assertEquals(withOpen, withNull, 1e-9);
    }

    @Test
    void posturalEchoArcConflictHalves() {
        double normal = MirrorResonance.posturalEcho(
            1.0, 1.0, Bond.RelationalState.OPEN, 1.0);
        double conflict = MirrorResonance.posturalEcho(
            1.0, 1.0, Bond.RelationalState.OPEN, 0.5);
        assertEquals(normal * 0.5, conflict, 1e-9);
    }

    @Test
    void affinityDriftPositiveTargets14() {
        var drifted = AffinityLearner.drift(
            Map.of("leather_chair", 1.0),
            List.of(new AffinityLearner.HoldObservation("leather_chair", 0.5)));
        // 1.0 + 0.05 × (1.4 - 1.0) = 1.02
        assertEquals(1.02, drifted.get("leather_chair"), 1e-9);
    }

    @Test
    void affinityDriftNegativeTargets06() {
        var drifted = AffinityLearner.drift(
            Map.of("leather_chair", 1.0),
            List.of(new AffinityLearner.HoldObservation("leather_chair", -0.2)));
        // 1.0 + 0.05 × (0.6 - 1.0) = 0.98
        assertEquals(0.98, drifted.get("leather_chair"), 1e-9);
    }

    @Test
    void affinityDriftClampsBelowFloor() {
        var drifted = AffinityLearner.drift(
            Map.of("leather_chair", -1.5),
            List.of(new AffinityLearner.HoldObservation("leather_chair", -2.0)));
        assertTrue(drifted.get("leather_chair") >= AffinityLearner.FLOOR);
    }

    @Test
    void affinityDriftHandlesNullCurrent() {
        var drifted = AffinityLearner.drift(null,
            List.of(new AffinityLearner.HoldObservation("sat", 0.5)));
        // Starting from default 1.0: 1.0 + 0.05 × 0.4 = 1.02
        assertEquals(1.02, drifted.get("sat"), 1e-9);
    }

    @Test
    void affinityDriftMultipleScenesCompound() {
        var drifted = AffinityLearner.drift(
            Map.of("leather_chair", 1.0),
            List.of(
                new AffinityLearner.HoldObservation("leather_chair", 0.5),
                new AffinityLearner.HoldObservation("leather_chair", 0.5)));
        // First scene: 1.0 + 0.05*(1.4-1.0) = 1.02
        // Second scene: 1.02 + 0.05*(1.4-1.02) = 1.039
        assertEquals(1.039, drifted.get("leather_chair"), 1e-9);
    }

    @Test
    void sceneLatestRevisionsFiltersChain() {
        var start = Instant.now();
        var s1 = new Scene("scene-1", List.of(), "study", "did:wyrd:m",
            List.of("did:wyrd:m"), start, start.plusSeconds(60),
            "rest", List.of(), null, true, 1L);
        var s1Rev = s1.asRevision("scene-1b",
            "felt: the chair settled around me.", false);
        var s2 = new Scene("scene-2", List.of(), "study", "did:wyrd:m",
            List.of("did:wyrd:m"), start.plusSeconds(70), start.plusSeconds(130),
            "work", List.of(), null, true, 2L);
        var latest = Scene.latestRevisions(List.of(s1, s1Rev, s2));
        assertEquals(2, latest.size());
        assertTrue(latest.stream().anyMatch(s -> s.id().equals("scene-1b")));
        assertTrue(latest.stream().anyMatch(s -> s.id().equals("scene-2")));
        assertFalse(latest.stream().anyMatch(s -> s.id().equals("scene-1")));
    }

    @Test
    void sceneAsRevisionThreadsReplacesId() {
        var start = Instant.now();
        var s1 = new Scene("scene-1", List.of(), "study", "did:wyrd:m",
            List.of("did:wyrd:m"), start, start.plusSeconds(60),
            "rest", List.of(new Beat("beat-1", "scene-1",
                BeatTrigger.CAST_CHANGE,
                start, start.plusSeconds(10), List.of(), "Settled.")),
            null, true, 1L);
        var revised = s1.asRevision("scene-1b", "felt prose now in.", false);
        assertEquals("scene-1", revised.replacesId());
        assertEquals("scene-1b", revised.id());
        assertFalse(revised.needsRendering());
        assertEquals(1, revised.beatCount());
    }
}
