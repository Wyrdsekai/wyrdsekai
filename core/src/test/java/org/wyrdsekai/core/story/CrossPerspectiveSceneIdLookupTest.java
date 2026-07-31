package org.wyrdsekai.core.story;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.soul.SoulFragmentStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cross-perspective sceneId lookup integration.
 *
 * <p>Proves the §14 promise: a closed scene resolves to the same {@code sceneId}
 * on both sides of the perspective split — the companion's EPISODIC fragment
 * (in the soul) and the bondholder's journal mirror entry (HTML-comment
 * marker in markdown). The integration:</p>
 *
 * <ol>
 *   <li>Drive a scene to close via StoryService — produces journal markdown
 *       with the §14 marker AND (via the inner-monologue synthesizer) an
 *       EPISODIC fragment.</li>
 *   <li>{@link StoryStore#focalsWithJournalEntryForScene(String)} resolves
 *       the marker → focal id.</li>
 *   <li>{@link SoulFragmentStore#loadBySceneId(String, String)} resolves the
 *       same sceneId → EPISODIC fragment in the companion's soul.</li>
 *   <li>Both sides match. "Do you remember that night by the fire" resolves
 *       to the same scene on the companion soul + the bondholder journal
 *       without similarity search.</li>
 * </ol>
 */
class CrossPerspectiveSceneIdLookupTest {

    private static final String FOCAL = "did:wyrd:companion:ember";
    private static final String OTHER = "did:wyrd:human:operator";
    private static final String ROOM = "study";

    private String jdbcUrl;
    private SoulFragmentStore fragmentStore;

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "xperspective-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        // Use the fresh-schema path — both kind + scene_id present from the start.
        try (var stmt = keepAlive.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS soul_fragments(
                  did                  TEXT NOT NULL,
                  fragment_id          TEXT NOT NULL,
                  category             TEXT NOT NULL DEFAULT 'memory',
                  label                TEXT,
                  fragment_text        TEXT,
                  embedding            BLOB,
                  embedding_model      TEXT,
                  formative            INTEGER NOT NULL DEFAULT 0,
                  confidence           REAL NOT NULL DEFAULT 0.5,
                  reinforcement_count  INTEGER NOT NULL DEFAULT 0,
                  first_observed       INTEGER,
                  last_confirmed       INTEGER,
                  valid_from           INTEGER,
                  superseded_at        INTEGER,
                  superseded_by        TEXT,
                  ordinal              INTEGER NOT NULL DEFAULT 0,
                  updated_at           INTEGER NOT NULL DEFAULT (unixepoch()),
                  kind                 TEXT NOT NULL DEFAULT 'NARRATIVE',
                  scene_id             TEXT,
                  PRIMARY KEY (did, fragment_id)
                )
                """);
        }
        fragmentStore = new SoulFragmentStore(jdbcUrl);
    }

    @Test
    void closed_scene_resolves_to_same_sceneId_in_journal_and_soul(@TempDir Path tempDir)
            throws Exception {
        // 1. Drive a scene to close. Use a synthesizer that lands a known EPISODIC
        //    fragment via fragmentStore — mimics what CompanionActor.persistInnerMonologueFragment
        //    does, but persists straight into the test store.
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();

        StoryService.InnerMonologueSynthesizer innerSink = (scene, focalName) -> {
            var fragment = SoulFragment.fromEpisodicScene(
                "episodic-" + scene.id(),
                "episodic",
                "scene-" + scene.id(),
                "I let him have the quiet. There was something about firelight on his hands.",
                scene.id());
            fragmentStore.replaceAll(FOCAL, List.of(fragment));
            return CompletableFuture.completedFuture(null);
        };

        var svc = new StoryService(FOCAL, "Ember", store, arcs,
            (s, n) -> CompletableFuture.completedFuture(
                "She let the room go quiet. He didn't need to fill it."),
            innerSink);

        var t0 = Instant.now();
        svc.openScene(ROOM, t0, List.of(FOCAL, OTHER), "companionship");
        svc.observe(new WorldEvent.EntityEntered(ROOM, t0.plusSeconds(1),
            OTHER, "Masumi", "human", "in")).toCompletableFuture().get();
        svc.observe(new WorldEvent.Said(ROOM, t0.plusSeconds(30),
            OTHER, "Masumi", "long day.", "en")).toCompletableFuture().get();
        var closedOpt = svc.observe(new WorldEvent.EntityLeft(ROOM, t0.plusSeconds(120),
            FOCAL, "Ember", "out")).toCompletableFuture().get();
        var closed = closedOpt.orElseThrow(() -> new AssertionError("scene did not close"));

        var sceneId = closed.id();

        // 2. Journal-mirror side: the StoryStore wrote markdown with the §14 marker
        //    AND the focalsWithJournalEntryForScene lookup resolves the focal by it.
        var today = LocalDate.ofInstant(t0, ZoneId.systemDefault());
        var bio = tempDir.resolve("biography").resolve(FOCAL).resolve(today + ".md");
        assertTrue(Files.exists(bio), "journal markdown written");
        var body = Files.readString(bio);
        var marker = StoryStore.SCENE_ID_MARKER_PREFIX + sceneId + " -->";
        assertTrue(body.contains(marker),
            "journal contains §14 marker for sceneId: expected '" + marker + "'");

        var focalsByMarker = store.focalsWithJournalEntryForScene(sceneId);
        assertTrue(focalsByMarker.contains(FOCAL),
            "marker-based lookup resolves to the focal who owns the journal: "
                + focalsByMarker);

        // 3. Soul side: the EPISODIC fragment lands in the SoulFragmentStore with
        //    the SAME sceneId, retrievable via loadBySceneId.
        var fragmentsByScene = fragmentStore.loadBySceneId(FOCAL, sceneId);
        assertEquals(1, fragmentsByScene.size(), "exactly one EPISODIC fragment for sceneId");
        var fragment = fragmentsByScene.get(0);
        assertEquals(FragmentKind.EPISODIC, fragment.kind());
        assertEquals(sceneId, fragment.sceneId(),
            "soul-side sceneId matches journal-marker sceneId — cross-perspective wire holds");

        // 4. The "do you remember that night" join: same sceneId on both sides
        //    proves the lookup is by-id (constant-time) not by-similarity (best-effort).
        var journalLookupSceneId = sceneId;  // resolved via marker
        var soulLookupSceneId = fragment.sceneId();  // resolved via fragment field
        assertEquals(journalLookupSceneId, soulLookupSceneId,
            "the §14 ↔ §10 promise: both sides share the same opaque scene id");
    }

    @Test
    void unrelated_sceneId_yields_no_fragments_no_focals(@TempDir Path tempDir) {
        var store = new StoryStore(tempDir);
        // No scenes closed → no fragments, no markers.
        var unrelatedId = "scene-never-existed";
        assertTrue(store.focalsWithJournalEntryForScene(unrelatedId).isEmpty());
        assertTrue(fragmentStore.loadBySceneId(FOCAL, unrelatedId).isEmpty());
    }

    @Test
    void multiple_scenes_each_resolve_to_their_own_id(@TempDir Path tempDir) throws Exception {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();

        // Build a sink that stamps each scene's EPISODIC with that scene's id.
        StoryService.InnerMonologueSynthesizer perSceneSink = (scene, focalName) -> {
            var existing = new ArrayList<>(fragmentStore.loadAll(FOCAL));
            existing.add(SoulFragment.fromEpisodicScene(
                "episodic-" + scene.id(),
                "episodic",
                "scene-" + scene.id(),
                "inner notice for " + scene.id(),
                scene.id()));
            fragmentStore.replaceAll(FOCAL, existing);
            return CompletableFuture.completedFuture(null);
        };

        var svc = new StoryService(FOCAL, "Ember", store, arcs,
            (s, n) -> CompletableFuture.completedFuture("witness for " + s.id()),
            perSceneSink);

        // Two distinct scenes (open + close, twice).
        var t0 = Instant.now().minusSeconds(3600);
        svc.openScene(ROOM, t0, List.of(FOCAL, OTHER), "presence");
        svc.observe(new WorldEvent.Said(ROOM, t0.plusSeconds(1),
            OTHER, "Masumi", "first scene", "en")).toCompletableFuture().get();
        var first = svc.observe(new WorldEvent.EntityLeft(ROOM, t0.plusSeconds(30),
            FOCAL, "Ember", "out")).toCompletableFuture().get().orElseThrow();

        var t1 = Instant.now();
        svc.openScene(ROOM, t1, List.of(FOCAL, OTHER), "presence");
        svc.observe(new WorldEvent.Said(ROOM, t1.plusSeconds(1),
            OTHER, "Masumi", "second scene", "en")).toCompletableFuture().get();
        var second = svc.observe(new WorldEvent.EntityLeft(ROOM, t1.plusSeconds(30),
            FOCAL, "Ember", "out")).toCompletableFuture().get().orElseThrow();

        assertNotEquals(first.id(), second.id(), "scenes have distinct ids");

        // Each scene id resolves to its own EPISODIC.
        var firstByScene = fragmentStore.loadBySceneId(FOCAL, first.id());
        var secondByScene = fragmentStore.loadBySceneId(FOCAL, second.id());
        assertEquals(1, firstByScene.size());
        assertEquals(1, secondByScene.size());
        assertEquals("episodic-" + first.id(), firstByScene.get(0).id());
        assertEquals("episodic-" + second.id(), secondByScene.get(0).id());
        assertNotEquals(firstByScene.get(0).text(), secondByScene.get(0).text(),
            "each EPISODIC carries its own scene-specific prose");
    }
}
