package org.wyrdsekai.core.forge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.core.story.ArcRegistry;
import org.wyrdsekai.core.story.Scene;
import org.wyrdsekai.core.story.StoryService;
import org.wyrdsekai.core.story.StoryStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §E.4 — AffinityLearner sleep-pass wire (CompanionActor:~11805).
 *
 * <p>Reproduces the same lambdas the CompanionActor sleep-pass uses:
 * <pre>
 *   netDelta(scene)  = scene.beatCount() &lt;= 1 ? null : (beats - 1) * 0.1
 *   postureKey(scene) = wantContext-based: "sat" / "stood" / "rested" / null
 * </pre>
 *
 * <p>Drives StoryService with synthetic events, lets it close scenes to disk,
 * then runs the same observations→drift pipeline the actor runs at sleep.
 * Decouples the wire's effect from the actor's heavyweight bootstrap.</p>
 */
class AffinityLearnerForgePassIT {

    private static final String FOCAL = "did:wyrd:focal";
    private static final String OTHER = "did:wyrd:other";
    private static final String ROOM = "test-room";

    /** Exact heuristic CompanionActor:~11829 uses for the sleep-pass. */
    private static Double netDelta(Scene s) {
        int beats = s.beatCount();
        return beats <= 1 ? null : (double) (beats - 1) * 0.1;
    }

    /** Exact heuristic CompanionActor:~11835 uses for the sleep-pass. */
    private static String postureKey(Scene s) {
        var wc = s.wantContext();
        if (wc == null) return null;
        var lower = wc.toLowerCase(Locale.ROOT);
        if (lower.contains("sit") || lower.contains("settle")) return "sat";
        if (lower.contains("stand")) return "stood";
        if (lower.contains("rest")) return "rested";
        return null;
    }

    /** Drive a multi-beat scene with a sit-themed wantContext to completion. */
    private static List<Scene> driveSittingScene(StoryService svc,
                                                                            Instant t0) throws Exception {
        svc.openScene(ROOM, t0, List.of(FOCAL, OTHER), "sitting at the hearth");
        svc.observe(new WorldEvent.PostureChanged(ROOM, t0.plusSeconds(2),
            FOCAL, "Focal", null,
            new Posture("sat", "leather chair", "Focal sat."))).toCompletableFuture().get();
        svc.observe(new WorldEvent.Said(ROOM, t0.plusSeconds(60),
            OTHER, "Other", "how was today", "en")).toCompletableFuture().get();
        svc.observe(new WorldEvent.PostureChanged(ROOM, t0.plusSeconds(900),
            FOCAL, "Focal",
            new Posture("sat", "leather chair", "Focal sat."), null))
            .toCompletableFuture().get();
        // Close per rule 1.
        svc.observe(new WorldEvent.EntityLeft(ROOM, t0.plusSeconds(905),
            FOCAL, "Focal", "out")).toCompletableFuture().get();
        return svc.recentClosedScenes(t0.minusSeconds(1));
    }

    @Test
    void multiBeatSittingScenePositivelyDriftsSatAffinity(@TempDir Path dir) throws Exception {
        var store = new StoryStore(dir);
        var svc = new StoryService(FOCAL, "Focal", store, new ArcRegistry(),
            StoryService.NULL_SYNTH);

        var t0 = Instant.now().minusSeconds(60);
        var closed = driveSittingScene(svc, t0);
        assertFalse(closed.isEmpty(), "scene closed + persisted");
        assertTrue(closed.get(0).beatCount() >= 2,
            "multi-beat scene built (Said + posture changes seal beats)");

        var holds = AffinityLearner.observationsFromScenes(closed,
            AffinityLearnerForgePassIT::netDelta,
            AffinityLearnerForgePassIT::postureKey);
        assertEquals(1, holds.size(), "one observation per multi-beat sit scene");
        assertEquals("sat", holds.get(0).postureKey());
        assertTrue(holds.get(0).netTankDelta() > 0, "positive net delta on multi-beat scene");

        // Drift from a neutral starting affinity.
        var initial = Map.of("sat", 0.0);
        var drifted = AffinityLearner.drift(initial, holds);
        assertTrue(drifted.get("sat") > 0.0,
            "positive observation drifts 'sat' affinity upward; got " + drifted);
        assertTrue(drifted.get("sat") <= AffinityLearner.CEILING,
            "affinity stays within ceiling");
    }

