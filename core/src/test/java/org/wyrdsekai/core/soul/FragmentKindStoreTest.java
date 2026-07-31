package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SoulFragmentStore kind-column behavior.
 *
 * <p>Covers two scenarios:</p>
 * <ol>
 *   <li><b>Legacy table</b> — created without the {@code kind} column.
 *       Store's idempotent migration must add the column with NARRATIVE
 *       default; existing rows must hydrate as NARRATIVE.</li>
 *   <li><b>Fresh writes</b> — fragments persist with their declared kind;
 *       {@link SoulFragmentStore#loadByKind} returns only matches;
 *       {@link SoulFragmentStore#countByKind} aggregates correctly.</li>
 * </ol>
 */
class FragmentKindStoreTest {

    private String jdbcUrl;
    private SoulFragmentStore store;

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "fk-test-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        // Deliberately create the table WITHOUT a kind column so we exercise
        // the §17.6 idempotent migration path.
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

    @Test void legacy_table_migrates_to_add_kind_column() {
        var did = "did:wyrd:test:legacy";
        // Pre-migration insert — this triggers ensureMigrated() which adds
        // the kind column with NARRATIVE default.
        store.replaceAll(did, List.of(
            SoulFragment.unembedded("a", "memory", "A", "alpha"),
            SoulFragment.unembedded("b", "memory", "B", "beta")));

        var loaded = store.loadAll(did);
        assertThat(loaded).hasSize(2);
        // Backward-compat: rows that didn't declare a kind hydrate as NARRATIVE.
        assertThat(loaded).allSatisfy(f ->
            assertThat(f.kind()).isEqualTo(FragmentKind.NARRATIVE));
    }

    @Test void dexterity_fragment_persists_with_kind() {
        var did = "did:wyrd:familiar:codeplane:test";
        store.replaceAll(did, List.of(
            SoulFragment.dexterity("dex-1", "procedural",
                "RegexParsing", "Regex on log files keeps failing — prefer split."),
            SoulFragment.unembedded("nar-1", "memory",
                "Mood", "Started feeling settled today.")));

        var dex = store.loadByKind(did, FragmentKind.DEXTERITY);
        assertThat(dex).hasSize(1);
        assertThat(dex.get(0).id()).isEqualTo("dex-1");
        assertThat(dex.get(0).kind()).isEqualTo(FragmentKind.DEXTERITY);

        var nar = store.loadByKind(did, FragmentKind.NARRATIVE);
        assertThat(nar).hasSize(1);
        assertThat(nar.get(0).id()).isEqualTo("nar-1");
    }

    @Test void all_four_kinds_round_trip() {
        var did = "did:wyrd:test:all-kinds";
        store.replaceAll(did, List.of(
            SoulFragment.unembedded("nar", "memory", "N", "narrative-text"),
            SoulFragment.dexterity("dex", "procedural", "D", "dexterity-text"),
            SoulFragment.convention("conv", "rule", "C", "convention-text"),
            SoulFragment.structural("struct", "shape", "S", "structural-text")));

        var counts = store.countByKind(did);
        assertThat(counts.get(FragmentKind.NARRATIVE)).isEqualTo(1);
        assertThat(counts.get(FragmentKind.DEXTERITY)).isEqualTo(1);
        assertThat(counts.get(FragmentKind.CONVENTION)).isEqualTo(1);
        assertThat(counts.get(FragmentKind.STRUCTURAL)).isEqualTo(1);
    }

    @Test void load_by_unknown_kind_returns_empty() {
        var did = "did:wyrd:test:lonely";
        store.replaceAll(did, List.of(
            SoulFragment.unembedded("only-narrative", "memory", "X", "y")));
        assertThat(store.loadByKind(did, FragmentKind.DEXTERITY)).isEmpty();
        assertThat(store.loadByKind(did, FragmentKind.CONVENTION)).isEmpty();
        assertThat(store.loadByKind(did, FragmentKind.STRUCTURAL)).isEmpty();
    }

    @Test void countByKind_returns_zero_for_absent_did() {
        // §17.6 declared NARRATIVE/DEXTERITY/CONVENTION/STRUCTURAL;
        // added EPISODIC. countByKind always returns one
        // entry per kind (zeros for absent kinds) so callers can render
        // histograms without checking nulls.
        var counts = store.countByKind("did:wyrd:test:nobody");
        assertThat(counts).hasSize(5);
        assertThat(counts.values()).allSatisfy(c -> assertThat(c).isZero());
    }

    @Test void withKind_returns_new_instance() {
        var f = SoulFragment.unembedded("x", "memory", "L", "T");
        assertThat(f.kind()).isEqualTo(FragmentKind.NARRATIVE);
        var dex = f.withKind(FragmentKind.DEXTERITY);
        assertThat(dex.kind()).isEqualTo(FragmentKind.DEXTERITY);
        assertThat(dex.id()).isEqualTo(f.id());
        assertThat(f.kind()).isEqualTo(FragmentKind.NARRATIVE); // original unchanged
    }

    @Test void reinforce_preserves_kind() {
        var f = SoulFragment.dexterity("d", "p", "L", "T");
        var reinforced = f.reinforce();
        assertThat(reinforced.kind()).isEqualTo(FragmentKind.DEXTERITY);
    }

    @Test void legacy_14arg_constructor_defaults_to_narrative() {
        // The backward-compat 14-arg constructor must give NARRATIVE so the
        // ~30+ existing call sites continue working unchanged.
        var f = SoulFragment.unembedded("legacy", "memory", "L", "T");
        assertThat(f.kind()).isEqualTo(FragmentKind.NARRATIVE);
    }
}
