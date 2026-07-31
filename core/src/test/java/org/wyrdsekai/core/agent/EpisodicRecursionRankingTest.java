package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.story.Beat;
import org.wyrdsekai.core.story.BeatTrigger;
import org.wyrdsekai.core.story.Scene;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cosine-similarity ranking of prior EPISODIC fragments
 * for the inner-monologue recursion context, with most-recent-first as the
 * fallback when {@code EmbeddingService} is unavailable (the test environment
 * doesn't initialize one).
 */
class EpisodicRecursionRankingTest {

    @BeforeEach
    void clearEmbeddingService() {
        // Ensure the recency-fallback path fires regardless of which other
        // tests ran first in this JVM (EmbeddingService is a process-wide
        // singleton; once any test calls init() it sticks). Without this
        // reset, rankPriorEpisodicForRecursion runs the cosine path and
        // produces semantic ordering instead of the recency ordering this
        // suite asserts.
        EmbeddingService.resetForTests();
    }

    private static SoulFragment episodic(String id, String sceneId, String text, Instant when) {
        return SoulFragment.fromEpisodicScene(id, "episodic", "scene-" + sceneId, text, sceneId)
            .withSceneId(sceneId)  // no-op — fromEpisodicScene already stamps it; here for clarity
            .reinforce();  // bumps lastConfirmed without changing firstObserved
    }

    private static SoulFragment episodicAt(String id, String sceneId, String text, Instant firstObserved) {
        return new SoulFragment(id, "episodic", "scene-" + sceneId, text,
            null, null, false, 0.5f, 0,
            firstObserved, null, null, null, null,
            FragmentKind.EPISODIC, sceneId);
    }

    private static final AtomicInteger BEAT_SEQ = new AtomicInteger();

    private static Scene sceneWithBeats(String id, String want, String... anchors) {
        var beats = new ArrayList<Beat>();
        var t = Instant.now();
        for (var a : anchors) {
            beats.add(new Beat(
                "beat-" + BEAT_SEQ.incrementAndGet(),
                id,
                BeatTrigger.TACTIC_CHANGE,
                t.minusSeconds(30),
                t,
                List.of(),
                a));
        }
        return new Scene(id, List.of(), "room-test", "did:wyrd:focal",
            List.of("did:wyrd:focal"), t.minusSeconds(60), t,
            want, beats, null, true, 1L);
    }

    @Test
    void recencyFallbackWhenEmbeddingServiceUnavailable() {
        // The test JVM never inits EmbeddingService, so rankPriorEpisodicForRecursion
        // falls through to recency order. Verifies the fallback wire works.
        var older = episodicAt("ep-old", "scene-old", "older inner notice",
            Instant.now().minusSeconds(3600));
        var newer = episodicAt("ep-new", "scene-new", "newer inner notice",
            Instant.now().minusSeconds(60));
        var scene = sceneWithBeats("scene-current", "presence", "hearth firelight long quiet");

        var ranked = CompanionActor.rankPriorEpisodicForRecursion(
            scene, List.of(older, newer), 3);

        assertEquals(2, ranked.size());
        assertEquals("ep-new", ranked.get(0).id(),
            "recency-fallback puts most-recent first");
        assertEquals("ep-old", ranked.get(1).id());
    }

    @Test
    void recencyFallbackRespectsK() {
        var t = Instant.now();
        var frags = new ArrayList<SoulFragment>();
        for (int i = 0; i < 5; i++) {
            frags.add(episodicAt("ep-" + i, "scene-" + i, "inner " + i,
                t.minusSeconds(60L * (5 - i))));
        }
        var ranked = CompanionActor.rankPriorEpisodicForRecursion(
            sceneWithBeats("scene-current", "presence", "x"), frags, 3);

        assertEquals(3, ranked.size(), "respects k");
        // Most recent first: ep-4 (most recent), ep-3, ep-2
        assertEquals(List.of("ep-4", "ep-3", "ep-2"),
            ranked.stream().map(SoulFragment::id).toList());
    }

    @Test
    void recencyFallbackHandlesNullFirstObserved() {
        var withTime = episodicAt("ep-time", "s1", "with time", Instant.now());
        // build a fragment with null firstObserved using the 14-arg ctor
        var withoutTime = new SoulFragment("ep-no-time", "episodic", "scene-s2",
            "no time",
            null, null, false, 0.5f, 0,
            null, null, null, null, null,
            FragmentKind.EPISODIC, "s2");
        var ranked = CompanionActor.rankPriorEpisodicForRecursion(
            sceneWithBeats("s-now", "presence", "x"),
            List.of(withoutTime, withTime), 5);
        assertEquals(2, ranked.size());
        assertEquals("ep-time", ranked.get(0).id(),
            "non-null firstObserved sorts ahead of null");
    }

    @Test
    void emptyCandidatesReturnsEmpty() {
        assertTrue(CompanionActor.rankPriorEpisodicForRecursion(
            sceneWithBeats("s", "presence", "x"), List.of(), 3).isEmpty());
        assertTrue(CompanionActor.rankPriorEpisodicForRecursion(
            sceneWithBeats("s", "presence", "x"), null, 3).isEmpty());
    }

    @Test
    void zeroKReturnsEmpty() {
        var ep = episodicAt("ep-1", "s1", "text", Instant.now());
        assertTrue(CompanionActor.rankPriorEpisodicForRecursion(
            sceneWithBeats("s", "presence", "x"), List.of(ep), 0).isEmpty());
    }

    @Test
    void recencyByRecencyHelperWorksStandalone() {
        var t = Instant.now();
        var a = episodicAt("ep-a", "sa", "a", t.minusSeconds(30));
        var b = episodicAt("ep-b", "sb", "b", t.minusSeconds(10));
        var c = episodicAt("ep-c", "sc", "c", t.minusSeconds(20));
        var ranked = CompanionActor.rankPriorEpisodicByRecency(List.of(a, b, c), 2);
        assertEquals(2, ranked.size());
        assertEquals("ep-b", ranked.get(0).id(), "most recent first");
        assertEquals("ep-c", ranked.get(1).id());
    }

    @Test
    void buildSceneRecursionQueryCombinesWantAndBeats() {
        var scene = sceneWithBeats("s-1", "companionship",
            "she sat across from him", "neither of them filled the silence");
        var q = CompanionActor.buildSceneRecursionQuery(scene);
        assertNotNull(q);
        assertTrue(q.contains("companionship"), "query includes wantContext: " + q);
        assertTrue(q.contains("she sat across from him"), "query includes first beat: " + q);
        assertTrue(q.contains("neither of them filled the silence"),
            "query includes second beat: " + q);
    }

    @Test
    void buildSceneRecursionQueryHandlesBlankWant() {
        var scene = sceneWithBeats("s-1", "", "only the beat anchor matters here");
        var q = CompanionActor.buildSceneRecursionQuery(scene);
        assertNotNull(q);
        assertEquals("only the beat anchor matters here", q.trim());
    }

    @Test
    void buildSceneRecursionQueryHandlesNullScene() {
        assertEquals("", CompanionActor.buildSceneRecursionQuery(null));
    }

    // ── DECENTMEM τ-floor ────────────────────────────

    @Test
    void tauFloorAdmitsOnlyAboveThreshold() {
        var weak = episodicAt("ep-weak", "s1", "weak match", Instant.now());
        var strong = episodicAt("ep-strong", "s2", "strong match", Instant.now());
        // weak=0.10 below floor, strong=0.55 above → only strong admitted.
        var ranked = CompanionActor.selectAboveTauFloor(
            List.of(weak, strong), new float[]{0.10f, 0.55f}, 0.20, 3);
        assertEquals(1, ranked.size(), "below-floor candidate is dropped");
        assertEquals("ep-strong", ranked.get(0).id());
    }

    @Test
    void tauFloorReturnsEmptyWhenNoneClear() {
        // The DECENTMEM move: nothing clears the floor → run the monologue with
        // NO stale recursion context (explore fresh) rather than confabulate.
        var a = episodicAt("ep-a", "s1", "a", Instant.now());
        var b = episodicAt("ep-b", "s2", "b", Instant.now());
        var ranked = CompanionActor.selectAboveTauFloor(
            List.of(a, b), new float[]{0.05f, 0.12f}, 0.20, 3);
        assertTrue(ranked.isEmpty(), "no fragment clears the floor → empty");
    }

    @Test
    void tauFloorZeroDisablesGateAndKeepsTopK() {
        // floor <= 0 = pure top-K (pre-DECENTMEM behaviour): even weak matches
        // are admitted, highest-score first.
        var a = episodicAt("ep-a", "s1", "a", Instant.now());
        var b = episodicAt("ep-b", "s2", "b", Instant.now());
        var c = episodicAt("ep-c", "s3", "c", Instant.now());
        var ranked = CompanionActor.selectAboveTauFloor(
            List.of(a, b, c), new float[]{0.02f, 0.40f, 0.15f}, 0.0, 2);
        assertEquals(2, ranked.size(), "respects k");
        assertEquals(List.of("ep-b", "ep-c"),
            ranked.stream().map(SoulFragment::id).toList(),
            "highest score first, weakest dropped only by k not floor");
    }

    @Test
    void tauFloorRanksAboveFloorByScoreDescending() {
        var a = episodicAt("ep-a", "s1", "a", Instant.now());
        var b = episodicAt("ep-b", "s2", "b", Instant.now());
        var c = episodicAt("ep-c", "s3", "c", Instant.now());
        var ranked = CompanionActor.selectAboveTauFloor(
            List.of(a, b, c), new float[]{0.30f, 0.80f, 0.55f}, 0.20, 5);
        assertEquals(List.of("ep-b", "ep-c", "ep-a"),
            ranked.stream().map(SoulFragment::id).toList());
    }

    @Test
    void tauFloorEmptyAndZeroKGuards() {
        assertTrue(CompanionActor.selectAboveTauFloor(
            List.of(), new float[]{}, 0.20, 3).isEmpty());
        assertTrue(CompanionActor.selectAboveTauFloor(
            null, new float[]{0.5f}, 0.20, 3).isEmpty());
        var a = episodicAt("ep-a", "s1", "a", Instant.now());
        assertTrue(CompanionActor.selectAboveTauFloor(
            List.of(a), new float[]{0.9f}, 0.20, 0).isEmpty(), "k=0 → empty");
    }
}
