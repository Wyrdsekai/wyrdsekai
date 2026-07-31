package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 4: Soul Store, Forge State, Forge Events.
 * ForgeActor tested via state/event unit tests (no Pekko test harness needed
 * for correctness — actor integration tested in E2E).
 */
class Phase4Test {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** Create a minimal test manifest. */
    private static SoulManifest testManifest(String did, int version) {
        var profile = new AgentProfile("TestAgent", "entity-1", "agent",
            "A test agent", "You are a test agent.", 4096, 512, 0.7, did);
        var genome = GenomeProfile.defaults();
        var manifest = SoulManifest.birth(did, "z6MkTest123", List.of(), profile, genome);
        // Reforge with desired version
        return SoulManifest.forge(
            did, "z6MkTest123", List.of(), null, version,
            profile, "I am a test agent.",
            List.of(), 3, "",
            genome, List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    // --- ForgeEvent ---

    @Nested
    class ForgeEventTests {

        @Test
        void soul_forged_event_fields() {
            var event = new ForgeEvent.SoulForged("did:key:z6Mk1", Instant.now(), 3, "abc123");
            assertEquals("did:key:z6Mk1", event.did());
            assertEquals(3, event.version());
            assertEquals("abc123", event.contentHash());
        }

        @Test
        void soul_born_event_fields() {
            var event = new ForgeEvent.SoulBorn("did:key:z6Mk2", Instant.now(), "resilient");
            assertEquals("did:key:z6Mk2", event.did());
            assertEquals("resilient", event.genomeName());
        }

        @Test
        void forge_event_json_roundtrip() throws Exception {
            var event = new ForgeEvent.SoulForged("did:key:z6Mk1", Instant.now(), 1, "hash");
            String json = MAPPER.writeValueAsString(event);
            var restored = MAPPER.readValue(json, ForgeEvent.class);
            assertInstanceOf(ForgeEvent.SoulForged.class, restored);
            assertEquals("did:key:z6Mk1", ((ForgeEvent.SoulForged) restored).did());
        }

        @Test
        void all_event_types_serialize() throws Exception {
            List<ForgeEvent> events = List.of(
                new ForgeEvent.SoulForged("d1", Instant.now(), 1, "h1"),
                new ForgeEvent.SoulRestored("d1", Instant.now(), "zone-a"),
                new ForgeEvent.SoulInspected("d1", "d2", Instant.now()),
                new ForgeEvent.SoulForked("d1", "d3", Instant.now()),
                new ForgeEvent.SoulArchived("d1", Instant.now(), "retired"),
                new ForgeEvent.SoulBorn("d4", Instant.now(), "curious")
            );

            for (var event : events) {
                String json = MAPPER.writeValueAsString(event);
                var restored = MAPPER.readValue(json, ForgeEvent.class);
                assertNotNull(restored);
            }
        }
    }

    // --- ForgeState ---

    @Nested
    class ForgeStateTests {

        @Test
        void empty_state() {
            var state = ForgeState.empty();
            assertTrue(state.knownSouls().isEmpty());
            assertEquals(0, state.totalForges());
            assertEquals(0, state.totalRestores());
        }

        @Test
        void apply_soul_forged() {
            var state = ForgeState.empty();
            var event = new ForgeEvent.SoulForged("did:key:z6Mk1", Instant.now(), 1, "hash1");

            var newState = state.apply(event);
            assertEquals(1, newState.totalForges());
            assertTrue(newState.knownSouls().containsKey("did:key:z6Mk1"));
            assertEquals(1, newState.knownSouls().get("did:key:z6Mk1").version());
            assertFalse(newState.knownSouls().get("did:key:z6Mk1").archived());
        }

        @Test
        void apply_soul_restored() {
            var state = ForgeState.empty();
            var event = new ForgeEvent.SoulRestored("did:key:z6Mk1", Instant.now(), "zone-a");

            var newState = state.apply(event);
            assertEquals(1, newState.totalRestores());
        }

        @Test
        void apply_soul_archived() {
            var state = ForgeState.empty()
                .apply(new ForgeEvent.SoulForged("did:key:z6Mk1", Instant.now(), 1, "h1"));

            var archived = state.apply(new ForgeEvent.SoulArchived("did:key:z6Mk1",
                Instant.now(), "retired"));
            assertTrue(archived.knownSouls().get("did:key:z6Mk1").archived());
        }

        @Test
        void apply_soul_forked() {
            var state = ForgeState.empty()
                .apply(new ForgeEvent.SoulForged("parent", Instant.now(), 5, "hp"));

            var forked = state.apply(new ForgeEvent.SoulForked("parent", "child", Instant.now()));
            assertTrue(forked.knownSouls().containsKey("child"));
            assertEquals(1, forked.knownSouls().get("child").version());
            assertEquals(2, forked.totalForges()); // parent forge + fork
        }

        @Test
        void apply_soul_born() {
            var state = ForgeState.empty();
            var born = state.apply(new ForgeEvent.SoulBorn("newborn", Instant.now(), "curious"));
            assertTrue(born.knownSouls().containsKey("newborn"));
            assertEquals(1, born.totalForges());
        }

        @Test
        void events_for_did_filters() {
            var state = ForgeState.empty()
                .apply(new ForgeEvent.SoulForged("d1", Instant.now(), 1, "h1"))
                .apply(new ForgeEvent.SoulForged("d2", Instant.now(), 1, "h2"))
                .apply(new ForgeEvent.SoulForged("d1", Instant.now(), 2, "h3"));

            var d1Events = state.eventsForDid("d1");
            assertEquals(2, d1Events.size());

            var d2Events = state.eventsForDid("d2");
            assertEquals(1, d2Events.size());
        }

        @Test
        void describe_empty() {
            var desc = ForgeState.empty().describe();
            assertTrue(desc.contains("empty"));
        }

        @Test
        void describe_with_souls() {
            var state = ForgeState.empty()
                .apply(new ForgeEvent.SoulForged("d1", Instant.now(), 1, "h1"))
                .apply(new ForgeEvent.SoulBorn("d2", Instant.now(), "default"));

            var desc = state.describe();
            assertTrue(desc.contains("Soul stones: 2"));
            assertTrue(desc.contains("Total forges: 2"));
        }

        @Test
        void state_json_roundtrip() throws Exception {
            var state = ForgeState.empty()
                .apply(new ForgeEvent.SoulForged("d1", Instant.now(), 1, "h1"))
                .apply(new ForgeEvent.SoulBorn("d2", Instant.now(), "resilient"));

            String json = MAPPER.writeValueAsString(state);
            var restored = MAPPER.readValue(json, ForgeState.class);
            assertEquals(state.totalForges(), restored.totalForges());
            assertEquals(state.knownSouls().size(), restored.knownSouls().size());
        }
    }

    // --- SoulStore (SqlSoulStore with in-memory SQLite) ---

    @Nested
    class SqlSoulStoreTests {

        @Test
        void store_and_load(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                var manifest = testManifest("did:key:z6Mk1", 1);
                store.store(manifest);

                var loaded = store.latest("did:key:z6Mk1");
                assertTrue(loaded.isPresent());
                assertEquals("did:key:z6Mk1", loaded.get().did());
                assertEquals(1, loaded.get().manifestVersion());
            }
        }

        @Test
        void load_specific_version(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                store.store(testManifest("did:key:z6Mk1", 1));
                store.store(testManifest("did:key:z6Mk1", 2));

                var v1 = store.load("did:key:z6Mk1", 1);
                assertTrue(v1.isPresent());
                assertEquals(1, v1.get().manifestVersion());

                var v2 = store.load("did:key:z6Mk1", 2);
                assertTrue(v2.isPresent());
                assertEquals(2, v2.get().manifestVersion());
            }
        }

        @Test
        void latest_returns_newest(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                store.store(testManifest("did:key:z6Mk1", 1));
                store.store(testManifest("did:key:z6Mk1", 2));
                store.store(testManifest("did:key:z6Mk1", 3));

                var latest = store.latest("did:key:z6Mk1");
                assertTrue(latest.isPresent());
                assertEquals(3, latest.get().manifestVersion());
            }
        }

