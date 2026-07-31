package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * world_knowledge table is canonical
 * SqlSoulStore.store() dual-writes via WorldKnowledgeStore.
 */
class WorldKnowledgeStoreTest {

    private String jdbcUrl;
    private WorldKnowledgeStore store;

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "wk-test-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        try (var stmt = keepAlive.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS world_knowledge(
                  did         TEXT NOT NULL,
                  key         TEXT NOT NULL,
                  value       TEXT,
                  updated_at  INTEGER NOT NULL DEFAULT (unixepoch()),
                  PRIMARY KEY (did, key)
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
        store = new WorldKnowledgeStore(jdbcUrl);
    }

    @Test
    void replaceAllPersistsEntries() {
        var did = "did:key:test1";
        var k = new LinkedHashMap<String, String>();
        k.put("starterKit", "explorer");
        k.put("preferredChannel", "matrix");
        store.replaceAll(did, k);

        var loaded = store.loadAll(did);
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get("starterKit")).isEqualTo("explorer");
        assertThat(loaded.get("preferredChannel")).isEqualTo("matrix");
    }

    @Test
    void getReturnsSingleValue() {
        store.replaceAll("did:key:single",
            Map.of("starterKit", "scholar"));
        assertThat(store.get("did:key:single", "starterKit")).isEqualTo("scholar");
        assertThat(store.get("did:key:single", "missing")).isNull();
    }

    @Test
    void replaceAllDeletesPreviousRows() {
        var did = "did:key:replace";
        store.replaceAll(did, Map.of("a", "1", "b", "2"));
        assertThat(store.loadAll(did)).hasSize(2);

        store.replaceAll(did, Map.of("c", "3"));
        var after = store.loadAll(did);
        assertThat(after).hasSize(1);
        assertThat(after.get("c")).isEqualTo("3");
        assertThat(after).doesNotContainKeys("a", "b");
    }

    @Test
    void replaceAllWithEmptyMapClearsRows() {
        var did = "did:key:clear";
        store.replaceAll(did, Map.of("x", "y"));
        assertThat(store.loadAll(did)).hasSize(1);

        store.replaceAll(did, Map.of());
        assertThat(store.loadAll(did)).isEmpty();
    }

    @Test
    void blankDidIsRejectedSilently() {
        store.replaceAll("", Map.of("k", "v"));
        store.replaceAll(null, Map.of("k", "v"));
        assertThat(store.count()).isZero();
    }

    @Test
    void loadAllReturnsEmptyForUnknownDid() {
        assertThat(store.loadAll("did:key:nope")).isEmpty();
    }

    @Test
    void countReflectsAllRowsAcrossDids() {
        store.replaceAll("did:a", Map.of("a1", "x", "a2", "y"));
        store.replaceAll("did:b", Map.of("b1", "z"));
        assertThat(store.count()).isEqualTo(3);
    }

    @Test
    void blankKeyIsSkipped() {
        var k = new LinkedHashMap<String, String>();
        k.put("", "blank-key");
        k.put("good", "value");
        store.replaceAll("did:key:bk", k);
        var loaded = store.loadAll("did:key:bk");
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get("good")).isEqualTo("value");
    }
}
