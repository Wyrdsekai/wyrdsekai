package org.wyrdsekai.core.story;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.SoulFragment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * inner-monologue wiring tests at the StoryService
 * level. Exercises the full close-scene → felt → inner-monologue chain via
 * the two synthesizer hooks ({@link StoryService.FeltSynthesizer} +
 * {@link StoryService.InnerMonologueSynthesizer}). Uses an in-memory
 * EPISODIC sink in lieu of the real SoulManifest / SqlSoulStore wiring
 * (which lives in CompanionActor) so the test stays under one second and
 * tests the contract the production wiring depends on.
 */
class InnerMonologueWiringTest {

    private static final String ROOM = "test-room";
    private static final String FOCAL = "did:wyrd:focal";
    private static final String OTHER = "did:wyrd:other";

    /**
     * Tiny in-memory stand-in for the CompanionActor manifest-fragment
     * persistence path. The test synthesizer mirrors the production
     * pipeline (idempotency by sceneId + recursion via prior EPISODIC).
     */
    static final class TestEpisodicSink {
        final List<SoulFragment> fragments = new ArrayList<>();
        final AtomicReference<String> lastPrompt = new AtomicReference<>();
        final AtomicInteger callCount = new AtomicInteger();
    }

    /**
     * Build a synthesizer that exercises the real pipeline: idempotency
     * check, top-3 prior EPISODIC for recursion, {@link StoryService#buildInnerMonologuePrompt}
     * for the actual prompt, and a caller-supplied prose function in place
     * of the voice call (so tests don't need :8201).
     */
    private static StoryService.InnerMonologueSynthesizer makeInnerSynth(
            TestEpisodicSink sink,
            Function<String, String> prose) {
        return (scene, focalName) -> {
            sink.callCount.incrementAndGet();
            // 1. Idempotency.
            boolean dup = sink.fragments.stream().anyMatch(f ->
                f.kind() == FragmentKind.EPISODIC && scene.id().equals(f.sceneId()));
            if (dup) return CompletableFuture.completedFuture(null);
            // 2. Recursion context.
            var prior = sink.fragments.stream()
                .filter(f -> f.kind() == FragmentKind.EPISODIC && !scene.id().equals(f.sceneId()))
                .sorted((a, b) -> b.firstObserved().compareTo(a.firstObserved()))
                .limit(3)
                .toList();
            // 3. Build the §10 prompt.
            var prompt = StoryService.buildInnerMonologuePrompt(scene, focalName, prior);
            sink.lastPrompt.set(prompt);
            // 4. "Voice call" — the test fixture supplies prose.
            var text = prose.apply(prompt);
            if (text == null || text.isBlank()) return CompletableFuture.completedFuture(null);
            // 5. Build + sink the EPISODIC fragment.
            var fragId = "episodic-" + scene.id();
            var label = "scene-" + scene.id();
            var fragment = SoulFragment.fromEpisodicScene(fragId, "episodic", label, text, scene.id());
            sink.fragments.add(fragment);
            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Drive a settle-and-leave scene to close.
     */
    private static Scene driveSettleScene(StoryService svc, Instant t0) throws Exception {
        svc.openScene(ROOM, t0, List.of(FOCAL, OTHER), "companionship");
        svc.observe(new WorldEvent.EntityEntered(ROOM, t0.plusSeconds(1),
            OTHER, "Other", "human", "in")).toCompletableFuture().get();
        svc.observe(new WorldEvent.PostureChanged(ROOM, t0.plusSeconds(2),
            FOCAL, "Ember", null,
            new Posture("sat", "leather chair", "Ember sat across from him."))).toCompletableFuture().get();
        svc.observe(new WorldEvent.Said(ROOM, t0.plusSeconds(30),
            OTHER, "Other", "long day.", "en")).toCompletableFuture().get();
        var closed = svc.observe(new WorldEvent.EntityLeft(ROOM, t0.plusSeconds(120),
            FOCAL, "Ember", "out")).toCompletableFuture().get();
        return closed.orElseThrow(() -> new AssertionError("scene did not close"));
    }

    // ─── Test 1: inner prose distinct from witness blockquote ─────────────

    @Test
    void innerProseIsDistinctFromWitnessBlockquote(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var sink = new TestEpisodicSink();
        var witness = "She watched him settle, and didn't fill the silence.";
        var inner = "I let him have the quiet. I almost said something. I didn't.";

        var svc = new StoryService(FOCAL, "Ember", store, arcs,
            (s, n) -> CompletableFuture.completedFuture(witness),
            makeInnerSynth(sink, prompt -> inner));

        var t0 = Instant.now();
        var closed = driveSettleScene(svc, t0);

        // Witness prose landed in the Scene record (via felt revision).
        var revised = Scene.latestRevisions(store.loadScenes(FOCAL,
            LocalDate.ofInstant(t0, ZoneId.systemDefault())));
        var last = revised.get(revised.size() - 1);
        assertEquals(witness, last.felt(), "witness blockquote = felt prose");
        // Inner prose landed in the EPISODIC sink.
        assertEquals(1, sink.fragments.size(), "exactly one EPISODIC fragment");
        var ep = sink.fragments.get(0);
        assertEquals(inner, ep.text(), "EPISODIC fragment carries the inner prose");
        assertNotEquals(witness, ep.text(),
            "witness and inner prose must be distinct strings (different prompts)");
        assertEquals(closed.id(), ep.sceneId(), "EPISODIC fragment sceneId matches closed scene");
    }

    // ─── Test 2: EPISODIC fragment lands with correct kind + sceneId ─────

    @Test
    void episodicFragmentLandsWithCorrectKindAndSceneId(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var sink = new TestEpisodicSink();
        var svc = new StoryService(FOCAL, "Ember", store, arcs,
            StoryService.NULL_SYNTH,
            makeInnerSynth(sink, p -> "Some private thought."));

        var t0 = Instant.now();
        var closed = driveSettleScene(svc, t0);

        assertEquals(1, sink.fragments.size());
        var ep = sink.fragments.get(0);
        assertEquals(FragmentKind.EPISODIC, ep.kind(), "fragment kind is EPISODIC");
        assertEquals(closed.id(), ep.sceneId(), "sceneId equals the closed scene id");
        assertEquals("episodic-" + closed.id(), ep.id(), "fragment id follows the episodic-<sceneId> convention");
        assertNotNull(ep.firstObserved(), "fragment timestamped at creation");
    }

    // ─── Test 3: idempotent — re-closing scene doesn't double-write ──────

    @Test
    void idempotentOnSceneId(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var sink = new TestEpisodicSink();
        var svc = new StoryService(FOCAL, "Ember", store, arcs,
            StoryService.NULL_SYNTH,
            makeInnerSynth(sink, p -> "Same thought twice."));

        var t0 = Instant.now();
        var closed = driveSettleScene(svc, t0);
        assertEquals(1, sink.fragments.size(), "first close writes one fragment");

        // Replay the same scene's renderPending — felt synthesizer is a real one
        // now so it'll re-enter chainInnerMonologueAfterFelt. Idempotency should
        // suppress a second EPISODIC write.
        var svc2 = new StoryService(FOCAL, "Ember", store, arcs,
            (s, n) -> CompletableFuture.completedFuture("witness retry"),
            makeInnerSynth(sink, p -> "Same thought twice."));
        var today = LocalDate.ofInstant(t0, ZoneId.systemDefault());
        svc2.renderPending(today, today).toCompletableFuture().get();

        assertEquals(1, sink.fragments.size(),
            "idempotency on sceneId — second close does not duplicate the fragment");
        // The synthesizer is still invoked (so it can check idempotency); it just
        // doesn't add a second fragment.
        assertTrue(sink.callCount.get() >= 1, "synthesizer was called");
    }

    // ─── Test 5: recursion — prior EPISODIC text lands in next prompt ────

    @Test
    void priorEpisodicAppearsInNextInnerPrompt(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var sink = new TestEpisodicSink();
        // Two scenes; the second's inner prose should see the first's text.
        var counter = new AtomicInteger();
        var svc = new StoryService(FOCAL, "Ember", store, arcs,
            StoryService.NULL_SYNTH,
            makeInnerSynth(sink, p ->
                "scene-" + counter.incrementAndGet() + "-inner"));

        var t0 = Instant.now().minusSeconds(3600);
        driveSettleScene(svc, t0);
        assertEquals(1, sink.fragments.size(), "first scene writes EPISODIC");
        var firstFragmentText = sink.fragments.get(0).text();

        // Wait + drive a second scene. The second scene's prompt should include
        // the first fragment's text as recursion context.
        var t1 = Instant.now();
        driveSettleScene(svc, t1);
        assertEquals(2, sink.fragments.size(), "second scene writes a second EPISODIC");

        var lastPrompt = sink.lastPrompt.get();
        assertNotNull(lastPrompt, "synthesizer captured the last prompt");
        assertTrue(lastPrompt.contains(firstFragmentText),
            "second inner-monologue prompt contains prior EPISODIC fragment text "
                + "(recursion spine) — prompt:\n" + lastPrompt);
        // And the bullet marker should be present.
        assertTrue(lastPrompt.contains("What you remember thinking the last few times"),
            "prompt includes the recursion preamble");
    }

    // ─── Test 7: cross-perspective sceneId matches journal mirror marker ─

    @Test
    void fragmentSceneIdMatchesJournalMirrorMarker(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        var sink = new TestEpisodicSink();
        var svc = new StoryService(FOCAL, "Ember", store, arcs,
            (s, n) -> CompletableFuture.completedFuture("witness for journal mirror"),
            makeInnerSynth(sink, p -> "private inner notice"));

        var t0 = Instant.now();
        var closed = driveSettleScene(svc, t0);

        // The journal markdown should carry the SCENE_ID_MARKER_PREFIX with the
        // same id stamped on the EPISODIC fragment.
        var today = LocalDate.ofInstant(t0, ZoneId.systemDefault());
        var bio = tempDir.resolve("biography").resolve(FOCAL).resolve(today + ".md");
        assertTrue(Files.exists(bio), "journal exists");
        var body = Files.readString(bio);
        var marker = StoryStore.SCENE_ID_MARKER_PREFIX + closed.id() + " -->";
        assertTrue(body.contains(marker),
            "journal contains the §14 sceneId marker for the closed scene — "
                + "expected substring '" + marker + "'");
        // The EPISODIC fragment's sceneId equals what's in the marker.
        assertEquals(1, sink.fragments.size());
        assertEquals(closed.id(), sink.fragments.get(0).sceneId(),
            "§10 EPISODIC sceneId matches §14 journal marker — cross-perspective lookup works");
    }

    // ─── Bonus: inner-monologue failure is fail-soft (no scene corruption) ─

    @Test
    void innerSynthesizerFailureIsFailSoft(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        // Synthesizer throws — chainInnerMonologueAfterFelt must absorb it
        // (log + continue) so the scene close still returns normally.
        StoryService.InnerMonologueSynthesizer crashy = (s, n) ->
            CompletableFuture.failedFuture(new RuntimeException("voice :8201 down"));
        var svc = new StoryService(FOCAL, "Ember", store, arcs,
            (s, n) -> CompletableFuture.completedFuture("witness ok"), crashy);

        var t0 = Instant.now();
        var closed = driveSettleScene(svc, t0);
        assertNotNull(closed, "scene closed normally despite inner synth failure");
        // No EPISODIC fragment — and no exception propagated.
    }
}
