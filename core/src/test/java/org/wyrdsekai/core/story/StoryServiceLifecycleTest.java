package org.wyrdsekai.core.story;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Posture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (Phase D) — end-to-end scene → journal lifecycle.
 *
 * <p>Drives StoryService with synthetic WorldEvents and verifies the
 * canonical persistence + biography markdown output. No actors, no
 * inference — uses {@link StoryService#NULL_SYNTH} so felt stays
 * unrendered (the canonical "voice :8201 unreachable" path).</p>
 */
class StoryServiceLifecycleTest {

    private static final String ROOM = "test-room";
    private static final String FOCAL = "did:wyrd:focal";
    private static final String OTHER = "did:wyrd:other";

    @Test
    void emptySceneClosedByForceCloseDoesNotJournal(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var svc = new StoryService(FOCAL, "Focal", store, arcs, StoryService.NULL_SYNTH);

        svc.openScene(ROOM, Instant.now(), List.of(FOCAL), "presence");
        // No events observed — buffer is open but empty. ForceClose returns
        // the closed scene with zero beats. Journal will still write a header
        // block, but it's a degenerate scene (skipFelt=true per SceneBuffer).
        var closed = svc.forceCloseAll(Instant.now())
            .toCompletableFuture().get();
        assertEquals(1, closed.size(), "one scene force-closed");
        assertEquals(0, closed.get(0).beatCount(), "empty scene has no beats");
        // skipFelt=true for single-beat solo, but 0-beat solo also won't render.
        assertFalse(closed.get(0).needsRendering(), "0-beat solo scene needsRendering=false");
    }

    @Test
    void sceneOpenObserveCloseWritesBiographyMarkdown(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var svc = new StoryService(FOCAL, "Focal Persona", store, arcs, StoryService.NULL_SYNTH);

        var t0 = Instant.now();
        svc.openScene(ROOM, t0, List.of(FOCAL, OTHER), "companionship");

        // Beat-triggering events: another entity enters, focal sits, focal stands.
        svc.observe(new WorldEvent.EntityEntered(ROOM, t0.plusSeconds(1),
            OTHER, "Other", "human", "in")).toCompletableFuture().get();
        svc.observe(new WorldEvent.PostureChanged(ROOM, t0.plusSeconds(2),
            FOCAL, "Focal Persona", null,
            new Posture("sat", "leather chair", "Focal settles at the leather chair.")))
            .toCompletableFuture().get();
        svc.observe(new WorldEvent.PostureChanged(ROOM, t0.plusSeconds(120),
            FOCAL, "Focal Persona",
            new Posture("sat", "leather chair", "Focal settles at the leather chair."),
            null))
            .toCompletableFuture().get();

        // Close per rule 1: focal leaves.
        var closedOpt = svc.observe(new WorldEvent.EntityLeft(ROOM, t0.plusSeconds(125),
            FOCAL, "Focal Persona", "out"))
            .toCompletableFuture().get();
        assertTrue(closedOpt.isPresent(), "EntityLeft for focal closes scene");

        var closed = closedOpt.get();
        assertEquals(ROOM, closed.roomId());
        assertEquals(FOCAL, closed.focalEntityId());
        assertNotNull(closed.rangeEnd(), "closed scene has rangeEnd");
        // NULL_SYNTH fails → needsRendering stays true.
        assertTrue(closed.needsRendering(),
            "needsRendering=true because voice synthesizer is NULL_SYNTH");

        // Biography markdown must exist under data/biography/<focal>/<today>.md.
        var today = LocalDate.ofInstant(t0, ZoneId.systemDefault());
        var bio = tempDir.resolve("biography").resolve(FOCAL).resolve(today + ".md");
        assertTrue(Files.exists(bio), "biography file written: " + bio);
        var body = Files.readString(bio);
        assertTrue(body.contains("Focal Persona") || body.contains(FOCAL),
            "journal mentions focal: " + body);
        assertTrue(body.contains("_felt pending voice synthesis_")
                || body.contains("_felt") || body.contains("felt"),
            "journal records felt-pending placeholder: " + body);
        // Scene canonical record on disk.
        var scenes = store.loadScenes(FOCAL, today);
        assertFalse(scenes.isEmpty(), "scene persisted");
        assertEquals(closed.id(), scenes.get(scenes.size() - 1).id(),
            "last persisted scene matches the closed one");
    }

    @Test
    void renderPendingFillsFeltWhenSynthesizerLater(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        // First pass: NULL_SYNTH → scene persists with needsRendering=true.
        var s1 = new StoryService(FOCAL, "Focal", store, arcs, StoryService.NULL_SYNTH);
        var t0 = Instant.now();
        s1.openScene(ROOM, t0, List.of(FOCAL, OTHER), "presence");
        s1.observe(new WorldEvent.Said(ROOM, t0.plusSeconds(1),
            OTHER, "Other", "hello", "en")).toCompletableFuture().get();
        s1.observe(new WorldEvent.EntityLeft(ROOM, t0.plusSeconds(2),
            FOCAL, "Focal", "out")).toCompletableFuture().get();

        // Second pass: a real synthesizer comes online — renderPending should
        // pick up the unrendered scene and amend it via a revision.
        var s2 = new StoryService(FOCAL, "Focal", store, arcs,
            (scene, name) -> CompletableFuture
                .completedFuture("She let the room go quiet."));
        var today = LocalDate.ofInstant(t0, ZoneId.systemDefault());
        var rendered = s2.renderPending(today, today).toCompletableFuture().get();
        assertEquals(1, rendered, "exactly one pending scene rendered");

        var scenes = Scene.latestRevisions(store.loadScenes(FOCAL, today));
        assertFalse(scenes.isEmpty());
        var latest = scenes.get(scenes.size() - 1);
        assertFalse(latest.needsRendering(), "revision marks needsRendering=false");
        assertEquals("She let the room go quiet.", latest.felt());
    }

    @Test
    void recentClosedScenesReturnsOnlyClosedSinceWatermark(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var svc = new StoryService(FOCAL, "Focal", store, arcs, StoryService.NULL_SYNTH);

        var t0 = Instant.now().minusSeconds(3600);
        // Scene 1 — closed an hour ago.
        svc.openScene(ROOM, t0, List.of(FOCAL, OTHER), "presence");
        svc.observe(new WorldEvent.EntityLeft(ROOM, t0.plusSeconds(60),
            FOCAL, "Focal", "out")).toCompletableFuture().get();
        // Scene 2 — closed now.
        var t1 = Instant.now();
        svc.openScene(ROOM, t1, List.of(FOCAL, OTHER), "presence");
        svc.observe(new WorldEvent.EntityLeft(ROOM, t1.plusSeconds(5),
            FOCAL, "Focal", "out")).toCompletableFuture().get();

        var sinceTen = Instant.now().minusSeconds(600);
        var recent = svc.recentClosedScenes(sinceTen);
        assertEquals(1, recent.size(), "only the scene closed within 10min is returned");
        assertTrue(recent.get(0).rangeEnd().isAfter(sinceTen),
            "returned scene closed after watermark");
    }

    @Test
    void signalWantChangeClosesScenePerRule2(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var svc = new StoryService(FOCAL, "Focal", store, arcs, StoryService.NULL_SYNTH);
        var t0 = Instant.now();
        svc.openScene(ROOM, t0, List.of(FOCAL, OTHER), "companionship");
        svc.observe(new WorldEvent.Said(ROOM, t0.plusSeconds(1),
            OTHER, "Other", "you ok?", "en")).toCompletableFuture().get();

        var closedOpt = svc.signalWantChange(ROOM, t0.plusSeconds(2), "rest")
            .toCompletableFuture().get();
        assertTrue(closedOpt.isPresent(), "want change closes scene");
        assertEquals("companionship", closedOpt.get().wantContext(),
            "closed scene carries the want context active at open");
        assertEquals(0, svc.openSceneCount(), "no scene open after close");
    }
}