        @Test
        void history_returns_all_versions(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                store.store(testManifest("did:key:z6Mk1", 1));
                store.store(testManifest("did:key:z6Mk1", 2));
                store.store(testManifest("did:key:z6Mk1", 3));

                var history = store.history("did:key:z6Mk1");
                assertEquals(3, history.size());
                // Newest first
                assertEquals(3, history.get(0).manifestVersion());
                assertEquals(1, history.get(2).manifestVersion());
            }
        }

        @Test
        void archive_hides_from_latest(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                store.store(testManifest("did:key:z6Mk1", 1));
                store.archive("did:key:z6Mk1", "retired");

                // latest() should not return archived
                var latest = store.latest("did:key:z6Mk1");
                assertTrue(latest.isEmpty());

                // But exists() should still find it
                assertTrue(store.exists("did:key:z6Mk1"));

                // And load() by version should still work
                var v1 = store.load("did:key:z6Mk1", 1);
                assertTrue(v1.isPresent());
            }
        }

        @Test
        void exists_and_count(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                assertFalse(store.exists("did:key:z6Mk1"));
                assertEquals(0, store.count());

                store.store(testManifest("did:key:z6Mk1", 1));
                assertTrue(store.exists("did:key:z6Mk1"));
                assertEquals(1, store.count());

                store.store(testManifest("did:key:z6Mk1", 2));
                assertEquals(2, store.count());

                store.store(testManifest("did:key:z6Mk2", 1));
                assertEquals(3, store.count());
            }
        }

        @Test
        void missing_did_returns_empty(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                assertTrue(store.latest("nonexistent").isEmpty());
                assertTrue(store.load("nonexistent", 1).isEmpty());
                assertTrue(store.history("nonexistent").isEmpty());
            }
        }

        @Test
        void manifest_roundtrip_preserves_all_fields(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                var profile = new AgentProfile("Lain", "home-server-1", "agent",
                    "A quiet thinker", "You are Lain.", 4096, 512, 0.7, "did:key:z6MkLain");
                var genome = GenomeProfile.randomized("curious");
                var fragment = SoulFragment.unembedded("identity-core", "personality",
                    "Core", "I am Lain, a quiet presence.");
                var memory = new CompactedMemory(
                    List.of(MemoryNode.neutral("m1", "First day", List.of("first"))),
                    List.of(), Map.of("philosophy", 0.8f));
                var rel = Relationship.acquaintance("did:key:z6MkAlice", "Alice");

                var manifest = SoulManifest.forge(
                    "did:key:z6MkLain", "z6MkLain", List.of(), null, 1,
                    profile, "I am Lain.",
                    List.of(fragment), 3, "# SOUL.md\n",
                    genome, List.of("calibration example 1"),
                    memory, List.of(rel), List.of(), Map.of("fact1", "value1"),
                    VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
                );

                store.store(manifest);
                var loaded = store.latest("did:key:z6MkLain").orElseThrow();

                assertEquals(manifest.did(), loaded.did());
                assertEquals(manifest.manifestVersion(), loaded.manifestVersion());
                assertEquals(manifest.residentIdentity(), loaded.residentIdentity());
                assertEquals(manifest.soulFragments().size(), loaded.soulFragments().size());
                assertEquals(manifest.memory().nodes().size(), loaded.memory().nodes().size());
                assertEquals(manifest.relationships().size(), loaded.relationships().size());
                assertEquals(manifest.genome().name(), loaded.genome().name());
                assertEquals(manifest.retrievalK(), loaded.retrievalK());
                assertEquals(manifest.worldKnowledge().get("fact1"), loaded.worldKnowledge().get("fact1"));
            }
        }
    }

    // --- ForgeCommand ---

    @Nested
    class ForgeCommandTests {

        @Test
        void forge_result_types() {
            var ok = new ForgeCommand.ForgeResult.Ok("done");
            assertInstanceOf(ForgeCommand.ForgeResult.class, ok);

            var err = new ForgeCommand.ForgeResult.Error("failed");
            assertInstanceOf(ForgeCommand.ForgeResult.class, err);

            var comp = new ForgeCommand.ForgeResult.ComparisonResult("diff");
            assertInstanceOf(ForgeCommand.ForgeResult.class, comp);

            var hist = new ForgeCommand.ForgeResult.HistoryResult(List.of());
            assertInstanceOf(ForgeCommand.ForgeResult.class, hist);
        }

        @Test
        void forge_command_json_roundtrip() throws Exception {
            // Commands with ActorRef can't be serialized, but the sealed hierarchy should compile
            // This tests the type discriminator works for events (which are serialized)
            var event = new ForgeEvent.SoulForged("d1", Instant.now(), 1, "h1");
            String json = MAPPER.writeValueAsString(event);
            assertTrue(json.contains("\"type\""));
            assertTrue(json.contains("SoulForged"));
        }
    }

    // --- Integration: Store + State ---

    @Nested
    class StoreStateIntegration {

        @Test
        void forge_flow_through_state_and_store(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                var manifest = testManifest("did:key:z6Mk1", 1);

                // Simulate ForgeActor flow: persist event + store manifest
                var state = ForgeState.empty();
                var event = new ForgeEvent.SoulForged(manifest.did(), Instant.now(),
                    manifest.manifestVersion(), manifest.contentHash());
                state = state.apply(event);
                store.store(manifest);

                // Verify state
                assertEquals(1, state.totalForges());
                assertTrue(state.knownSouls().containsKey("did:key:z6Mk1"));

                // Verify store
                var loaded = store.latest("did:key:z6Mk1");
                assertTrue(loaded.isPresent());

                // Simulate second forge
                var manifest2 = testManifest("did:key:z6Mk1", 2);
                state = state.apply(new ForgeEvent.SoulForged(manifest2.did(), Instant.now(),
                    manifest2.manifestVersion(), manifest2.contentHash()));
                store.store(manifest2);

                assertEquals(2, state.totalForges());
                assertEquals(2, state.knownSouls().get("did:key:z6Mk1").version());
                assertEquals(2, store.history("did:key:z6Mk1").size());
            }
        }

        @Test
        void birth_fork_archive_flow(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                // Birth
                var parent = testManifest("did:key:parent", 1);
                var state = ForgeState.empty()
                    .apply(new ForgeEvent.SoulBorn("did:key:parent", Instant.now(), "resilient"));
                store.store(parent);

                // Fork
                state = state.apply(new ForgeEvent.SoulForked("did:key:parent",
                    "did:key:child", Instant.now()));
                var child = testManifest("did:key:child", 1);
                store.store(child);

                assertEquals(2, state.knownSouls().size());
                assertTrue(store.exists("did:key:child"));

                // Archive parent
                state = state.apply(new ForgeEvent.SoulArchived("did:key:parent",
                    Instant.now(), "retired"));
                store.archive("did:key:parent", "retired");

                assertTrue(state.knownSouls().get("did:key:parent").archived());
                assertTrue(store.latest("did:key:parent").isEmpty()); // archived
                assertTrue(store.latest("did:key:child").isPresent()); // child still active
            }
        }
    }
}
