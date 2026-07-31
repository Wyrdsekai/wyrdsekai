package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * persistence-layer round-trip for EPISODIC
 * fragments + scene_id column.
 *
 * <p>Catches the latent bug found while writing this test: §14 added
 * {@code sceneId} to the in-memory {@link SoulFragment} record and the
 * journal-mirror HTML-comment marker, but the {@link SoulFragmentStore}
 * SQL schema never grew a {@code scene_id} column. So before this test +
 * the migration fix, every EPISODIC fragment lost its sceneId on
 * {@code SqlSoulStore.store()} → {@code loadByKind(EPISODIC)} round-trip,
 * silently breaking the §10 cross-perspective promise.</p>
 *
 * <p>This test forces the legacy-table migration path (creates the table
 * WITHOUT the new columns), then writes + reads an EPISODIC fragment
 * and asserts kind + sceneId both survive.</p>
 */
class SoulFragmentStoreEpisodicRoundTripTest {

    private String jdbcUrl;
    private SoulFragmentStore store;

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "fk-episodic-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        // Pre-§14 / pre-§17.6 schema: no kind column, no scene_id column.
        // Forces both migration paths through ensureMigrated().
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
                  PRIMARY KEY (did, fragment_id)
                )
                """);
        }
        store = new SoulFragmentStore(jdbcUrl);
    }

    @Test void episodic_fragment_round_trips_with_kind_and_sceneId() {
        var did = "did:wyrd:companion:ember";
        var sceneId = "scene-2026-05-24-fire-night";
        var inner = "I let him have the quiet. There was something about firelight on his hands.";

        var fragment = SoulFragment.fromEpisodicScene(
            "episodic-" + sceneId, "episodic", "scene-" + sceneId, inner, sceneId);

        store.replaceAll(did, List.of(fragment));

        // Load by kind — the load-bearing read pattern for EPISODIC.
        var loaded = store.loadByKind(did, FragmentKind.EPISODIC);
        assertThat(loaded).hasSize(1);
        var f = loaded.get(0);
        assertThat(f.kind()).isEqualTo(FragmentKind.EPISODIC);
        assertThat(f.sceneId())
            .as("sceneId must survive round-trip — the §10 + §14 cross-perspective"
                + " promise depends on this. If null, the scene_id column was not"
                + " persisted (the bug this test was built to catch).")
            .isEqualTo(sceneId);
        assertThat(f.text()).isEqualTo(inner);
        assertThat(f.id()).isEqualTo("episodic-" + sceneId);
    }

    @Test void loadBySceneId_finds_episodic_fragment_by_id() {
        var did = "did:wyrd:companion:ember";
        var sceneId = "scene-cross-perspective-lookup";
        var fragment = SoulFragment.fromEpisodicScene(
            "ep-1", "episodic", "scene-x", "inner notice", sceneId);

        store.replaceAll(did, List.of(fragment));

        // The cross-perspective lookup: given a sceneId resolved from a
        // journal-marker, find the matching EPISODIC fragment in the
        // companion's soul.
        var byScene = store.loadBySceneId(did, sceneId);
        assertThat(byScene).hasSize(1);
        assertThat(byScene.get(0).sceneId()).isEqualTo(sceneId);
        assertThat(byScene.get(0).text()).isEqualTo("inner notice");
    }

    @Test void loadBySceneId_returns_empty_for_unknown_scene() {
        var did = "did:wyrd:companion:ember";
        store.replaceAll(did, List.of(SoulFragment.fromEpisodicScene(
            "ep-1", "episodic", "scene-x", "txt", "scene-real")));

        assertThat(store.loadBySceneId(did, "scene-does-not-exist")).isEmpty();
    }

    @Test void loadBySceneId_handles_null_inputs() {
        assertThat(store.loadBySceneId(null, "x")).isEmpty();
        assertThat(store.loadBySceneId("", "x")).isEmpty();
        assertThat(store.loadBySceneId("did:wyrd:x", null)).isEmpty();
        assertThat(store.loadBySceneId("did:wyrd:x", "")).isEmpty();
    }

    @Test void non_scene_derived_fragments_have_null_sceneId_on_round_trip() {
        var did = "did:wyrd:companion:ember";
        store.replaceAll(did, List.of(
            SoulFragment.unembedded("personality-1", "personality",
                "Core", "Ember is patient by nature."),
            SoulFragment.dexterity("dex-1", "procedural", "Test",
                "Always run :core:test first")));

        var loaded = store.loadAll(did);
        assertThat(loaded).hasSize(2);
        assertThat(loaded).allSatisfy(f -> assertThat(f.sceneId())
            .as("non-scene-derived fragment must hydrate with null sceneId — "
                + "only fromScene/fromEpisodicScene factories carry the field")
            .isNull());
    }

    @Test void scene_derived_NARRATIVE_fragment_also_round_trips_sceneId() {
        // §14 NARRATIVE-with-sceneId (the existing fromScene factory) must
        // also survive the round-trip — this catches the same bug for the
        // non-EPISODIC scene-derived path.
        var did = "did:wyrd:companion:ember";
        var sceneId = "scene-narrative-derived";
        var frag = SoulFragment.fromScene(
            "nar-from-scene", "memory", "by-the-fire",
            "She came home and sat by the hearth.", sceneId);

        store.replaceAll(did, List.of(frag));

        var loaded = store.loadAll(did);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).kind()).isEqualTo(FragmentKind.NARRATIVE);
        assertThat(loaded.get(0).sceneId()).isEqualTo(sceneId);
    }

    @Test void mixed_kinds_with_and_without_sceneId_all_survive() {
        var did = "did:wyrd:companion:ember";
        store.replaceAll(did, List.of(
            SoulFragment.unembedded("p-1", "personality", "Core", "patient"),
            SoulFragment.fromEpisodicScene("ep-1", "episodic", "s1", "inner 1", "scene-1"),
            SoulFragment.fromScene("nar-1", "memory", "by-fire", "witness 1", "scene-1"),
            SoulFragment.dexterity("dex-1", "procedural", "T", "tx")));

        var byEpisodic = store.loadByKind(did, FragmentKind.EPISODIC);
        var byNarrative = store.loadByKind(did, FragmentKind.NARRATIVE);
        var byDexterity = store.loadByKind(did, FragmentKind.DEXTERITY);

        assertThat(byEpisodic).hasSize(1);
        assertThat(byEpisodic.get(0).sceneId()).isEqualTo("scene-1");

        // NARRATIVE pool has both the personality fragment (no sceneId)
        // and the scene-derived NARRATIVE (with sceneId).
        assertThat(byNarrative).hasSize(2);
        assertThat(byNarrative)
            .anySatisfy(f -> {
                assertThat(f.id()).isEqualTo("p-1");
                assertThat(f.sceneId()).isNull();
            })
            .anySatisfy(f -> {
                assertThat(f.id()).isEqualTo("nar-1");
                assertThat(f.sceneId()).isEqualTo("scene-1");
            });

        assertThat(byDexterity).hasSize(1);
        assertThat(byDexterity.get(0).sceneId()).isNull();
    }
}
