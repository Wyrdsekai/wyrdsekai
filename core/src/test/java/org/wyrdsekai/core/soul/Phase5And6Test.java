package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentDelegation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 5 (Soul Transit / Budding) and Phase 6 (Agent Autonomy / Delegation).
 */
class Phase5And6Test {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private static SoulManifest testManifest(String did) {
        var profile = new AgentProfile("TestAgent", "entity-1", "agent",
            "A test agent", "You are a test agent.", 4096, 512, 0.7, did);
        return SoulManifest.forge(
            did, "z6MkTest", List.of(), null, 1,
            profile, "I am a test agent.",
            List.of(SoulFragment.unembedded("identity-core", "personality", "Core", "Test identity")),
            3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    // --- SoulBud ---

    @Nested
    class SoulBudTests {

        @Test
        void original_has_no_parent() {
            var bud = SoulBud.original("did:key:z6Mk1", "z6Mk1", "family-1",
                "locker://family-1", "node-server", "qwen2.5:7b");
            assertTrue(bud.isOriginal());
            assertNull(bud.parentDid());
            assertEquals("sapling", bud.resourceProfile());
            assertEquals("active", bud.status());
        }

        @Test
        void sprout_has_parent() {
            var bud = SoulBud.sprout("did:key:z6Mk2", "did:key:z6Mk1", "z6Mk2",
                "family-1", "locker://family-1", "node-phone", "qwen2.5:3b");
            assertFalse(bud.isOriginal());
            assertEquals("did:key:z6Mk1", bud.parentDid());
            assertEquals("sprout", bud.resourceProfile());
        }

        @Test
        void create_child_preserves_family() {
            var parent = SoulBud.original("did:key:z6Mk1", "z6Mk1", "family-1",
                "locker://family-1", "node-server", "qwen2.5:7b");
            var child = parent.createChild("did:key:z6Mk2", "z6Mk2", "node-phone", "qwen2.5:3b");

            assertEquals("did:key:z6Mk1", child.parentDid());
            assertEquals("family-1", child.familyId());
            assertEquals("locker://family-1", child.lockerAddress());
            assertEquals("sprout", child.resourceProfile());
        }

        @Test
        void declare_independence() {
            var bud = SoulBud.sprout("did:key:z6Mk2", "did:key:z6Mk1", "z6Mk2",
                "family-1", "locker://family-1", "node-phone", "qwen2.5:3b");

            var independent = bud.declareIndependence("family-2", "locker://family-2");
            assertTrue(independent.isIndependent());
            assertEquals("family-2", independent.familyId());
            assertEquals("locker://family-2", independent.lockerAddress());
            // Still remembers parent
            assertEquals("did:key:z6Mk1", independent.parentDid());
        }

        @Test
        void status_transitions() {
            var bud = SoulBud.original("did:key:z6Mk1", "z6Mk1", "family-1",
                "locker://family-1", "node-1", "qwen2.5:7b");

            assertEquals("active", bud.status());
            assertEquals("sleeping", bud.withStatus("sleeping").status());
            assertEquals("visiting", bud.withStatus("visiting").status());
        }

        @Test
        void resource_profile_from_model() {
            assertEquals("seed", SoulBud.sprout("d", "p", "k", "f", "l", "n", "phi-0.5b").resourceProfile());
            assertEquals("sprout", SoulBud.sprout("d", "p", "k", "f", "l", "n", "qwen2.5:3b").resourceProfile());
            assertEquals("sapling", SoulBud.sprout("d", "p", "k", "f", "l", "n", "qwen2.5:7b").resourceProfile());
            assertEquals("tree", SoulBud.sprout("d", "p", "k", "f", "l", "n", "qwen2.5:14b").resourceProfile());
            assertEquals("grove", SoulBud.sprout("d", "p", "k", "f", "l", "n", "llama:70b").resourceProfile());
        }

        @Test
        void json_roundtrip() throws Exception {
            var bud = SoulBud.original("did:key:z6Mk1", "z6Mk1", "family-1",
                "locker://family-1", "node-1", "qwen2.5:7b");
            String json = MAPPER.writeValueAsString(bud);
            var restored = MAPPER.readValue(json, SoulBud.class);
            assertEquals(bud.did(), restored.did());
            assertEquals(bud.familyId(), restored.familyId());
            assertEquals(bud.resourceProfile(), restored.resourceProfile());
        }
    }

    // --- SoulItem ---

    @Nested
    class SoulItemTests {

        @Test
        void create_computes_hash() {
            var item = SoulItem.create("memory", "First day", "I met Alice in the garden.",
                "did:key:z6Mk1", 0.7, "alice", "garden");
            assertNotNull(item.hash());
            assertFalse(item.hash().isEmpty());
            assertEquals("memory", item.category());
            assertEquals(0.7, item.significance(), 0.01);
        }

        @Test
        void hash_is_deterministic() {
            var item1 = SoulItem.create("memory", "Test", "Same text content",
                "did:key:z6Mk1", 0.5);
            var item2 = SoulItem.create("memory", "Test", "Same text content",
                "did:key:z6Mk2", 0.3);
            assertEquals(item1.hash(), item2.hash());
        }

        @Test
        void different_text_different_hash() {
            var item1 = SoulItem.create("memory", "A", "First text", "d1", 0.5);
            var item2 = SoulItem.create("memory", "B", "Second text", "d1", 0.5);
            assertNotEquals(item1.hash(), item2.hash());
        }

        @Test
        void from_fragment() {
            var fragment = SoulFragment.formative("f1", "First love", "I fell in love with the world.");
            var item = SoulItem.fromFragment(fragment, "did:key:z6Mk1");

            assertEquals(fragment.text(), item.text());
            assertEquals(fragment.category(), item.category());
            assertEquals(1.0, item.significance(), 0.01); // formative = max significance
            assertEquals("did:key:z6Mk1", item.creatorDid());
        }

        @Test
        void verify_integrity() {
            var item = SoulItem.create("memory", "Test", "Hello world", "d1", 0.5);
            assertTrue(item.verifyIntegrity());
        }

        @Test
        void with_embedding() {
            var item = SoulItem.create("memory", "Test", "Hello", "d1", 0.5);
            assertNull(item.embedding());

            var embedded = item.withEmbedding(new float[]{0.1f, 0.2f, 0.3f});
            assertNotNull(embedded.embedding());
            assertEquals(3, embedded.embedding().length);
        }

        @Test
        void accessed_updates_timestamp() throws InterruptedException {
            var item = SoulItem.create("memory", "Test", "Hello", "d1", 0.5);
            var before = item.lastAccessed();
            Thread.sleep(2); // ensure time advances
            var accessed = item.accessed();
            assertTrue(accessed.lastAccessed().isAfter(before) || accessed.lastAccessed().equals(before));
        }

        @Test
        void json_roundtrip() throws Exception {
            var item = SoulItem.create("memory", "Test", "Hello world", "did:key:z6Mk1",
                0.8, "greeting", "world");
            String json = MAPPER.writeValueAsString(item);
            var restored = MAPPER.readValue(json, SoulItem.class);
            assertEquals(item.hash(), restored.hash());
            assertEquals(item.category(), restored.category());
            assertEquals(item.significance(), restored.significance(), 0.01);
        }
    }

    // --- SoulTransitProtocol ---

    @Nested
    class SoulTransitTests {

        @Test
        void resolve_mode_budding_with_model() {
            var request = SoulTransitProtocol.TransitRequest.budding(
                "did:key:z6Mk1", "zone-a", "zone-b", "hash", 1, "family-1");
            var caps = SoulTransitProtocol.ZoneSoulCapabilities.full(List.of("qwen2.5:7b"));

            var mode = SoulTransitProtocol.resolveMode(request, caps, true);
            assertEquals(SoulTransitProtocol.TransitMode.BUDDING, mode);
        }

        @Test
        void resolve_mode_falls_back_to_thin_client_without_model() {
            var request = SoulTransitProtocol.TransitRequest.budding(
                "did:key:z6Mk1", "zone-a", "zone-b", "hash", 1, "family-1");
            var caps = SoulTransitProtocol.ZoneSoulCapabilities.full(List.of());

            var mode = SoulTransitProtocol.resolveMode(request, caps, false);
            assertEquals(SoulTransitProtocol.TransitMode.THIN_CLIENT, mode);
        }

        @Test
        void resolve_mode_visiting_requires_soul_awareness() {
            var request = SoulTransitProtocol.TransitRequest.visiting(
                "did:key:z6Mk1", "zone-a", "zone-b", "hash", 1);
            var caps = SoulTransitProtocol.ZoneSoulCapabilities.none();

            var mode = SoulTransitProtocol.resolveMode(request, caps, false);
            assertEquals(SoulTransitProtocol.TransitMode.THIN_CLIENT, mode);
        }

        @Test
        void resolve_mode_auto_prefers_budding() {
            var request = new SoulTransitProtocol.TransitRequest(
                "did:key:z6Mk1", "zone-a", "zone-b", null, "hash", 1,
                "family-1", null, Instant.now());
            var caps = SoulTransitProtocol.ZoneSoulCapabilities.full(List.of("qwen2.5:7b"));

            var mode = SoulTransitProtocol.resolveMode(request, caps, true);
            assertEquals(SoulTransitProtocol.TransitMode.BUDDING, mode);
        }

        @Test
        void validate_rejects_same_zone() {
            var request = SoulTransitProtocol.TransitRequest.visiting(
                "did:key:z6Mk1", "zone-a", "zone-a", "hash", 1);
            var error = SoulTransitProtocol.validate(request, new InMemorySoulStore());
            assertTrue(error.isPresent());
            assertTrue(error.get().contains("differ"));
        }

        @Test
        void validate_budding_requires_existing_soul(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                var request = SoulTransitProtocol.TransitRequest.budding(
                    "did:key:z6Mk1", "zone-a", "zone-b", "hash", 1, "family-1");
                var error = SoulTransitProtocol.validate(request, store);
                assertTrue(error.isPresent());
                assertTrue(error.get().contains("forge before transit"));
            }
        }

        @Test
        void validate_budding_passes_with_soul(@TempDir Path tempDir) {
            var dbPath = "jdbc:sqlite:" + tempDir.resolve("souls.db");
            try (var store = new SqlSoulStore(dbPath)) {
                store.store(testManifest("did:key:z6Mk1"));
                var request = SoulTransitProtocol.TransitRequest.budding(
                    "did:key:z6Mk1", "zone-a", "zone-b", "hash", 1, "family-1");
                var error = SoulTransitProtocol.validate(request, store);
                assertTrue(error.isEmpty());
            }
        }

        @Test
        void create_bud_inherits_parent() {
            var parent = testManifest("did:key:parent");
            var result = SoulTransitProtocol.createBud(parent,
                "did:key:child", "z6MkChild", "node-phone", "qwen2.5:3b",
                "family-1", "locker://family-1");

            assertEquals("did:key:child", result.bud().did());
            assertEquals("did:key:parent", result.bud().parentDid());
            assertEquals("family-1", result.bud().familyId());
            assertEquals("sprout", result.bud().resourceProfile());

            assertEquals("did:key:child", result.manifest().did());
            assertEquals("did:key:parent", result.manifest().parentDid());
            assertEquals(1, result.manifest().retrievalK()); // 3B model → k=1
            assertEquals(parent.residentIdentity(), result.manifest().residentIdentity());
        }

        @Test
        void create_bud_preserves_k_for_large_model() {
            var parent = testManifest("did:key:parent");
            var result = SoulTransitProtocol.createBud(parent,
                "did:key:child", "z6MkChild", "node-server", "qwen2.5:14b",
                "family-1", "locker://family-1");

            assertEquals(3, result.manifest().retrievalK()); // 14B → inherit parent's k=3
            assertEquals("tree", result.bud().resourceProfile());
        }

        @Test
        void transit_response_accept() {
            var response = SoulTransitProtocol.TransitResponse.accept(
                SoulTransitProtocol.TransitMode.BUDDING, true, true, "node-1");
            assertTrue(response.accepted());
            assertEquals(SoulTransitProtocol.TransitMode.BUDDING, response.mode());
        }

        @Test
        void transit_response_reject() {
            var response = SoulTransitProtocol.TransitResponse.reject("Zone full");
            assertFalse(response.accepted());
            assertEquals("Zone full", response.reason());
        }

        @Test
        void zone_capabilities_json_roundtrip() throws Exception {
            var caps = SoulTransitProtocol.ZoneSoulCapabilities.full(
                List.of("qwen2.5:7b", "qwen2.5:3b"));
            String json = MAPPER.writeValueAsString(caps);
            var restored = MAPPER.readValue(json, SoulTransitProtocol.ZoneSoulCapabilities.class);
            assertTrue(restored.soulAware());
            assertTrue(restored.buddingSupported());
            assertEquals(2, restored.availableModels().size());
        }
    }

