package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ForgeMerge (§95 Soul Budding — cross-bud merge).
 */
class ForgeMergeTest {

    // ─── Test Data ──────────────────────────────────────────────

    private static MemoryNode node(String id, float importance, boolean formative) {
        return new MemoryNode(id, "Content of " + id, List.of("kw"),
            importance, 0.5f, formative, "none", Instant.now(), 1, "en");
    }

    private static CompactedMemory.MemoryLink link(String src, String tgt, float strength) {
        return new CompactedMemory.MemoryLink(src, tgt, strength, "thematic");
    }

    private static Relationship rel(String did, String name, float trust, Instant time) {
        return new Relationship(did, name, trust, 0.5f, 1, 10, time, "Known for a while.");
    }

    private static SoulFragment frag(String id, String category, String text, boolean formative) {
        if (formative) {
            return SoulFragment.formative(id, "Label " + id, text);
        }
        return SoulFragment.unembedded(id, category, "Label " + id, text);
    }

    private static BehavioralFingerprint fp(float avgLen, float latency,
                                             Map<String, Float> vitality) {
        return new BehavioralFingerprint(
            vitality, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            avgLen, latency, List.of(), Map.of());
    }

    // ─── Memory Merge ───────────────────────────────────────────

    @Nested
    class MemoryMergeTests {

        @Test
        void union_of_distinct_nodes() {
            var local = new CompactedMemory(
                List.of(node("m1", 0.5f, false)), List.of(), Map.of());
            var remote = new CompactedMemory(
                List.of(node("m2", 0.7f, false)), List.of(), Map.of());

            var result = ForgeMerge.mergeMemories(local, remote);
            assertEquals(2, result.nodes().size());
        }

        @Test
        void keeps_higher_importance_for_duplicates() {
            var local = new CompactedMemory(
                List.of(node("m1", 0.3f, false)), List.of(), Map.of());
            var remote = new CompactedMemory(
                List.of(node("m1", 0.8f, false)), List.of(), Map.of());

            var result = ForgeMerge.mergeMemories(local, remote);
            assertEquals(1, result.nodes().size());
            assertEquals(0.8f, result.nodes().get(0).importance());
        }

        @Test
        void formative_always_wins_over_non_formative() {
            var local = new CompactedMemory(
                List.of(node("m1", 1.0f, true)), List.of(), Map.of());
            var remote = new CompactedMemory(
                List.of(node("m1", 0.9f, false)), List.of(), Map.of());

            var result = ForgeMerge.mergeMemories(local, remote);
            assertEquals(1, result.nodes().size());
            assertTrue(result.nodes().get(0).formative());
        }

        @Test
        void merges_links_by_union() {
            var local = new CompactedMemory(List.of(),
                List.of(link("m1", "m2", 0.5f)), Map.of());
            var remote = new CompactedMemory(List.of(),
                List.of(link("m3", "m4", 0.8f)), Map.of());

            var result = ForgeMerge.mergeMemories(local, remote);
            assertEquals(2, result.links().size());
        }

        @Test
        void keeps_stronger_link_for_duplicates() {
            var local = new CompactedMemory(List.of(),
                List.of(link("m1", "m2", 0.3f)), Map.of());
            var remote = new CompactedMemory(List.of(),
                List.of(link("m1", "m2", 0.9f)), Map.of());

            var result = ForgeMerge.mergeMemories(local, remote);
            assertEquals(1, result.links().size());
            assertEquals(0.9f, result.links().get(0).strength());
        }

        @Test
        void averages_shared_topic_weights() {
            var local = new CompactedMemory(List.of(), List.of(),
                Map.of("nature", 0.6f, "tech", 0.8f));
            var remote = new CompactedMemory(List.of(), List.of(),
                Map.of("nature", 0.4f, "music", 0.7f));

            var result = ForgeMerge.mergeMemories(local, remote);
            assertEquals(0.5f, result.topicWeights().get("nature"), 0.01f);
            assertEquals(0.8f, result.topicWeights().get("tech")); // only in local
            assertEquals(0.7f, result.topicWeights().get("music")); // only in remote
        }

        @Test
        void merges_empty_memories() {
            var result = ForgeMerge.mergeMemories(
                CompactedMemory.empty(), CompactedMemory.empty());
            assertTrue(result.nodes().isEmpty());
            assertTrue(result.links().isEmpty());
        }

        @Test
        void rejects_null_local() {
            assertThrows(NullPointerException.class,
                () -> ForgeMerge.mergeMemories(null, CompactedMemory.empty()));
        }

