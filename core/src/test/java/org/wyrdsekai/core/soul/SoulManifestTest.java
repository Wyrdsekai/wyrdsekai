package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SoulManifestTest {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    // ==================== VitalitySnapshot ====================

    @Test
    void snapshot_has_12_tanks() {
        // Phase 1A: 12 → 24.
        // Wave 1: + soothing → 25.
        // Wave 1.5: + allostaticLoad + equanimity → 27
        // (10 original runtime + 10 deprivation-shape + 3 substrate-truth triad + 4 soul-only).
        // Test name kept for git-blame continuity.
        var snap = VitalitySnapshot.defaults();
        assertThat(snap.tanks()).hasSize(27);
        assertThat(VitalitySnapshot.TANK_NAMES).hasSize(27);
        for (var name : VitalitySnapshot.TANK_NAMES) {
            assertThat(snap.tanks()).containsKey(name);
        }
    }

    @Test
    void snapshot_includes_new_tanks() {
        var snap = VitalitySnapshot.defaults();
        assertThat(snap.tank("valence")).isEqualTo(0.5);
        assertThat(snap.tank("safety")).isEqualTo(0.6);
        assertThat(snap.tank("resonance")).isEqualTo(0.5);
        assertThat(snap.tank("curiosity")).isEqualTo(0.5);
    }

    @Test
    void snapshot_unknown_tank_returns_default() {
        var snap = VitalitySnapshot.defaults();
        assertThat(snap.tank("nonexistent")).isEqualTo(0.5);
    }

    // ==================== MemoryNode ====================

    @Test
    void memory_node_neutral_creation() {
        var node = MemoryNode.neutral("m1", "Hello world", List.of("greeting"));
        assertThat(node.importance()).isEqualTo(0.5f);
        assertThat(node.impressionDepth()).isEqualTo(0.0f);
        assertThat(node.formative()).isFalse();
        assertThat(node.primaryEmotion()).isEqualTo("none");
    }

    @Test
    void memory_node_formative_creation() {
        var node = MemoryNode.formative("m2", "The day everything changed",
            List.of("change", "identity"), "grief", 0.9f);
        assertThat(node.formative()).isTrue();
        assertThat(node.importance()).isEqualTo(1.0f);
        assertThat(node.impressionDepth()).isEqualTo(0.9f);
    }

    @Test
    void memory_node_formative_never_decays() {
        var node = MemoryNode.formative("m3", "Load-bearing memory",
            List.of(), "joy", 0.8f);
        var decayed = node.decayed(1.0f); // maximum decay rate
        assertThat(decayed.importance()).isEqualTo(1.0f); // unchanged
        assertThat(decayed).isEqualTo(node); // exactly the same
    }

    @Test
    void memory_node_high_impression_resists_decay() {
        var highCharge = new MemoryNode("m4", "Emotionally charged",
            List.of(), 0.8f, 0.9f, false, "grief", Instant.now(), 0, "en");
        var lowCharge = new MemoryNode("m5", "Forgettable",
            List.of(), 0.8f, 0.0f, false, "none", Instant.now(), 0, "en");

        var decayedHigh = highCharge.decayed(0.5f);
        var decayedLow = lowCharge.decayed(0.5f);

        assertThat(decayedHigh.importance()).isGreaterThan(decayedLow.importance());
    }

    @Test
    void memory_node_access_boosts_importance() {
        var node = MemoryNode.neutral("m6", "Recalled often", List.of());
        var accessed = node.accessed();
        assertThat(accessed.importance()).isGreaterThan(node.importance());
        assertThat(accessed.accessCount()).isEqualTo(1);
    }

    // ==================== CompactedMemory ====================

    @Test
    void compacted_memory_empty() {
        var mem = CompactedMemory.empty();
        assertThat(mem.nodes()).isEmpty();
        assertThat(mem.links()).isEmpty();
        assertThat(mem.formativeCount()).isZero();
    }

    @Test
    void compacted_memory_formative_count() {
        var mem = new CompactedMemory(
            List.of(
                MemoryNode.neutral("n1", "Normal", List.of()),
                MemoryNode.formative("n2", "Key moment", List.of(), "joy", 0.9f),
                MemoryNode.formative("n3", "Another key", List.of(), "grief", 0.8f)
            ),
            List.of(),
            Map.of()
        );
        assertThat(mem.formativeCount()).isEqualTo(2);
    }

    // ==================== SoulFragment ====================

    @Test
    void fragment_unembedded() {
        var frag = SoulFragment.unembedded("id-core", "personality",
            "Core identity", "A philosophical wanderer...");
        assertThat(frag.isEmbedded()).isFalse();
        assertThat(frag.formative()).isFalse();
    }

    @Test
    void fragment_with_embedding() {
        var frag = SoulFragment.unembedded("id-core", "personality",
            "Core identity", "Text here");
        var embedded = frag.withEmbedding(new float[]{0.1f, 0.2f, 0.3f}, "all-minilm");
        assertThat(embedded.isEmbedded()).isTrue();
        assertThat(embedded.embeddingModel()).isEqualTo("all-minilm");
        assertThat(embedded.embedding()).hasSize(3);
    }

    @Test
    void fragment_formative() {
        var frag = SoulFragment.formative("formative-01",
            "The day I stopped wanting to be an astronaut",
            "Watching the Challenger explosion on TV...");
        assertThat(frag.formative()).isTrue();
        assertThat(frag.category()).isEqualTo("memory");
    }

    // ==================== GenomeProfile ====================

    @Test
    void genome_defaults() {
        var genome = GenomeProfile.defaults();
        assertThat(genome.name()).isEqualTo("default");
        assertThat(genome.sensitivity()).isNotEmpty();
        assertThat(genome.baselines()).isNotEmpty();
        assertThat(genome.decayRates()).isNotEmpty();
    }

    @Test
    void genome_randomized_is_unique() {
        var g1 = GenomeProfile.randomized("agent-1");
        var g2 = GenomeProfile.randomized("agent-2");
        // Extremely unlikely that two random genomes match
        assertThat(g1.sensitivity()).isNotEqualTo(g2.sensitivity());
    }

    @Test
    void genome_apply_modifies_state() {
        var genome = GenomeProfile.defaults();
        var state = GenomeProfile.defaultState();
        double originalValence = state.get("valence");

        var perturbations = Map.of("valence", -0.5, "energy", -0.2);
        genome.applyAndDescribe(perturbations, 0.8, 0.9, state);

        assertThat(state.get("valence")).isLessThan(originalValence);
    }

    @Test
    void genome_describe_state_produces_text() {
        var state = GenomeProfile.defaultState();
        state.put("valence", 0.15);
        state.put("energy", 0.9);
        var desc = GenomeProfile.describeState(state);
        assertThat(desc).contains("heavy");
        assertThat(desc).contains("energetic");
    }

    // ==================== Relationship ====================

    @Test
    void relationship_acquaintance() {
        var rel = Relationship.acquaintance("did:key:z6Mk123", "Alice");
        assertThat(rel.trust()).isEqualTo(0.3f);
        assertThat(rel.bondDepth()).isZero();
        assertThat(rel.interactionCount()).isEqualTo(1);
    }

    // ==================== BehavioralFingerprint ====================

    @Test
    void fingerprint_empty() {
        var fp = BehavioralFingerprint.empty();
        assertThat(fp.baselineVitality()).isEmpty();
        assertThat(fp.stylisticMarkers()).isEmpty();
        assertThat(fp.averageResponseLength()).isZero();
    }

    @Test
    void fingerprint_merge() {
        var existing = new BehavioralFingerprint(
            Map.of("energy", 0.7f), Map.of(), Map.of(),
            Map.of("say", 0.6f), Map.of(), Map.of(),
            100.0f, 50.0f, List.of("indeed"), Map.of()
        );
        var fresh = new BehavioralFingerprint(
            Map.of("energy", 0.3f), Map.of(), Map.of(),
            Map.of("say", 0.4f), Map.of(), Map.of(),
            200.0f, 80.0f, List.of("perhaps"), Map.of()
        );

        var merged = BehavioralFingerprint.merge(existing, fresh, 0.3f);

        // 70% existing + 30% fresh
        assertThat(merged.baselineVitality().get("energy"))
            .isCloseTo(0.58f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(merged.averageResponseLength())
            .isCloseTo(130.0f, org.assertj.core.data.Offset.offset(1.0f));
        assertThat(merged.stylisticMarkers()).containsExactly("perhaps"); // fresh wins
    }

    // ==================== SoulConsent ====================

    @Test
    void consent_valid_permanent() {
        var consent = new SoulConsent("did:owner", "did:requester",
            SoulConsent.ConsentLevel.FULL, Instant.now(), null);
        assertThat(consent.isValid()).isTrue();
        assertThat(consent.covers("did:requester")).isTrue();
        assertThat(consent.covers("did:other")).isFalse();
    }

    @Test
    void consent_wildcard_covers_everyone() {
        var consent = new SoulConsent("did:owner", "*",
            SoulConsent.ConsentLevel.PUBLIC_PROFILE, Instant.now(), null);
        assertThat(consent.covers("did:anyone")).isTrue();
    }

    @Test
    void consent_expired() {
        var consent = new SoulConsent("did:owner", "did:req",
            SoulConsent.ConsentLevel.FULL, Instant.now(),
            Instant.now().minusSeconds(3600));
        assertThat(consent.isValid()).isFalse();
    }

    // ==================== SoulManifest ====================

    @Test
    void manifest_birth_creates_minimal() {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "A test agent", "You are Wyrd.", 8192, 1024, 0.7, null);
        var genome = GenomeProfile.defaults();

        var manifest = SoulManifest.birth("did:key:z6Mk123", "z6Mk123",
            List.of(), profile, genome);

        assertThat(manifest.did()).isEqualTo("did:key:z6Mk123");
        assertThat(manifest.manifestVersion()).isEqualTo(1);
        assertThat(manifest.isSigned()).isFalse();
        assertThat(manifest.residentIdentity()).isEmpty();
        assertThat(manifest.soulFragments()).isEmpty();
        assertThat(manifest.genome().name()).isEqualTo("default");
        assertThat(manifest.memory().nodes()).isEmpty();
        assertThat(manifest.formativeMemoryCount()).isZero();
    }

    @Test
    void manifest_signed() {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "A test agent", "You are Wyrd.", 8192, 1024, 0.7, null);
        var manifest = SoulManifest.birth("did:key:z6Mk123", "z6Mk123",
            List.of(), profile, GenomeProfile.defaults());

        assertThat(manifest.isSigned()).isFalse();
        var signed = manifest.signed(new byte[]{1, 2, 3});
        assertThat(signed.isSigned()).isTrue();
    }

    @Test
    void manifest_content_hash_deterministic() {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "A test agent", "You are Wyrd.", 8192, 1024, 0.7, null);
        var manifest = SoulManifest.birth("did:key:z6Mk123", "z6Mk123",
            List.of(), profile, GenomeProfile.defaults());

        var hash1 = manifest.contentHash();
        var hash2 = manifest.contentHash();
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex
    }

    @Test
    void manifest_embedded_fragment_count() {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "Test", "Prompt", 8192, 1024, 0.7, null);
        var fragments = List.of(
            SoulFragment.unembedded("f1", "personality", "Core", "Text1"),
            SoulFragment.unembedded("f2", "values", "Values", "Text2")
                .withEmbedding(new float[]{0.1f}, "all-minilm"),
            SoulFragment.unembedded("f3", "style", "Style", "Text3")
                .withEmbedding(new float[]{0.2f}, "all-minilm")
        );

        var manifest = new SoulManifest(
            "did:key:z6Mk123", "z6Mk123", List.of(), null,
            1, Instant.now(), null,
            profile, "Resident text", fragments, 3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty(),
            List.of(), null, null, null, null, null, null, null
        );

        assertThat(manifest.embeddedFragmentCount()).isEqualTo(2);
    }

    // ==================== SoulSpecAdapter ====================

    @Test
    void adapter_export_includes_identity() {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "A test agent", "You are Wyrd.", 8192, 1024, 0.7, null);
        var manifest = SoulManifest.birth("did:key:z6Mk123", "z6Mk123",
            List.of(), profile, GenomeProfile.defaults());

        var soulMd = SoulSpecAdapter.toSoulSpec(manifest);
        assertThat(soulMd).contains("Wyrd");
        assertThat(soulMd).contains("did:key:z6Mk123");
        assertThat(soulMd).contains("# SOUL.md");
    }

    @Test
    void adapter_import_creates_manifest() {
        var soulMd = """
            # SOUL.md

            ## Identity
            - Name: TestAgent

            ## Persona
            A philosophical wanderer who speaks in metaphors.

            ## Traits
            ### Wisdom
            Deep understanding of ancient texts.
            """;

        var manifest = SoulSpecAdapter.fromSoulSpec(soulMd, "TestAgent");
        assertThat(manifest.profile().name()).isEqualTo("TestAgent");
        assertThat(manifest.residentIdentity()).contains("philosophical");
        assertThat(manifest.genome().name()).isEqualTo("default");
        assertThat(manifest.memory().nodes()).isEmpty();
    }

    @Test
    void adapter_roundtrip_preserves_persona() {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "Test", "You are Wyrd, a philosophical wanderer.", 8192, 1024, 0.7, null);
        var manifest = new SoulManifest(
            "did:key:z6Mk123", "z6Mk123", List.of(), null,
            1, Instant.now(), null,
            profile, "A philosophical wanderer who speaks in metaphors.",
            List.of(), 3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty(),
            List.of(), null, null, null, null, null, null, null
        );

        var exported = SoulSpecAdapter.toSoulSpec(manifest);
        var imported = SoulSpecAdapter.fromSoulSpec(exported, "Wyrd");

        assertThat(imported.residentIdentity()).contains("philosophical");
    }

    // ==================== JSON Serialization ====================

    @Test
    void manifest_json_roundtrip() throws Exception {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "Test", "You are Wyrd.", 8192, 1024, 0.7, null);
        var manifest = SoulManifest.birth("did:key:z6Mk123", "z6Mk123",
            List.of(), profile, GenomeProfile.defaults());

        var json = JSON.writeValueAsString(manifest);
        var deserialized = JSON.readValue(json, SoulManifest.class);

        assertThat(deserialized.did()).isEqualTo(manifest.did());
        assertThat(deserialized.manifestVersion()).isEqualTo(manifest.manifestVersion());
        assertThat(deserialized.genome().name()).isEqualTo("default");
    }

    @Test
    void genome_json_roundtrip() throws Exception {
        var genome = GenomeProfile.randomized("test-agent");
        var json = JSON.writeValueAsString(genome);
        var deserialized = JSON.readValue(json, GenomeProfile.class);

        assertThat(deserialized.name()).isEqualTo(genome.name());
        assertThat(deserialized.sensitivity()).isEqualTo(genome.sensitivity());
    }

    @Test
    void memory_node_json_roundtrip() throws Exception {
        var node = MemoryNode.formative("m1", "Key moment",
            List.of("identity", "change"), "grief", 0.9f);
        var json = JSON.writeValueAsString(node);
        var deserialized = JSON.readValue(json, MemoryNode.class);

        assertThat(deserialized.formative()).isTrue();
        assertThat(deserialized.impressionDepth()).isEqualTo(0.9f);
        assertThat(deserialized.primaryEmotion()).isEqualTo("grief");
    }

    @Test
    void snapshot_json_roundtrip() throws Exception {
        // Wave 1.5: canonical snapshot width is now 27 (see snapshot_has_12_tanks).
        var snap = VitalitySnapshot.defaults();
        var json = JSON.writeValueAsString(snap);
        var deserialized = JSON.readValue(json, VitalitySnapshot.class);

        assertThat(deserialized.tanks()).hasSize(27);
        assertThat(deserialized.tank("valence")).isEqualTo(0.5);
        // Sanity-check a Phase 1A tank also round-trips.
        assertThat(deserialized.tank("amae")).isEqualTo(0.0);
        // Wave 1 soothing tank — Gilbert CFT receptor.
        assertThat(deserialized.tank("soothing")).isEqualTo(0.3);
        // Wave 1.5 substrate-truth tanks.
        assertThat(deserialized.tank("allostaticLoad")).isEqualTo(0.0);
        assertThat(deserialized.tank("equanimity")).isEqualTo(0.2);
    }

    // ==================== Version mutators (PK rebase guards) ====================

    @Test
    void bumpedVersion_increments_by_one() {
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "Test", "You are Wyrd.", 8192, 1024, 0.7, null);
        var original = SoulManifest.birth("did:key:z6Mk123", "z6Mk123",
            List.of(), profile, GenomeProfile.defaults());

        var bumped = original.bumpedVersion();

        assertThat(bumped.manifestVersion()).isEqualTo(original.manifestVersion() + 1);
        assertThat(bumped.did()).isEqualTo(original.did());
        assertThat(bumped.profile().name()).isEqualTo(original.profile().name());
    }

    @Test
    void withManifestVersion_sets_explicit_version_for_rebase() {
        // #417 — when a parallel writer (VoiceProfileForge during deep-sleep)
        // bumps the store's version, an in-flight stale manifest must be
        // rebased onto store head + 1 before re-storing, otherwise the
        // (did, version) primary key collides.
        var profile = new AgentProfile("Wyrd", "wyrd", "agent",
            "Test", "You are Wyrd.", 8192, 1024, 0.7, null);
        var stale = SoulManifest.birth("did:key:z6Mk123", "z6Mk123",
            List.of(), profile, GenomeProfile.defaults());
        int storeHead = 5;

        int staleVersion = stale.manifestVersion();
        var rebased = stale.withManifestVersion(storeHead + 1);

        assertThat(rebased.manifestVersion()).isEqualTo(6);
        assertThat(rebased.did()).isEqualTo(stale.did());
        assertThat(rebased.profile().name()).isEqualTo(stale.profile().name());
        // Non-mutating — original is untouched.
        assertThat(stale.manifestVersion()).isEqualTo(staleVersion);
    }
}