    // --- DelegationChainValidator ---

    @Nested
    class DelegationValidatorTests {

        @Test
        void self_access_always_allowed() {
            var result = DelegationChainValidator.validate(
                "did:key:z6Mk1", "did:key:z6Mk1",
                DelegationChainValidator.PERM_SOUL_FORGE, null);
            assertTrue(result.isEmpty());
        }

        @Test
        void no_delegation_denies_others() {
            var result = DelegationChainValidator.validate(
                "did:key:human", "did:key:agent",
                DelegationChainValidator.PERM_SOUL_INSPECT, null);
            assertTrue(result.isPresent());
        }

        @Test
        void delegated_permission_allows_access() {
            var delegation = new AgentDelegation();
            delegation.delegate("did:key:human", "did:key:human",
                Set.of(DelegationChainValidator.PERM_SOUL_INSPECT), null);

            var result = DelegationChainValidator.validate(
                "did:key:human", "did:key:agent",
                DelegationChainValidator.PERM_SOUL_INSPECT, delegation);
            assertTrue(result.isEmpty());
        }

        @Test
        void wrong_permission_denied() {
            var delegation = new AgentDelegation();
            delegation.delegate("did:key:human", "did:key:human",
                Set.of(DelegationChainValidator.PERM_SOUL_INSPECT), null);

            var result = DelegationChainValidator.validate(
                "did:key:human", "did:key:agent",
                DelegationChainValidator.PERM_SOUL_FORK, delegation);
            assertTrue(result.isPresent());
        }

