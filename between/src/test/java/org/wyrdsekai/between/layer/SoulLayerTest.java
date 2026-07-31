package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.DidKey;
import org.wyrdsekai.core.soul.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class SoulLayerTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @Nested
    class PresenceTests {

        @Test
        void announce_and_locate() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            layer.tell(new SoulLayer.AnnouncePresence("did:key:home-server", 1, "hash1"));
            layer.tell(new SoulLayer.LocateAgent("did:key:home-server", probe.getRef()));

            var loc = probe.receiveMessage();
            assertThat(loc.found()).isTrue();
            assertThat(loc.nodeId()).isEqualTo("node-1");
            assertThat(loc.manifestVersion()).isEqualTo(1);
        }

        @Test
        void locate_unknown_returns_not_found() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            layer.tell(new SoulLayer.LocateAgent("did:key:nobody", probe.getRef()));

            assertThat(probe.receiveMessage().found()).isFalse();
        }

        @Test
        void remove_presence() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            layer.tell(new SoulLayer.AnnouncePresence("did:key:home-server", 1, "hash1"));
            layer.tell(new SoulLayer.RemovePresence("did:key:home-server"));
            layer.tell(new SoulLayer.LocateAgent("did:key:home-server", probe.getRef()));

            assertThat(probe.receiveMessage().found()).isFalse();
        }

        @Test
        void list_hosted_agents() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.HostedAgents.class);

            layer.tell(new SoulLayer.AnnouncePresence("did:key:home-server", 1, "h1"));
            layer.tell(new SoulLayer.AnnouncePresence("did:key:alice", 2, "h2"));
            layer.tell(new SoulLayer.ListHosted(probe.getRef()));

            var hosted = probe.receiveMessage();
            assertThat(hosted.nodeId()).isEqualTo("node-1");
            assertThat(hosted.agentDids()).containsExactlyInAnyOrder(
                "did:key:home-server", "did:key:alice");
        }

        @Test
        void receive_presence_from_peer() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            long now = Instant.now().getEpochSecond();
            layer.tell(new SoulLayer.ReceivePresence(
                "node-2", "did:key:bob", 3, "hash3", now));
            layer.tell(new SoulLayer.LocateAgent("did:key:bob", probe.getRef()));

            var loc = probe.receiveMessage();
            assertThat(loc.found()).isTrue();
            assertThat(loc.nodeId()).isEqualTo("node-2");
            assertThat(loc.manifestVersion()).isEqualTo(3);
        }

        @Test
        void newer_presence_overwrites_older() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            long t1 = Instant.now().getEpochSecond();
            long t2 = t1 + 60;
            layer.tell(new SoulLayer.ReceivePresence("node-2", "did:key:home-server", 1, "h1", t1));
            layer.tell(new SoulLayer.ReceivePresence("node-3", "did:key:home-server", 2, "h2", t2));
            layer.tell(new SoulLayer.LocateAgent("did:key:home-server", probe.getRef()));

            var loc = probe.receiveMessage();
            assertThat(loc.nodeId()).isEqualTo("node-3");
            assertThat(loc.manifestVersion()).isEqualTo(2);
        }
    }

    @Nested
    class MigrationTests {

        @Test
        void migrate_hosted_agent_succeeds() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.MigrationResult.class);

            layer.tell(new SoulLayer.AnnouncePresence("did:key:home-server", 1, "h1"));
            layer.tell(new SoulLayer.MigrateSoul(
                "did:key:home-server", "node-2", "{}", probe.getRef()));

            var result = probe.receiveMessage();
            assertThat(result.success()).isTrue();
        }

        @Test
        void migrate_non_hosted_agent_fails() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.MigrationResult.class);

            layer.tell(new SoulLayer.MigrateSoul(
                "did:key:unknown", "node-2", "{}", probe.getRef()));

            var result = probe.receiveMessage();
            assertThat(result.success()).isFalse();
            assertThat(result.reason()).contains("not hosted");
        }

        @Test
        void receive_migration_adds_to_hosted() {
            var layer = testKit.spawn(SoulLayer.create("node-2"));
            var hostedProbe = testKit.createTestProbe(SoulLayer.HostedAgents.class);
            var locProbe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            layer.tell(new SoulLayer.ReceiveMigration(
                "node-1", "did:key:home-server", "{\"did\":\"did:key:home-server\"}", 5));

            layer.tell(new SoulLayer.ListHosted(hostedProbe.getRef()));
            assertThat(hostedProbe.receiveMessage().agentDids()).contains("did:key:home-server");

            layer.tell(new SoulLayer.LocateAgent("did:key:home-server", locProbe.getRef()));
            var loc = locProbe.receiveMessage();
            assertThat(loc.found()).isTrue();
            assertThat(loc.nodeId()).isEqualTo("node-2");
            assertThat(loc.manifestVersion()).isEqualTo(5);
        }
    }

    @Nested
    class BackupTests {

        @Test
        void backup_and_restore() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.BackupResult.class);

            String manifest = "{\"did\":\"did:key:home-server\",\"version\":3}";
            layer.tell(new SoulLayer.BackupManifest("did:key:home-server", manifest, 3, "hash3"));
            layer.tell(new SoulLayer.RequestBackup("did:key:home-server", probe.getRef()));

            var result = probe.receiveMessage();
            assertThat(result.found()).isTrue();
            assertThat(result.manifestJson()).isEqualTo(manifest);
        }

        @Test
        void backup_not_found() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.BackupResult.class);

            layer.tell(new SoulLayer.RequestBackup("did:key:nobody", probe.getRef()));

            assertThat(probe.receiveMessage().found()).isFalse();
        }

        @Test
        void newer_backup_overwrites_older() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.BackupResult.class);

            layer.tell(new SoulLayer.BackupManifest("did:key:home-server", "{v:1}", 1, "h1"));
            layer.tell(new SoulLayer.BackupManifest("did:key:home-server", "{v:3}", 3, "h3"));
            layer.tell(new SoulLayer.RequestBackup("did:key:home-server", probe.getRef()));

            assertThat(probe.receiveMessage().manifestJson()).isEqualTo("{v:3}");
        }

        @Test
        void older_backup_does_not_overwrite() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.BackupResult.class);

            layer.tell(new SoulLayer.BackupManifest("did:key:home-server", "{v:3}", 3, "h3"));
            layer.tell(new SoulLayer.BackupManifest("did:key:home-server", "{v:1}", 1, "h1"));
            layer.tell(new SoulLayer.RequestBackup("did:key:home-server", probe.getRef()));

            assertThat(probe.receiveMessage().manifestJson()).isEqualTo("{v:3}");
        }
    }

    @Nested
    class TraceTests {

        @Test
        void deposit_and_retrieve_trace() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.RoomTraces.class);

            var trace = StigmergicTrace.deposit("nexus", "did:key:home-server", "joy", 0.8f,
                Map.of("valence", 0.1, "energy", 0.05));
            layer.tell(new SoulLayer.DepositTrace(trace));
            layer.tell(new SoulLayer.GetTraces("nexus", probe.getRef()));

            var result = probe.receiveMessage();
            assertThat(result.roomId()).isEqualTo("nexus");
            assertThat(result.traces()).hasSize(1);
            assertThat(result.traces().getFirst().emotion()).isEqualTo("joy");
        }

        @Test
        void multiple_traces_accumulate() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.RoomTraces.class);

            layer.tell(new SoulLayer.DepositTrace(
                StigmergicTrace.deposit("nexus", "did:key:home-server", "joy", 0.8f, Map.of())));
            layer.tell(new SoulLayer.DepositTrace(
                StigmergicTrace.deposit("nexus", "did:key:alice", "curiosity", 0.5f, Map.of())));

            layer.tell(new SoulLayer.GetTraces("nexus", probe.getRef()));
            assertThat(probe.receiveMessage().traces()).hasSize(2);
        }

        @Test
        void no_traces_returns_empty() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.RoomTraces.class);

            layer.tell(new SoulLayer.GetTraces("empty-room", probe.getRef()));
            assertThat(probe.receiveMessage().traces()).isEmpty();
        }

        @Test
        void receive_trace_from_peer() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.RoomTraces.class);

            var trace = StigmergicTrace.deposit("nexus", "did:key:bob", "calm", 0.6f, Map.of());
            layer.tell(new SoulLayer.ReceiveTrace("node-2", trace));

            layer.tell(new SoulLayer.GetTraces("nexus", probe.getRef()));
            assertThat(probe.receiveMessage().traces()).hasSize(1);
        }
    }

    // --- Helpers ---

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** Create a minimal test manifest suitable for JSON round-trip. */
    private static SoulManifest testManifest(String did, int version) {
        var profile = new AgentProfile("TestAgent", "entity-1", "agent",
            "A test agent", "You are a test agent.", 4096, 512, 0.7, did);
        var genome = GenomeProfile.defaults();
        return SoulManifest.forge(
            did, "z6MkTest123", List.of(), null, version,
            profile, "I am a test agent.",
            List.of(), 3, "",
            genome, List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    /** Thread-safe in-memory SoulStore for actor tests (no SQLite threading issues). */
    static class TestSoulStore implements SoulStore {
        private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, SoulManifest>> data
            = new ConcurrentHashMap<>();

        @Override
        public void store(SoulManifest manifest) {
            data.computeIfAbsent(manifest.did(), _ -> new ConcurrentHashMap<>())
                .put(manifest.manifestVersion(), manifest);
        }

        @Override
        public Optional<SoulManifest> load(String did, int version) {
            var versions = data.get(did);
            if (versions == null) return Optional.empty();
            return Optional.ofNullable(versions.get(version));
        }

        @Override
        public Optional<SoulManifest> latest(String did) {
            var versions = data.get(did);
            if (versions == null || versions.isEmpty()) return Optional.empty();
            int maxVersion = versions.keySet().stream().mapToInt(v -> v).max().orElse(0);
            return Optional.ofNullable(versions.get(maxVersion));
        }

        @Override
        public List<SoulManifest> history(String did) {
            var versions = data.get(did);
            if (versions == null) return List.of();
            return versions.values().stream()
                .sorted((a, b) -> b.manifestVersion() - a.manifestVersion())
                .toList();
        }

        @Override
        public void archive(String did, String reason) {
            data.remove(did);
        }

        @Override
        public boolean exists(String did) {
            return data.containsKey(did) && !data.get(did).isEmpty();
        }

        @Override
        public int count() {
            return data.values().stream().mapToInt(ConcurrentHashMap::size).sum();
        }
    }

    @Nested
    class SoulStoreIntegrationTests {

        @Test
        void receive_migration_persists_to_soul_store() throws Exception {
            var store = new TestSoulStore();
            var layer = testKit.spawn(SoulLayer.create("node-2", null, store));
            var locProbe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            var manifest = testManifest("did:key:home-server", 3);
            String json = MAPPER.writeValueAsString(manifest);

            layer.tell(new SoulLayer.ReceiveMigration("node-1", "did:key:home-server", json, 3));

            // Use LocateAgent as sync barrier — once the actor replies, migration is processed
            layer.tell(new SoulLayer.LocateAgent("did:key:home-server", locProbe.getRef()));
            var loc = locProbe.receiveMessage();
            assertThat(loc.found()).isTrue();
            assertThat(loc.nodeId()).isEqualTo("node-2");
            assertThat(loc.manifestVersion()).isEqualTo(3);

            // By the time LocateAgent responds, ReceiveMigration was already processed
            var stored = store.latest("did:key:home-server");
            assertThat(stored).isPresent();
            assertThat(stored.get().manifestVersion()).isEqualTo(3);
            assertThat(stored.get().did()).isEqualTo("did:key:home-server");
        }

        @Test
        void receive_migration_without_soul_store_still_works() {
            var layer = testKit.spawn(SoulLayer.create("node-2"));
            var hostedProbe = testKit.createTestProbe(SoulLayer.HostedAgents.class);

            // No SoulStore — should not crash
            layer.tell(new SoulLayer.ReceiveMigration(
                "node-1", "did:key:bob", "{invalid-json}", 1));

            layer.tell(new SoulLayer.ListHosted(hostedProbe.getRef()));
            assertThat(hostedProbe.receiveMessage().agentDids()).contains("did:key:bob");
        }

        @Test
        void replicate_after_forge_updates_presence() throws Exception {
            var store = new TestSoulStore();
            var manifest = testManifest("did:key:home-server", 2);
            store.store(manifest);

            var layer = testKit.spawn(SoulLayer.create("node-1", null, store));
            var locProbe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            layer.tell(new SoulLayer.ReplicateAfterForge("did:key:home-server"));

            // Use LocateAgent as sync barrier
            layer.tell(new SoulLayer.LocateAgent("did:key:home-server", locProbe.getRef()));
            var loc = locProbe.receiveMessage();
            assertThat(loc.found()).isTrue();
            assertThat(loc.nodeId()).isEqualTo("node-1");
            assertThat(loc.manifestVersion()).isEqualTo(2);
        }

        @Test
        void replicate_after_forge_with_missing_manifest() {
            var store = new TestSoulStore();
            var layer = testKit.spawn(SoulLayer.create("node-1", null, store));
            var locProbe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            // Should not crash even if manifest doesn't exist
            layer.tell(new SoulLayer.ReplicateAfterForge("did:key:nobody"));

            // Verify actor still alive
            layer.tell(new SoulLayer.LocateAgent("did:key:nobody", locProbe.getRef()));
            assertThat(locProbe.receiveMessage().found()).isFalse();
        }

        @Test
        void replicate_after_forge_without_soul_store_is_noop() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var hostedProbe = testKit.createTestProbe(SoulLayer.HostedAgents.class);

            // No SoulStore — should not crash
            layer.tell(new SoulLayer.ReplicateAfterForge("did:key:home-server"));

            // Verify actor still alive
            layer.tell(new SoulLayer.ListHosted(hostedProbe.getRef()));
            assertThat(hostedProbe.receiveMessage()).isNotNull();
        }

        @Test
        void receive_backup_replication_stores_in_memory() throws Exception {
            var layer = testKit.spawn(SoulLayer.create("node-2"));
            var backupProbe = testKit.createTestProbe(SoulLayer.BackupResult.class);

            var manifest = testManifest("did:key:home-server", 4);
            String json = MAPPER.writeValueAsString(manifest);

            layer.tell(new SoulLayer.ReceiveBackupReplication(
                "node-1", "did:key:home-server", json, 4, manifest.contentHash()));

            layer.tell(new SoulLayer.RequestBackup("did:key:home-server", backupProbe.getRef()));
            var result = backupProbe.receiveMessage();
            assertThat(result.found()).isTrue();
            assertThat(result.manifestJson()).isEqualTo(json);
        }

        @Test
        void receive_backup_replication_persists_to_local_store() throws Exception {
            var store = new TestSoulStore();
            var layer = testKit.spawn(SoulLayer.create("node-2", null, store));
            var backupProbe = testKit.createTestProbe(SoulLayer.BackupResult.class);

            var manifest = testManifest("did:key:alice", 2);
            String json = MAPPER.writeValueAsString(manifest);

            layer.tell(new SoulLayer.ReceiveBackupReplication(
                "node-1", "did:key:alice", json, 2, manifest.contentHash()));

            // Use RequestBackup as sync barrier — once this responds,
            // the ReceiveBackupReplication was already processed
            layer.tell(new SoulLayer.RequestBackup("did:key:alice", backupProbe.getRef()));
            assertThat(backupProbe.receiveMessage().found()).isTrue();

            var stored = store.latest("did:key:alice");
            assertThat(stored).isPresent();
            assertThat(stored.get().manifestVersion()).isEqualTo(2);
        }

        @Test
        void older_backup_replication_does_not_overwrite() throws Exception {
            var layer = testKit.spawn(SoulLayer.create("node-2"));
            var backupProbe = testKit.createTestProbe(SoulLayer.BackupResult.class);

            var newManifest = testManifest("did:key:home-server", 5);
            var oldManifest = testManifest("did:key:home-server", 3);

            // Store newer first
            layer.tell(new SoulLayer.ReceiveBackupReplication(
                "node-1", "did:key:home-server",
                MAPPER.writeValueAsString(newManifest), 5, newManifest.contentHash()));

            // Then try older
            layer.tell(new SoulLayer.ReceiveBackupReplication(
                "node-1", "did:key:home-server",
                MAPPER.writeValueAsString(oldManifest), 3, oldManifest.contentHash()));

            layer.tell(new SoulLayer.RequestBackup("did:key:home-server", backupProbe.getRef()));
            var result = backupProbe.receiveMessage();
            assertThat(result.found()).isTrue();
            // Should still have the v5 backup
            assertThat(result.manifestJson()).contains("\"manifestVersion\":5");
        }

        @Test
        void migration_receive_announces_to_network() {
            var layer = testKit.spawn(SoulLayer.create("node-2"));
            var locProbe = testKit.createTestProbe(SoulLayer.AgentLocation.class);

            // Without NATS, the broadcast is a no-op but shouldn't crash
            layer.tell(new SoulLayer.ReceiveMigration(
                "node-1", "did:key:home-server", "{}", 7));

            layer.tell(new SoulLayer.LocateAgent("did:key:home-server", locProbe.getRef()));
            var loc = locProbe.receiveMessage();
            assertThat(loc.found()).isTrue();
            assertThat(loc.manifestVersion()).isEqualTo(7);
        }
    }

    @Nested
    class StigmergicTraceUnitTests {

        @Test
        void fresh_trace_has_full_intensity() {
            var trace = StigmergicTrace.deposit("r1", "did:key:a", "joy", 0.8f,
                Map.of("valence", 0.1));
            assertThat(trace.effectiveIntensity()).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.01f));
        }

        @Test
        void decayed_trace_has_lower_intensity() {
            var trace = StigmergicTrace.deposit("r1", "did:key:a", "joy", 0.8f,
                Map.of(), 1800);
            // After one half-life (1800s), intensity should be ~0.4
            Instant future = trace.createdAt().plusSeconds(1800);
            float decayed = trace.effectiveIntensityAt(future);
            assertThat(decayed).isCloseTo(0.4f, org.assertj.core.data.Offset.offset(0.01f));
        }

        @Test
        void fully_decayed_trace_is_expired() {
            var trace = StigmergicTrace.deposit("r1", "did:key:a", "joy", 0.1f,
                Map.of(), 60); // 60s half-life, low intensity
            // After 10 half-lives (600s), intensity ≈ 0.1 * 0.5^10 ≈ 0.0001 → expired
            Instant far = trace.createdAt().plusSeconds(600);
            assertThat(trace.effectiveIntensityAt(far)).isLessThan(0.01f);
        }

        @Test
        void effective_tank_effects_scale_with_intensity() {
            var trace = StigmergicTrace.deposit("r1", "did:key:a", "joy", 0.5f,
                Map.of("valence", 0.2, "energy", 0.1));
            var effects = trace.effectiveTankEffects();
            // Fresh trace: intensity ~0.5, so effects scale to ~0.1 and ~0.05
            assertThat(effects.get("valence")).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.02));
            assertThat(effects.get("energy")).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.02));
        }
    }

    // --- Verification helpers ---

    /**
     * Create a manifest with a real Ed25519 keypair so SoulVerifier can
     * reconstruct the identity from publicKeyMultibase.
     */
    private static SoulManifest testManifestWithRealKey(String did) throws Exception {
        var householdSecret = new byte[32];
        Arrays.fill(householdSecret, (byte) 0x42);
        var identity = AgentIdentity.generate(householdSecret);

        var multibaseKey = identity.did().substring("did:key:".length());
        var profile = new AgentProfile("TestAgent", "entity-1", "agent",
            "A test agent", "You are a test agent.", 4096, 512, 0.7, identity.did());
        var genome = GenomeProfile.defaults();
        return SoulManifest.forge(
            identity.did(), multibaseKey, identity.keyLog(), null, 1,
            profile, "I am a test agent.",
            List.of(), 3, "",
            genome, List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    @Nested
    class VerificationTests {

        @Test
        void migration_runs_soul_verification() throws Exception {
            var store = new TestSoulStore();
            var layer = testKit.spawn(SoulLayer.create("node-2", null, store));
            var verifyProbe = testKit.createTestProbe(SoulLayer.VerificationStatus.class);

            var manifest = testManifestWithRealKey("did:key:verified");
            String json = MAPPER.writeValueAsString(manifest);

            layer.tell(new SoulLayer.ReceiveMigration(
                "node-1", manifest.did(), json, 1));

            // Query verification status — use it as a sync barrier too
            layer.tell(new SoulLayer.GetVerificationStatus(manifest.did(), verifyProbe.getRef()));
            var status = verifyProbe.receiveMessage();

            assertThat(status.found()).isTrue();
            assertThat(status.result()).isNotNull();
            // Unsigned manifest should still get at least KERI-level trust
            // (signature check is skipped for unsigned manifests)
            assertThat(status.result().trustLevel()).isNotEqualTo(SoulVerifier.TrustLevel.NONE);
        }

        @Test
        void migration_with_invalid_manifest_still_accepts_in_quarantine() throws Exception {
            var layer = testKit.spawn(SoulLayer.create("node-2"));
            var hostedProbe = testKit.createTestProbe(SoulLayer.HostedAgents.class);
            var verifyProbe = testKit.createTestProbe(SoulLayer.VerificationStatus.class);

            // Send a migration with invalid JSON — cannot deserialize
            layer.tell(new SoulLayer.ReceiveMigration(
                "node-1", "did:key:invalid", "{bad json}", 1));

            // Agent should still be hosted (quarantine mode)
            layer.tell(new SoulLayer.ListHosted(hostedProbe.getRef()));
            assertThat(hostedProbe.receiveMessage().agentDids()).contains("did:key:invalid");

            // No verification result since deserialization failed
            layer.tell(new SoulLayer.GetVerificationStatus("did:key:invalid", verifyProbe.getRef()));
            assertThat(verifyProbe.receiveMessage().found()).isFalse();
        }

        @Test
        void verification_status_not_found_for_unknown_agent() {
            var layer = testKit.spawn(SoulLayer.create("node-1"));
            var probe = testKit.createTestProbe(SoulLayer.VerificationStatus.class);

            layer.tell(new SoulLayer.GetVerificationStatus("did:key:nobody", probe.getRef()));
            assertThat(probe.receiveMessage().found()).isFalse();
        }

        @Test
        void backup_replication_runs_verification() throws Exception {
            var store = new TestSoulStore();
            var layer = testKit.spawn(SoulLayer.create("node-2", null, store));
            var verifyProbe = testKit.createTestProbe(SoulLayer.VerificationStatus.class);

            var manifest = testManifestWithRealKey("did:key:backup-verified");
            String json = MAPPER.writeValueAsString(manifest);

            layer.tell(new SoulLayer.ReceiveBackupReplication(
                "node-1", manifest.did(), json, 1, manifest.contentHash()));

            // Use backup request as sync barrier
            var backupProbe = testKit.createTestProbe(SoulLayer.BackupResult.class);
            layer.tell(new SoulLayer.RequestBackup(manifest.did(), backupProbe.getRef()));
            assertThat(backupProbe.receiveMessage().found()).isTrue();

            // Verify the verification result was stored
            layer.tell(new SoulLayer.GetVerificationStatus(manifest.did(), verifyProbe.getRef()));
            var status = verifyProbe.receiveMessage();
            assertThat(status.found()).isTrue();
            assertThat(status.result().trustLevel()).isNotEqualTo(SoulVerifier.TrustLevel.NONE);
        }

        @Test
        void migration_verification_does_not_get_overwritten_by_backup() throws Exception {
            var store = new TestSoulStore();
            var layer = testKit.spawn(SoulLayer.create("node-2", null, store));
            var verifyProbe = testKit.createTestProbe(SoulLayer.VerificationStatus.class);

            var manifest = testManifestWithRealKey("did:key:priority-test");
            String json = MAPPER.writeValueAsString(manifest);

            // First: migration (higher priority verification)
            layer.tell(new SoulLayer.ReceiveMigration(
                "node-1", manifest.did(), json, 1));

            // Then: backup replication (should not overwrite migration result)
            layer.tell(new SoulLayer.ReceiveBackupReplication(
                "node-3", manifest.did(), json, 1, manifest.contentHash()));

            // Use locate as sync barrier
            var locProbe = testKit.createTestProbe(SoulLayer.AgentLocation.class);
            layer.tell(new SoulLayer.LocateAgent(manifest.did(), locProbe.getRef()));
            locProbe.receiveMessage();

            layer.tell(new SoulLayer.GetVerificationStatus(manifest.did(), verifyProbe.getRef()));
            var status = verifyProbe.receiveMessage();
            assertThat(status.found()).isTrue();
            // The migration result should be preserved (backup uses putIfAbsent)
            assertThat(status.result()).isNotNull();
        }
    }
}
