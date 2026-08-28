package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DreamWeaverTest {

    @Test
    void weave_with_topics_produces_dream() {
        var fingerprint = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of("say", 0.5f),
            Map.of("exploration", 0.8f, "curiosity", 0.6f),
            Map.of(), 0, 0, List.of(), Map.of());
        var manifest = minimalManifest(fingerprint, List.of(), List.of());

        var dream = DreamWeaver.weave(manifest, null, null);
        assertTrue(dream.isPresent(), "Should produce a dream with topics");
        assertTrue(dream.get().contains("stirs from sleep"), "Dream should have framing");
    }

    @Test
    void weave_with_emotions_produces_dream() {
        var fingerprint = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), 0, 0, List.of(),
            Map.of("curiosity", 0.7f, "wonder", 0.5f));
        var manifest = minimalManifest(fingerprint, List.of(), List.of());

        var dream = DreamWeaver.weave(manifest, null, null);
        assertTrue(dream.isPresent());
    }

    @Test
    void weave_with_new_fragment_produces_dream() {
        var fragment = new SoulFragment("frag-1", "memory", "a realization",
            "I noticed something important today", null, null, false,
            0.5f, 1, Instant.now(), null, null, null, null);
        var fingerprint = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), 0, 0, List.of(), Map.of());
        var manifest = minimalManifest(fingerprint, List.of(fragment), List.of());

        var dream = DreamWeaver.weave(manifest, null, null);
        assertTrue(dream.isPresent());
    }

    @Test
    void weave_with_relationships_produces_dream() {
        var rel = new Relationship("did:key:operator", "Operator", 0.8f, 0.7f, 3, 10,
            Instant.now(), "The steward");
        var fingerprint = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), 0, 0, List.of(), Map.of());
        var manifest = minimalManifest(fingerprint, List.of(), List.of(rel));

        var dream = DreamWeaver.weave(manifest, null, null);
        assertTrue(dream.isPresent());
        assertTrue(dream.get().contains("Operator"), "Dream should mention relationship");
    }

    @Test
    void weave_with_consolidation_produces_dream() {
        var fingerprint = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), 0, 0, List.of(), Map.of());
        var manifest = minimalManifest(fingerprint, List.of(), List.of());

        var before = new CompactedMemory(
            List.of(memNode("n1"), memNode("n2"), memNode("n3")), List.of(), Map.of());
        var after = new CompactedMemory(
            List.of(memNode("n1")), List.of(), Map.of());

        var dream = DreamWeaver.weave(manifest, before, after);
        assertTrue(dream.isPresent());
    }

    @Test
    void weave_null_manifest_returns_empty() {
        var dream = DreamWeaver.weave(null, null, null);
        assertTrue(dream.isEmpty());
    }

    @Test
    void weave_empty_fingerprint_may_return_empty() {
        var fingerprint = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), 0, 0, List.of(), Map.of());
        var manifest = minimalManifest(fingerprint, List.of(), List.of());

        // With no topics, emotions, fragments, or relationships — no dream material
        var dream = DreamWeaver.weave(manifest, null, null);
        assertTrue(dream.isEmpty(), "Empty fingerprint should produce no dream");
    }

    @Test
    void weave_produces_different_dreams() {
        var fingerprint = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of("say", 0.5f),
            Map.of("exploration", 0.8f, "the nexus", 0.6f),
            Map.of(), 0, 0, List.of(),
            Map.of("curiosity", 0.7f));
        var manifest = minimalManifest(fingerprint, List.of(), List.of());

        // Generate several dreams — they should not all be identical
        var dreams = new HashSet<String>();
        for (int i = 0; i < 20; i++) {
            DreamWeaver.weave(manifest, null, null).ifPresent(dreams::add);
        }
        assertTrue(dreams.size() > 1, "Dreams should have variety (got " + dreams.size() + " unique)");
    }

    // --- Helpers ---

    private SoulManifest minimalManifest(BehavioralFingerprint fingerprint,
                                          List<SoulFragment> fragments,
                                          List<Relationship> relationships) {
        return new SoulManifest(
            "did:key:test", null, null, null, 1, Instant.now(), null,
            null, null, fragments, 3, null,
            null, null,
            null, relationships, null, null,
            null, fingerprint, null, null, null, null, null, null, null, null);
    }

    private MemoryNode memNode(String id) {
        return new MemoryNode(id, "something happened", List.of("event"),
            1.0f, 0.5f, false, null, Instant.now(), 1, null);
    }
}
