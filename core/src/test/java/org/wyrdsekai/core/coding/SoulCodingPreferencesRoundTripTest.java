package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.soul.CodingPreferences;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1c — verifies {@link CodingPreferences} round-trips through soul
 * manifest serialization (Jackson + SqlSoulStore). Per
 * "Soul manifest round-trip" + §9.4.
 *
 * <p>Three invariants:</p>
 * <ol>
 *   <li>A manifest forged before Phase 1b (no {@code coding_preferences})
 *       loads with {@code codingPreferences == null} — pre-existing souls
 *       on disk must not pick up a non-null default.</li>
 *   <li>A manifest with populated coding preferences round-trips through
 *       SqlSoulStore unchanged (preferred_backend, avoid_backends, and
 *       task_type_overrides all preserved).</li>
 *   <li>JSON serialization round-trips cleanly — pure Jackson, no DB.</li>
 * </ol>
 */
class SoulCodingPreferencesRoundTripTest {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @TempDir Path workspace;

    // ─── Pure Jackson round-trip ────────────────────────────────────

    @Test void manifest_without_coding_preferences_serializes_with_null() throws Exception {
        var manifest = birth("did:key:plain");
        assertThat(manifest.codingPreferences())
            .as("birth manifest must NOT default codingPreferences — souls "
                + "forged before Phase 1b stay null after upgrade")
            .isNull();

        var json = JSON.writeValueAsString(manifest);
        var loaded = JSON.readValue(json, SoulManifest.class);
        assertThat(loaded.codingPreferences()).isNull();
    }

    @Test void manifest_with_coding_preferences_round_trips_through_jackson()
            throws Exception {
        var prefs = new CodingPreferences(
            "aider",
            List.of("codex", "claude-sdk"),
            Map.of(
                "explore", "openhands",
                "refactor", "aider",
                "implement_feature", "codezaiku"
            )
        );
        var manifest = birth("did:key:withprefs").withCodingPreferences(prefs);

        var json = JSON.writeValueAsString(manifest);
        var loaded = JSON.readValue(json, SoulManifest.class);

        assertThat(loaded.codingPreferences()).isNotNull();
        assertThat(loaded.codingPreferences().preferredBackend()).isEqualTo("aider");
        assertThat(loaded.codingPreferences().avoidBackends())
            .containsExactly("codex", "claude-sdk");
        assertThat(loaded.codingPreferences().taskTypeOverrides())
            .containsEntry("explore", "openhands")
            .containsEntry("refactor", "aider")
            .containsEntry("implement_feature", "codezaiku");
    }

    @Test void coding_preferences_uses_snake_case_json_property_names()
            throws Exception {
        // The wire format uses snake_case (per the SPEC §9.4 example) so
        // hand-written manifests / Forge-emitted JSON can round-trip
        // through the Java record. This pin guards against a future
        // refactor that flips to camelCase silently.
        var prefs = new CodingPreferences("aider",
            List.of("codex"), Map.of("refactor", "aider"));
        var json = JSON.writeValueAsString(prefs);

        assertThat(json).contains("\"preferred_backend\"");
        assertThat(json).contains("\"avoid_backends\"");
        assertThat(json).contains("\"task_type_overrides\"");
    }

    @Test void coding_preferences_with_null_collections_hydrates_to_empty()
            throws Exception {
        // Per CodingPreferences javadoc: round-trip tolerance — null
        // collections in JSON hydrate to empty so the policy script can
        // dot-walk without null guards.
        var json = "{\"preferred_backend\":\"aider\","
            + "\"avoid_backends\":null,\"task_type_overrides\":null}";
        var prefs = JSON.readValue(json, CodingPreferences.class);
        assertThat(prefs.preferredBackend()).isEqualTo("aider");
        assertThat(prefs.avoidBackends()).isNotNull().isEmpty();
        assertThat(prefs.taskTypeOverrides()).isNotNull().isEmpty();
    }

    // ─── SqlSoulStore round-trip ────────────────────────────────────

    @Test void sql_soul_store_preserves_null_coding_preferences() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("nullprefs.db"));
        try (var store = new SqlSoulStore(jdbc)) {
            var manifest = birth("did:key:nullprefs");
            store.store(manifest);

            var loaded = store.latest("did:key:nullprefs").orElseThrow();
            assertThat(loaded.codingPreferences())
                .as("a soul with no coding preferences must remain null "
                    + "across a store/load cycle")
                .isNull();
        }
    }

    @Test void sql_soul_store_round_trips_populated_coding_preferences() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("withprefs.db"));
        try (var store = new SqlSoulStore(jdbc)) {
            var taskMap = new LinkedHashMap<String, String>();
            taskMap.put("explore", "openhands");
            taskMap.put("refactor", "aider");
            var prefs = new CodingPreferences(
                "aider",
                List.of("codex", "claude-sdk"),
                taskMap);
            var manifest = birth("did:key:populated").withCodingPreferences(prefs);
            store.store(manifest);

            var loaded = store.latest("did:key:populated").orElseThrow();
            var loadedPrefs = loaded.codingPreferences();
            assertThat(loadedPrefs).isNotNull();
            assertThat(loadedPrefs.preferredBackend()).isEqualTo("aider");
            assertThat(loadedPrefs.avoidBackends())
                .containsExactly("codex", "claude-sdk");
            assertThat(loadedPrefs.taskTypeOverrides())
                .containsEntry("explore", "openhands")
                .containsEntry("refactor", "aider");
        }
    }

    @Test void sql_soul_store_preserves_other_soul_fields_when_prefs_set() {
        // Sanity: setting codingPreferences must not nuke surrounding
        // sub-records in storageView. Verifies the withCodingPreferences
        // copy constructor preserves DID, version, profile, etc.
        var jdbc = SchemaInitializer.initialize(workspace.resolve("mixed.db"));
        try (var store = new SqlSoulStore(jdbc)) {
            var prefs = new CodingPreferences("aider", List.of(), Map.of());
            var manifest = birth("did:key:mixed").withCodingPreferences(prefs);
            store.store(manifest);

            var loaded = store.latest("did:key:mixed").orElseThrow();
            assertThat(loaded.did()).isEqualTo("did:key:mixed");
            assertThat(loaded.profile()).isNotNull();
            assertThat(loaded.profile().name())
                .isEqualTo(manifest.profile().name());
            assertThat(loaded.codingPreferences()).isNotNull();
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static SoulManifest birth(String did) {
        var profile = new AgentProfile(
            "Test", "test", "agent", "test agent", "You are Test.",
            8192, 1024, 0.7, null);
        return SoulManifest.birth(did, "z6Mk", List.of(),
            profile, GenomeProfile.defaults());
    }
}