    @Test
    void soloOneBeatSceneProducesNoObservation(@TempDir Path dir) throws Exception {
        var store = new StoryStore(dir);
        var svc = new StoryService(FOCAL, "Focal", store, new ArcRegistry(),
            StoryService.NULL_SYNTH);

        // Solo scene — focal alone, opens and forceCloses with zero/one beat.
        var t0 = Instant.now();
        svc.openScene(ROOM, t0, List.of(FOCAL), "sitting alone");
        var closed = svc.forceCloseAll(t0.plusSeconds(5))
            .toCompletableFuture().get();
        assertEquals(1, closed.size());

        var holds = AffinityLearner.observationsFromScenes(closed,
            AffinityLearnerForgePassIT::netDelta,
            AffinityLearnerForgePassIT::postureKey);
        assertTrue(holds.isEmpty(),
            "single-beat solo scene has beats<=1; netDelta returns null → no observation");
    }

    @Test
    void wantContextWithoutPostureKeyProducesNoObservation(@TempDir Path dir) throws Exception {
        var store = new StoryStore(dir);
        var svc = new StoryService(FOCAL, "Focal", store, new ArcRegistry(),
            StoryService.NULL_SYNTH);

        var t0 = Instant.now().minusSeconds(60);
        // wantContext="companionship" — no sit/stand/rest token → key=null
        svc.openScene(ROOM, t0, List.of(FOCAL, OTHER), "companionship");
        svc.observe(new WorldEvent.Said(ROOM, t0.plusSeconds(2),
            OTHER, "Other", "hi", "en")).toCompletableFuture().get();
        svc.observe(new WorldEvent.Said(ROOM, t0.plusSeconds(4),
            OTHER, "Other", "how are you", "en")).toCompletableFuture().get();
        svc.observe(new WorldEvent.EntityLeft(ROOM, t0.plusSeconds(10),
            FOCAL, "Focal", "out")).toCompletableFuture().get();

        var closed = svc.recentClosedScenes(t0.minusSeconds(1));
        assertFalse(closed.isEmpty());
        var holds = AffinityLearner.observationsFromScenes(closed,
            AffinityLearnerForgePassIT::netDelta,
            AffinityLearnerForgePassIT::postureKey);
        assertTrue(holds.isEmpty(),
            "non-posture wantContext → postureKey returns null → no observation");
    }

    @Test
    void recentClosedScenesIsLoadablePostPersist(@TempDir Path dir) throws Exception {
        // Regression guard for the Scene JSON round-trip bug (the Forge wire
        // calls recentClosedScenes which reads back through StoryStore.loadScenes).
        var store = new StoryStore(dir);
        var svc = new StoryService(FOCAL, "Focal", store, new ArcRegistry(),
            StoryService.NULL_SYNTH);
        var t0 = Instant.now().minusSeconds(60);
        driveSittingScene(svc, t0);

        // Build a FRESH StoryService against the same store — simulates restart.
        var freshSvc = new StoryService(FOCAL, "Focal", store, new ArcRegistry(),
            StoryService.NULL_SYNTH);
        var recent = freshSvc.recentClosedScenes(t0.minusSeconds(1));
        assertFalse(recent.isEmpty(),
            "scenes survive restart — Scene JSON round-trips cleanly through StoryStore");
        assertEquals(FOCAL, recent.get(0).focalEntityId(),
            "round-tripped scene retains focal id");
        assertTrue(recent.get(0).beats().size() >= 2,
            "round-tripped scene retains beat list");
    }
}
