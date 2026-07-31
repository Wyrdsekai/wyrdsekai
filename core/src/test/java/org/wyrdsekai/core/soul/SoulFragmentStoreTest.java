package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * soul_fragments table is canonical
 * SqlSoulStore.store() dual-writes through SoulFragmentStore.
 */
class SoulFragmentStoreTest {

    private String jdbcUrl;
    private SoulFragmentStore store;

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "sf-test-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
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
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS soul_manifests(
                  did TEXT NOT NULL, version INTEGER NOT NULL,
                  forged_at TEXT, content_hash TEXT,
                  manifest_json TEXT, archived INTEGER DEFAULT 0,
                  archive_reason TEXT, PRIMARY KEY(did, version))
                """);
        }
        store = new SoulFragmentStore(jdbcUrl);
    }

    @Test
    void replaceAllPersistsListInOrder() {
        var did = "did:key:test1";
        var f1 = SoulFragment.unembedded("identity-core", "personality", "Core", "I am calm.");
        var f2 = SoulFragment.unembedded("pattern-social", "style", "Social", "I greet warmly.");
        store.replaceAll(did, List.of(f1, f2));

        var loaded = store.loadAll(did);
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).id()).isEqualTo("identity-core");
        assertThat(loaded.get(1).id()).isEqualTo("pattern-social");
        assertThat(loaded.get(0).text()).isEqualTo("I am calm.");
    }

    @Test
    void embeddingRoundTripsBitExact() {
        var emb = new float[]{0.1f, -0.5f, 1.0f, Float.MIN_NORMAL, Float.MAX_VALUE};
        var f = SoulFragment.unembedded("e1", "memory", "lbl", "txt").withEmbedding(emb, "test-v1");
        store.replaceAll("did:key:e", List.of(f));

        var loaded = store.loadAll("did:key:e").get(0);
        assertThat(loaded.embedding()).containsExactly(emb);
        assertThat(loaded.embeddingModel()).isEqualTo("test-v1");
    }

    @Test
    void replaceAllDeletesPreviousRows() {
        var did = "did:key:replace";
        store.replaceAll(did, List.of(
            SoulFragment.unembedded("a", "memory", "A", "alpha"),
            SoulFragment.unembedded("b", "memory", "B", "beta")));
        assertThat(store.loadAll(did)).hasSize(2);

        store.replaceAll(did, List.of(SoulFragment.unembedded("c", "memory", "C", "gamma")));
        var after = store.loadAll(did);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).id()).isEqualTo("c");
    }

    @Test
    void replaceAllWithEmptyListClearsRows() {
        var did = "did:key:clear";
        store.replaceAll(did, List.of(
            SoulFragment.unembedded("x", "memory", "X", "x-text")));
        assertThat(store.loadAll(did)).hasSize(1);

        store.replaceAll(did, List.of());
        assertThat(store.loadAll(did)).isEmpty();
    }

    @Test
    void formativeAndBitemporalFieldsRoundTrip() {
        var f = SoulFragment.formative("birth-1", "First moment", "I awoke at dawn.");
        var did = "did:key:formative";
        store.replaceAll(did, List.of(f));

        var loaded = store.loadAll(did).get(0);
        assertThat(loaded.formative()).isTrue();
        assertThat(loaded.confidence()).isEqualTo(0.8f);
        assertThat(loaded.reinforcementCount()).isEqualTo(1);
        assertThat(loaded.firstObserved()).isCloseTo(f.firstObserved(), within(1L, ChronoUnit.SECONDS));
        assertThat(loaded.lastConfirmed()).isNotNull();
    }

    @Test
    void supersededFragmentsRetainTombstone() {
        var did = "did:key:super";
        var f = SoulFragment.unembedded("old", "memory", "Old", "outdated").supersede("new");
        store.replaceAll(did, List.of(f));

        var loaded = store.loadAll(did).get(0);
        assertThat(loaded.isSuperseded()).isTrue();
        assertThat(loaded.supersededBy()).isEqualTo("new");
        assertThat(loaded.supersededAt()).isNotNull();
    }

    @Test
    void blankDidIsRejectedSilently() {
        store.replaceAll("", List.of(SoulFragment.unembedded("x", "memory", "X", "t")));
        store.replaceAll(null, List.of(SoulFragment.unembedded("x", "memory", "X", "t")));
        assertThat(store.count()).isZero();
    }

    @Test
    void loadAllReturnsEmptyForUnknownDid() {
        assertThat(store.loadAll("did:key:nope")).isEmpty();
    }

    @Test
    void countReflectsAllRowsAcrossDids() {
        store.replaceAll("did:a", List.of(
            SoulFragment.unembedded("a1", "memory", "A1", "t"),
            SoulFragment.unembedded("a2", "memory", "A2", "t")));
        store.replaceAll("did:b", List.of(
            SoulFragment.unembedded("b1", "memory", "B1", "t")));
        assertThat(store.count()).isEqualTo(3);
    }
}
