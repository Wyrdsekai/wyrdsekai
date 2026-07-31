package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * voice_profiles table is canonical
 * VoiceProfileService dual-writes to both store and manifest.
 */
class VoiceProfileStoreTest {

    private String jdbcUrl;
    private VoiceProfileStore voiceStore;

    @BeforeEach
    void setUp() throws SQLException {
        // Shared-cache in-memory SQLite, kept alive by holding a connection.
        var dbName = "vp-test-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        try (var stmt = keepAlive.createStatement()) {
            // Mirror the production schema for the table under test.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS voice_profiles(
                  did                TEXT PRIMARY KEY,
                  clauses_json       TEXT NOT NULL DEFAULT '{}',
                  revision           INTEGER NOT NULL DEFAULT 0,
                  frozen             INTEGER NOT NULL DEFAULT 0,
                  history_json       TEXT NOT NULL DEFAULT '[]',
                  updated_at         INTEGER NOT NULL DEFAULT (unixepoch())
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
        voiceStore = new VoiceProfileStore(jdbcUrl);
    }

    @Test
    void roundTripsEmptyProfile() {
        voiceStore.save("did:key:test1", VoiceProfile.empty());
        var loaded = voiceStore.load("did:key:test1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().revision()).isZero();
        assertThat(loaded.get().frozen()).isFalse();
        assertThat(loaded.get().clauses()).isEmpty();
        assertThat(loaded.get().history()).isEmpty();
    }

    @Test
    void roundTripsProfileWithClausesAndHistory() {
        var clauses = new LinkedHashMap<String, String>();
        clauses.put("greeting-tone", "warm but spare");
        clauses.put("ending", "no sign-off");
        var withClauses = VoiceProfile.empty()
            .withClauses(clauses, "first edit", "steward:operator");
        voiceStore.save("did:key:test2", withClauses);

        var loaded = voiceStore.load("did:key:test2");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().clauses()).hasSize(2);
        assertThat(loaded.get().clauses().get("greeting-tone")).isEqualTo("warm but spare");
        assertThat(loaded.get().revision()).isEqualTo(1);
        assertThat(loaded.get().history()).hasSize(1);
        assertThat(loaded.get().history().get(0).author()).isEqualTo("steward:operator");
    }

    @Test
    void upsertOverwritesExistingRow() {
        voiceStore.save("did:key:test3", VoiceProfile.empty());
        var bumped = VoiceProfile.empty()
            .withClauses(new LinkedHashMap<>() {{ put("k", "v"); }}, "r", "a");
        voiceStore.save("did:key:test3", bumped);

        assertThat(voiceStore.load("did:key:test3").get().revision()).isEqualTo(1);
        assertThat(voiceStore.count()).isEqualTo(1);
    }

    @Test
    void loadReturnsEmptyForUnknownDid() {
        assertThat(voiceStore.load("did:key:does-not-exist")).isEmpty();
    }

    @Test
    void blankDidIsRejectedSilently() {
        // No throw; just no-op (logged warning).
        voiceStore.save("", VoiceProfile.empty());
        voiceStore.save(null, VoiceProfile.empty());
        assertThat(voiceStore.count()).isZero();
    }
}