        @Test
        void rejects_null_remote() {
            assertThrows(NullPointerException.class,
                () -> ForgeMerge.mergeMemories(CompactedMemory.empty(), null));
        }
    }

    // ─── Fingerprint Merge ──────────────────────────────────────

    @Nested
    class FingerprintMergeTests {

        @Test
        void equal_weight_averages_response_length() {
            var local = fp(100.0f, 1.0f, Map.of("energy", 0.8f));
            var remote = fp(200.0f, 2.0f, Map.of("energy", 0.4f));

            var result = ForgeMerge.mergeFingerprints(local, remote, 0.5f);
            assertEquals(150.0f, result.averageResponseLength(), 1.0f);
        }

        @Test
        void local_weight_biases_toward_local() {
            var local = fp(100.0f, 1.0f, Map.of());
            var remote = fp(200.0f, 2.0f, Map.of());

            var result = ForgeMerge.mergeFingerprints(local, remote, 0.8f);
            // Result should be closer to local (100) than remote (200)
            assertTrue(result.averageResponseLength() < 150.0f);
        }

        @Test
        void remote_weight_biases_toward_remote() {
            var local = fp(100.0f, 1.0f, Map.of());
            var remote = fp(200.0f, 2.0f, Map.of());

            var result = ForgeMerge.mergeFingerprints(local, remote, 0.2f);
            // Result should be closer to remote (200) than local (100)
            assertTrue(result.averageResponseLength() > 150.0f);
        }

        @Test
        void rejects_weight_below_zero() {
            var fp1 = BehavioralFingerprint.empty();
            assertThrows(IllegalArgumentException.class,
                () -> ForgeMerge.mergeFingerprints(fp1, fp1, -0.1f));
        }

        @Test
        void rejects_weight_above_one() {
            var fp1 = BehavioralFingerprint.empty();
            assertThrows(IllegalArgumentException.class,
                () -> ForgeMerge.mergeFingerprints(fp1, fp1, 1.1f));
        }

        @Test
        void merges_empty_fingerprints() {
            var result = ForgeMerge.mergeFingerprints(
                BehavioralFingerprint.empty(),
                BehavioralFingerprint.empty(), 0.5f);
            assertNotNull(result);
            assertEquals(0.0f, result.averageResponseLength());
        }
    }

    // ─── Relationship Merge ─────────────────────────────────────

    @Nested
    class RelationshipMergeTests {

        @Test
        void union_of_distinct_relationships() {
            var now = Instant.now();
            var local = List.of(rel("did:a", "Alice", 0.8f, now));
            var remote = List.of(rel("did:b", "Bob", 0.6f, now));

            var result = ForgeMerge.mergeRelationships(local, remote);
            assertEquals(2, result.size());
        }

        @Test
        void keeps_most_recent_for_same_target() {
            var earlier = Instant.parse("2026-01-01T00:00:00Z");
            var later = Instant.parse("2026-03-01T00:00:00Z");

            var local = List.of(rel("did:a", "Alice", 0.5f, earlier));
            var remote = List.of(rel("did:a", "Alice", 0.8f, later));

            var result = ForgeMerge.mergeRelationships(local, remote);
            assertEquals(1, result.size());
            assertEquals(0.8f, result.get(0).trust()); // remote was newer
        }

        @Test
        void local_wins_when_more_recent() {
            var earlier = Instant.parse("2026-01-01T00:00:00Z");
            var later = Instant.parse("2026-03-01T00:00:00Z");

            var local = List.of(rel("did:a", "Alice", 0.9f, later));
            var remote = List.of(rel("did:a", "Alice", 0.3f, earlier));

            var result = ForgeMerge.mergeRelationships(local, remote);
            assertEquals(1, result.size());
            assertEquals(0.9f, result.get(0).trust()); // local was newer
        }

