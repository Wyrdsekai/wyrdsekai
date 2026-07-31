package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §85.18.3-4 — Divergence measurement and experience sharing.
 */
class DivergenceAndSharingTest {

    // ── DivergenceReport ──

    @Nested
    class DivergenceReportTests {

        @Test
        void identical_fingerprints_zero_divergence() {
            var fp = new BehavioralFingerprint(
                Map.of("say", 0.6f, "move", 0.2f),
                Map.of(), Map.of(),
                Map.of("say", 0.6f, "move", 0.2f),
                Map.of("philosophy", 0.8f),
                Map.of(), 50f, 0.5f,
                List.of("perhaps", "consider"), Map.of());

            double div = DivergenceReport.behavioralDivergence(fp, fp);
            assertEquals(0.0, div, 0.01);
        }

        @Test
        void different_fingerprints_positive_divergence() {
            var fpA = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.9f, "move", 0.1f),
                Map.of("philosophy", 0.9f),
                Map.of(), 50f, 0.5f,
                List.of("perhaps"), Map.of());

            var fpB = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.1f, "move", 0.9f),
                Map.of("combat", 0.9f),
                Map.of(), 50f, 0.5f,
                List.of("attack"), Map.of());

            double div = DivergenceReport.behavioralDivergence(fpA, fpB);
            assertTrue(div > 0.3, "Distinct fingerprints should have significant divergence");
            assertTrue(div <= 1.0);
        }

        @Test
        void null_fingerprint_max_divergence() {
            var fp = BehavioralFingerprint.empty();
            assertEquals(1.0, DivergenceReport.behavioralDivergence(null, fp));
            assertEquals(1.0, DivergenceReport.behavioralDivergence(fp, null));
        }

        @Test
        void memory_divergence_full_overlap() {
            assertEquals(0.0, DivergenceReport.memoryDivergence(10, 0, 0), 0.01);
        }

        @Test
        void memory_divergence_no_overlap() {
            assertEquals(1.0, DivergenceReport.memoryDivergence(0, 5, 5), 0.01);
        }

        @Test
        void memory_divergence_partial_overlap() {
            double div = DivergenceReport.memoryDivergence(5, 3, 2);
            // 5 shared, 10 total = 0.5 overlap → 0.5 divergence
            assertEquals(0.5, div, 0.01);
        }

        @Test
        void memory_divergence_empty() {
            assertEquals(0.0, DivergenceReport.memoryDivergence(0, 0, 0));
        }

        @Test
        void identity_divergence_identical() {
            assertEquals(0.0, DivergenceReport.identityDivergence(
                "A philosophical companion", "A philosophical companion"));
        }

        @Test
        void identity_divergence_different() {
            double div = DivergenceReport.identityDivergence(
                "A philosophical companion who loves tea",
                "A warrior who fights with honor");
            assertTrue(div > 0.5, "Very different identities should diverge significantly");
        }

        @Test
        void identity_divergence_null() {
            assertEquals(0.0, DivergenceReport.identityDivergence(null, null));
            assertEquals(1.0, DivergenceReport.identityDivergence("text", null));
            assertEquals(1.0, DivergenceReport.identityDivergence(null, "text"));
        }

        @Test
        void composite_measure() {
            var fpA = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.6f),
                Map.of("philosophy", 0.8f),
                Map.of(), 50f, 0.5f, List.of("perhaps"), Map.of());

            var fpB = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.6f),
                Map.of("philosophy", 0.8f),
                Map.of(), 50f, 0.5f, List.of("perhaps"), Map.of());

            var div = DivergenceReport.measure("bud-a", "bud-b",
                fpA, fpB, 10, 0, 0, "same identity", "same identity");

            assertTrue(div.composite() < 0.1, "Similar buds should have low divergence");
            assertTrue(div.aligned());
            assertFalse(div.speciated());
            assertEquals("identical", div.label());
        }

        @Test
        void speciation_detection() {
            var fpA = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.9f),
                Map.of("philosophy", 1.0f),
                Map.of(), 100f, 0.1f, List.of("perhaps", "indeed"), Map.of());

            var fpB = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("attack", 0.9f),
                Map.of("combat", 1.0f),
                Map.of(), 10f, 0.9f, List.of("strike!", "defend!"), Map.of());

            var div = DivergenceReport.measure("bud-a", "bud-b",
                fpA, fpB, 0, 20, 15,
                "A philosopher", "A warrior");

            assertTrue(div.composite() > 0.5, "Completely different buds should show high divergence");
        }

        @Test
        void family_report() {
            var d1 = DivergenceReport.measure("a", "b",
                BehavioralFingerprint.empty(), BehavioralFingerprint.empty(),
                10, 2, 3, "identity", "identity");
            var d2 = DivergenceReport.measure("a", "c",
                BehavioralFingerprint.empty(), BehavioralFingerprint.empty(),
                5, 5, 5, "identity a", "different identity c");

            var report = DivergenceReport.report("family-1", List.of(d1, d2));

            assertEquals("family-1", report.familyId());
            assertEquals(2, report.pairwise().size());
            assertEquals(3, report.budCount());
            assertTrue(report.averageDivergence() > 0);
            assertTrue(report.maxDivergence() >= report.averageDivergence());
        }

        @Test
        void divergence_labels() {
            // Test label boundaries
            assertEquals("identical", new DivergenceReport.BudDivergence(
                "a", "b", 0, 0, 0, 0.05, Instant.now()).label());
            assertEquals("aligned", new DivergenceReport.BudDivergence(
                "a", "b", 0, 0, 0, 0.15, Instant.now()).label());
            assertEquals("diverging", new DivergenceReport.BudDivergence(
                "a", "b", 0, 0, 0, 0.35, Instant.now()).label());
            assertEquals("distinct", new DivergenceReport.BudDivergence(
                "a", "b", 0, 0, 0, 0.55, Instant.now()).label());
            assertEquals("speciated", new DivergenceReport.BudDivergence(
                "a", "b", 0, 0, 0, 0.75, Instant.now()).label());
        }

        @Test
        void map_distance_empty_maps() {
            assertEquals(0.0, DivergenceReport.mapDistance(Map.of(), Map.of()));
            assertEquals(1.0, DivergenceReport.mapDistance(Map.of("a", 1f), Map.of()));
        }

        @Test
        void levenshtein_basic() {
            assertEquals(0, DivergenceReport.levenshtein("abc", "abc"));
            assertEquals(1, DivergenceReport.levenshtein("abc", "abd"));
            assertEquals(3, DivergenceReport.levenshtein("abc", "def"));
            assertEquals(3, DivergenceReport.levenshtein("", "abc"));
        }
    }

    // ── ExperienceSharing ──

    @Nested
    class ExperienceSharingTests {

        private static final String BUD_A = "did:key:z6MkBudA";
        private static final String BUD_B = "did:key:z6MkBudB";

        private SoulItem makeItem(String content, double significance, String category) {
            return SoulItem.create(category, content, content, BUD_A, significance);
        }

        @Test
        void convergence_recommends_missing_items() {
            var sharing = new ExperienceSharing();
            var items = List.of(
                makeItem("Memory 1", 0.8, "memory"),
                makeItem("Memory 2", 0.5, "memory"),
                makeItem("Memory 3", 0.1, "memory") // below threshold
            );

            var rec = sharing.forConvergence(BUD_A, BUD_B, items, Set.of(), 0.5);

            assertEquals(2, rec.itemCount()); // Only items above 0.3 threshold
            assertEquals(ExperienceSharing.SharingMode.CONVERGENCE, rec.mode());
            // First item should be highest significance
            assertEquals(0.8, rec.items().getFirst().significance(), 0.01);
        }

        @Test
        void convergence_excludes_already_present() {
            var sharing = new ExperienceSharing();
            var item1 = makeItem("Memory 1", 0.8, "memory");
            var item2 = makeItem("Memory 2", 0.6, "memory");

            var rec = sharing.forConvergence(BUD_A, BUD_B,
                List.of(item1, item2), Set.of(item1.hash()), 0.5);

            assertEquals(1, rec.itemCount());
            assertEquals(item2.hash(), rec.items().getFirst().hash());
        }

        @Test
        void growth_prioritizes_novel_categories() {
            var sharing = new ExperienceSharing();
            var items = List.of(
                makeItem("Memory in known category", 0.7, "memory"),
                makeItem("Skill in new category", 0.7, "skill")
            );

            // Bud B already has memory category but not skill
            var rec = sharing.forGrowth(BUD_A, BUD_B, items, Set.of(), Set.of("memory"));

            assertEquals(2, rec.itemCount());
            // Skill should be first (new category = higher novelty)
            assertEquals("skill", rec.items().getFirst().category());
        }

        @Test
        void custom_threshold() {
            var sharing = new ExperienceSharing().withSignificanceThreshold(0.7);
            var items = List.of(
                makeItem("High", 0.8, "memory"),
                makeItem("Medium", 0.5, "memory"),
                makeItem("Low", 0.3, "memory")
            );

            var rec = sharing.forConvergence(BUD_A, BUD_B, items, Set.of(), 0.5);
            assertEquals(1, rec.itemCount()); // Only 0.8 passes 0.7 threshold
        }

        @Test
        void max_batch_size_enforced() {
            var sharing = new ExperienceSharing().withMaxBatchSize(2);
            var items = new ArrayList<SoulItem>();
            for (int i = 0; i < 10; i++) {
                items.add(makeItem("Memory " + i, 0.5 + i * 0.05, "memory"));
            }

            var rec = sharing.forConvergence(BUD_A, BUD_B, items, Set.of(), 0.5);
            assertEquals(2, rec.itemCount());
        }

        @Test
        void apply_sharing_to_locker() {
            var sharing = new ExperienceSharing();
            var bud = SoulBud.original(BUD_A, "z6Mk...", "family-1", "locker-1", "node-1", "qwen:7b");
            var budB = bud.createChild(BUD_B, "z6Mk2...", "node-2", "qwen:3b");
            var locker = FamilyLocker.create("family-1", "locker-1", bud);
            locker.authorize(budB);

            var items = List.of(
                makeItem("Shared memory", 0.8, "memory"),
                makeItem("Low significance", 0.2, "memory")
            );
            // Store items in locker as bud A
            for (var item : items) locker.store(item, BUD_A);

            var rec = sharing.forConvergence(BUD_A, BUD_B, items, Set.of(), 0.5);
            var result = sharing.apply(rec, locker, BUD_B, 0.3, 0.5, 0.3);

            assertTrue(result.accepted() > 0 || result.alreadyPresent() > 0);
        }

        @Test
        void bidirectional_convergence() {
            var sharing = new ExperienceSharing();
            var itemsA = List.of(makeItem("A's memory", 0.8, "memory"));
            var itemsB = List.of(makeItem("B's memory", 0.7, "memory"));

            var recs = sharing.bidirectionalConvergence(BUD_A, BUD_B,
                itemsA, itemsB, 0.5);

            assertEquals(2, recs.size());
            assertEquals(BUD_A, recs.get(0).fromBud());
            assertEquals(BUD_B, recs.get(0).toBud());
            assertEquals(BUD_B, recs.get(1).fromBud());
            assertEquals(BUD_A, recs.get(1).toBud());
        }

        @Test
        void empty_items_no_recommendations() {
            var sharing = new ExperienceSharing();
            var rec = sharing.forConvergence(BUD_A, BUD_B, List.of(), Set.of(), 0.5);
            assertEquals(0, rec.itemCount());
        }

        @Test
        void sharing_result_tracks_reduction() {
            var result = new ExperienceSharing.SharingResult(5, 3, 1, 1, 0.6, 0.4);
            assertEquals(0.2, result.reduction(), 0.01);
        }
    }
}
