package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — Forge ingester output: fragments, fingerprint
 * delta, corpus entries. Retired-form exclusion (§12.3) and success-ratio
 * weighting are the load-bearing invariants.
 */
class FamiliarForgeIngesterTest {

    private static final String DID = "did:wyrd:zA:wyrd";

    private ThoughtForm form(String name, long summons, long successes) {
        var base = ThoughtForm.author(DID, name, "Do " + name + " work.",
            Set.of(), "Return something useful.");
        // Replay successful/failed outcomes to match requested ratios
        for (long i = 0; i < summons; i++) base = base.incrementSummon();
        for (long i = 0; i < successes; i++) base = base.recordSuccess();
        for (long i = successes; i < summons; i++) base = base.recordFailure();
        return base;
    }

    private BunshinReport successfulBunshin(String task, String summary) {
        return new BunshinReport(UUID.randomUUID().toString(),
            DID, task, BunshinReport.Outcome.SUCCESS, summary,
            List.of(), List.of(), Tanks.defaults(), 3,
            Instant.now(), Instant.now(), Optional.empty());
    }

    // ── empty input ─────────────────────────────────────────────────────────

    @Test
    void empty_batch_returns_empty_result() {
        var batch = new FamiliarForgeIngester.Batch(DID, List.of(), List.of(), List.of(), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        assertTrue(result.isEmpty());
        assertTrue(result.newFragments().isEmpty());
        assertTrue(result.corpusEntries().isEmpty());
    }

    // ── fragments ───────────────────────────────────────────────────────────

    @Test
    void single_form_produces_identity_fragment() {
        var batch = new FamiliarForgeIngester.Batch(DID,
            List.of(form("researcher", 0, 0)),
            List.of(), List.of(), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        assertFalse(result.newFragments().isEmpty());
        assertTrue(result.newFragments().stream()
            .anyMatch(f -> f.label().equals("Form-maker identity")));
    }

    @Test
    void retirement_produces_wisdom_fragment() {
        var batch = new FamiliarForgeIngester.Batch(DID,
            List.of(form("gardener", 0, 0)),
            List.of("oldcoder", "flaky"),
            List.of(), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        assertTrue(result.newFragments().stream()
            .anyMatch(f -> f.label().equals("Retirement wisdom") && f.text().contains("Letting go")));
    }

    @Test
    void failing_forms_produce_honesty_fragment() {
        var batch = new FamiliarForgeIngester.Batch(DID,
            List.of(form("broken", 10, 2)),    // 20% success, above min summons
            List.of(), List.of(), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        assertTrue(result.newFragments().stream()
            .anyMatch(f -> f.label().equals("Honesty about what doesn't work")
                && f.text().contains("broken")));
    }

    @Test
    void named_familiar_fragment_requires_bond_threshold() {
        var unbonded = NamedFamiliar.named("fresh", DID, "form-1", "");     // bond 0.15
        // bump bond via DONE outcomes
        var bonded = NamedFamiliar.named("beloved", DID, "form-1", "");
        for (int i = 0; i < 10; i++) bonded = bonded.withOutcome(Familiar.Status.DONE, 1, null);

        var batch = new FamiliarForgeIngester.Batch(DID, List.of(),
            List.of(), List.of(unbonded, bonded), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        var names = result.newFragments().stream().map(f -> f.label()).toList();
        assertTrue(names.contains("Named familiar: beloved"));
        assertFalse(names.contains("Named familiar: fresh"),
            "bond-charge below threshold should not anchor a fragment");
    }

    @Test
    void bunshin_reports_produce_parallel_self_fragment() {
        var reports = List.of(
            successfulBunshin("task-a", "got it"),
            successfulBunshin("task-b", "got it"),
            successfulBunshin("task-c", "got it"));
        var batch = new FamiliarForgeIngester.Batch(DID, List.of(), List.of(), List.of(), reports);
        var result = FamiliarForgeIngester.ingest(batch);
        assertTrue(result.newFragments().stream()
            .anyMatch(f -> f.label().equals("Parallel self habit")));
    }

    @Test
    void bunshin_seeds_become_fragments() {
        var seed = new BunshinReport.FragmentSeed("insight",
            "Library packs beat web search for historical documents.",
            0.7, Optional.empty());
        var report = new BunshinReport(UUID.randomUUID().toString(),
            DID, "research histories", BunshinReport.Outcome.SUCCESS, "found it",
            List.of(seed), List.of(), Tanks.defaults(), 2,
            Instant.now(), Instant.now(), Optional.empty());
        var batch = new FamiliarForgeIngester.Batch(DID, List.of(), List.of(), List.of(),
            List.of(report));
        var result = FamiliarForgeIngester.ingest(batch);
        assertTrue(result.newFragments().stream()
            .anyMatch(f -> f.text().contains("Library packs beat web search")));
    }

    // ── corpus entries (§12.2 / §12.3) ──────────────────────────────────────

    @Test
    void corpus_excludes_retired_forms() {
        var batch = new FamiliarForgeIngester.Batch(DID,
            List.of(form("researcher", 10, 9), form("oldcoder", 10, 9)),
            List.of("oldcoder"),
            List.of(), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        assertTrue(result.corpusEntries().stream().anyMatch(e -> e.contains("researcher")));
        assertFalse(result.corpusEntries().stream().anyMatch(e -> e.contains("oldcoder")),
            "retired forms must be excluded from training corpus (§12.3)");
    }

    @Test
    void corpus_excludes_low_success_ratio_forms() {
        var batch = new FamiliarForgeIngester.Batch(DID,
            List.of(form("winner", 10, 9), form("loser", 10, 1)),
            List.of(), List.of(), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        assertTrue(result.corpusEntries().stream().anyMatch(e -> e.contains("winner")));
        assertFalse(result.corpusEntries().stream().anyMatch(e -> e.contains("loser")),
            "forms below MIN_SUCCESS_RATIO must not seed corpus");
    }

    @Test
    void corpus_excludes_forms_below_min_summons() {
        var batch = new FamiliarForgeIngester.Batch(DID,
            List.of(form("fresh", 1, 1)),   // only 1 summon
            List.of(), List.of(), List.of());
        var result = FamiliarForgeIngester.ingest(batch);
        assertFalse(result.corpusEntries().stream().anyMatch(e -> e.contains("fresh")));
    }

    @Test
    void corpus_includes_successful_bunshin_only() {
        var good = successfulBunshin("good task", "got it done");
        var bad = new BunshinReport(UUID.randomUUID().toString(),
            DID, "bad task", BunshinReport.Outcome.FAILURE, "timed out",
            List.of(), List.of(), Tanks.defaults(), 0,
            Instant.now(), Instant.now(), Optional.empty());
        var batch = new FamiliarForgeIngester.Batch(DID, List.of(), List.of(), List.of(),
            List.of(good, bad));
        var result = FamiliarForgeIngester.ingest(batch);
        assertTrue(result.corpusEntries().stream().anyMatch(e -> e.contains("good task")));
        assertFalse(result.corpusEntries().stream().anyMatch(e -> e.contains("bad task")));
    }

    // ── fingerprint delta ───────────────────────────────────────────────────

    @Test
    void fingerprint_delta_tracks_form_making_counts() {
        var batch = new FamiliarForgeIngester.Batch(DID,
            List.of(form("a", 5, 4), form("b", 5, 4), form("c", 5, 4)),
            List.of(), List.of(), List.of(
                successfulBunshin("split task", "done")));
        var result = FamiliarForgeIngester.ingest(batch);
        var fp = result.fingerprintDelta();

        assertEquals(3.0f, fp.actionDistribution().get("shape_form"), 1e-9);
        assertEquals(15.0f, fp.actionDistribution().get("summon_familiar"), 1e-9);
        assertEquals(1.0f, fp.actionDistribution().get("dispatch_bunshin"), 1e-9);
        assertNotNull(fp.topicAffinities().get("form-making"));
        assertTrue(fp.stylisticMarkers().stream()
            .anyMatch(m -> m.contains("bunshin")));
    }
}