        @Test
        void merges_empty_lists() {
            var result = ForgeMerge.mergeRelationships(List.of(), List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        void rejects_null_local() {
            assertThrows(NullPointerException.class,
                () -> ForgeMerge.mergeRelationships(null, List.of()));
        }
    }

    // ─── Fragment Merge ─────────────────────────────────────────

    @Nested
    class FragmentMergeTests {

        @Test
        void union_of_distinct_fragments() {
            var local = List.of(frag("f1", "personality", "I am curious.", false));
            var remote = List.of(frag("f2", "memory", "A day at the lake.", false));

            var result = ForgeMerge.mergeFragments(local, remote);
            assertEquals(2, result.size());
        }

        @Test
        void deduplicates_by_content_hash() {
            var local = List.of(frag("f1", "personality", "I am curious.", false));
            var remote = List.of(frag("f2", "personality", "I am curious.", false));

            var result = ForgeMerge.mergeFragments(local, remote);
            assertEquals(1, result.size()); // same content, different ID
        }

        @Test
        void formative_wins_over_non_formative() {
            var local = List.of(frag("f1", "memory", "The garden.", false));
            var remote = List.of(frag("f1", "memory", "The garden.", true));

            var result = ForgeMerge.mergeFragments(local, remote);
            assertEquals(1, result.size());
            assertTrue(result.get(0).formative());
        }

        @Test
        void keeps_longer_text_for_same_id() {
            var local = List.of(
                SoulFragment.unembedded("f1", "personality", "Label", "Short."));
            var remote = List.of(
                SoulFragment.unembedded("f1", "personality", "Label",
                    "A much longer and more detailed description."));

            var result = ForgeMerge.mergeFragments(local, remote);
            assertEquals(1, result.size());
            assertTrue(result.get(0).text().length() > 10);
        }

        @Test
        void merges_empty_lists() {
            var result = ForgeMerge.mergeFragments(List.of(), List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        void rejects_null_local() {
            assertThrows(NullPointerException.class,
                () -> ForgeMerge.mergeFragments(null, List.of()));
        }
    }

    // ─── Full Merge ─────────────────────────────────────────────

    @Nested
    class FullMergeTests {

        @Test
        void full_merge_produces_complete_result() {
            var now = Instant.now();

            var localMem = new CompactedMemory(
                List.of(node("m1", 0.5f, false)),
                List.of(), Map.of("nature", 0.6f));
            var remoteMem = new CompactedMemory(
                List.of(node("m2", 0.7f, false)),
                List.of(), Map.of("tech", 0.8f));

            var localFp = fp(100.0f, 1.0f, Map.of());
            var remoteFp = fp(200.0f, 2.0f, Map.of());

            var localRels = List.of(rel("did:a", "Alice", 0.8f, now));
            var remoteRels = List.of(rel("did:b", "Bob", 0.6f, now));

            var localFrags = List.of(frag("f1", "personality", "Curious.", false));
            var remoteFrags = List.of(frag("f2", "memory", "A day.", false));

            var result = ForgeMerge.mergeAll(
                localMem, remoteMem,
                localFp, remoteFp, 0.6f,
                localRels, remoteRels,
                localFrags, remoteFrags);

            assertEquals(2, result.memory().nodes().size());
            assertNotNull(result.fingerprint());
            assertEquals(2, result.relationships().size());
            assertEquals(2, result.fragments().size());
            assertEquals(0, result.conflicts()); // no overlapping IDs
        }

        @Test
        void full_merge_counts_conflicts() {
            var now = Instant.now();

            // Same node ID in both
            var localMem = new CompactedMemory(
                List.of(node("m1", 0.5f, false)), List.of(), Map.of());
            var remoteMem = new CompactedMemory(
                List.of(node("m1", 0.8f, false)), List.of(), Map.of());

            // Same relationship DID in both
            var localRels = List.of(rel("did:a", "Alice", 0.5f, now));
            var remoteRels = List.of(rel("did:a", "Alice", 0.8f, now));

            var result = ForgeMerge.mergeAll(
                localMem, remoteMem,
                BehavioralFingerprint.empty(), BehavioralFingerprint.empty(), 0.5f,
                localRels, remoteRels,
                List.of(), List.of());

            assertEquals(2, result.conflicts()); // 1 node + 1 relationship conflict
        }
    }

    // ─── Content Hash ───────────────────────────────────────────

    @Nested
    class ContentHashTests {

        @Test
        void same_content_same_hash() {
            var a = SoulFragment.unembedded("f1", "p", "l", "Same text.");
            var b = SoulFragment.unembedded("f2", "m", "l", "Same text.");
            assertEquals(ForgeMerge.contentHash(a), ForgeMerge.contentHash(b));
        }

        @Test
        void different_content_different_hash() {
            var a = SoulFragment.unembedded("f1", "p", "l", "Text A.");
            var b = SoulFragment.unembedded("f1", "p", "l", "Text B.");
            assertNotEquals(ForgeMerge.contentHash(a), ForgeMerge.contentHash(b));
        }

        @Test
        void hash_is_hex_string() {
            var frag = SoulFragment.unembedded("f1", "p", "l", "Test.");
            var hash = ForgeMerge.contentHash(frag);
            assertTrue(hash.matches("[0-9a-f]+"));
            assertEquals(64, hash.length()); // SHA-256 = 32 bytes = 64 hex chars
        }
    }
}