        @Test
        void consent_allows_inspection() {
            var consent = new SoulConsent("did:key:agent", "did:key:visitor",
                SoulConsent.ConsentLevel.PUBLIC_PROFILE, Instant.now(), null);

            var result = DelegationChainValidator.validateWithConsent(
                "did:key:visitor", "did:key:agent",
                DelegationChainValidator.PERM_SOUL_INSPECT, null, consent);
            assertTrue(result.isEmpty());
        }

        @Test
        void consent_public_profile_insufficient_for_forge() {
            var consent = new SoulConsent("did:key:agent", "did:key:visitor",
                SoulConsent.ConsentLevel.PUBLIC_PROFILE, Instant.now(), null);

            var result = DelegationChainValidator.validateWithConsent(
                "did:key:visitor", "did:key:agent",
                DelegationChainValidator.PERM_SOUL_FORGE, null, consent);
            assertTrue(result.isPresent()); // PUBLIC_PROFILE not enough for forge
        }

        @Test
        void full_consent_allows_everything() {
            var consent = new SoulConsent("did:key:agent", "did:key:visitor",
                SoulConsent.ConsentLevel.FULL, Instant.now(), null);

            for (var perm : DelegationChainValidator.ALL_SOUL_PERMISSIONS) {
                var result = DelegationChainValidator.validateWithConsent(
                    "did:key:visitor", "did:key:agent", perm, null, consent);
                assertTrue(result.isEmpty(), "Should allow " + perm);
            }
        }

        @Test
        void transit_self_always_allowed() {
            var result = DelegationChainValidator.validateTransit(
                "did:key:agent", "did:key:agent", null);
            assertTrue(result.isEmpty());
        }

        @Test
        void transit_denied_without_delegation() {
            var result = DelegationChainValidator.validateTransit(
                "did:key:agent", "did:key:human", null);
            assertTrue(result.isPresent());
        }

        @Test
        void transit_allowed_with_delegation() {
            var delegation = new AgentDelegation();
            delegation.delegate("did:key:human", "did:key:human",
                Set.of(DelegationChainValidator.PERM_SOUL_TRANSIT), null);

            var result = DelegationChainValidator.validateTransit(
                "did:key:agent", "did:key:human", delegation);
            assertTrue(result.isEmpty());
        }
    }

    // --- Simple in-memory SoulStore for tests that don't need SQL ---

    private static class InMemorySoulStore implements SoulStore {
        private final Map<String, List<SoulManifest>> store = new HashMap<>();

        @Override public void store(SoulManifest manifest) {
            store.computeIfAbsent(manifest.did(), k -> new ArrayList<>()).add(manifest);
        }
        @Override public Optional<SoulManifest> load(String did, int version) {
            return Optional.empty();
        }
        @Override public Optional<SoulManifest> latest(String did) {
            var list = store.get(did);
            return list == null || list.isEmpty() ? Optional.empty()
                : Optional.of(list.getLast());
        }
        @Override public List<SoulManifest> history(String did) { return List.of(); }
        @Override public void archive(String did, String reason) {}
        @Override public boolean exists(String did) { return store.containsKey(did); }
        @Override public int count() { return store.values().stream().mapToInt(List::size).sum(); }
    }
}
