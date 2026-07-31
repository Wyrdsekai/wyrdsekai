package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for storage primitives —
 * {@link ReadingLog}, {@link ArrivalTable}, and the {@link Provenance}
 * extension to {@link KnowledgeChunk}. Covers the schema piece (#462)
 * + the proposal substrate (#463). Discovery + ingest wiring is owed.
 */
class LibraryAcquisitionStoresTest {

    @Test
    void chunk_without_provenance_defaults_to_unknown_tier() {
        var chunk = KnowledgeChunk.text("simple-wikipedia:0", "simple-wikipedia",
            "Hello", "world", "Wikipedia");
        assertThat(chunk.trustTier()).isEqualTo(Provenance.TrustTier.UNKNOWN);
        assertThat(chunk.provenance()).isNull();
    }

    @Test
    void chunk_with_paper_provenance_is_auto_approve_eligible() {
        var prov = new Provenance(
            new Provenance.Source("arxiv", "arXiv:2103.00111",
                "https://arxiv.org/abs/2103.00111", "Some Paper",
                List.of("Author"), 2021),
            Provenance.TrustTier.PAPER, "cc-by",
            Instant.now(), "did:key:wyrd",
            "did:key:operator", Instant.now(),
            "bunshin-dispatched-by-wyrd", null);
        var chunk = KnowledgeChunk.full("crypto-homomorphic:0", "crypto-homomorphic",
            "FHE basics", "homomorphic encryption...", "arxiv", "https://arxiv.org/abs/2103.00111",
            "cc-by", new String[]{"crypto"}, null, prov);
        assertThat(chunk.trustTier()).isEqualTo(Provenance.TrustTier.PAPER);
        assertThat(prov.trustTier().autoApproveEligible()).isTrue();
    }

    @Test
    void reading_log_records_local_and_misses_and_persists(@TempDir Path tmp) {
        var rl = new ReadingLog(tmp);
        rl.recordLocal("homomorphic encryption", "did:key:wyrd", 5, 0.92, "crypto");
        rl.recordWebFallback("rust async runtime", "did:key:wyrd", 0);
        rl.recordMiss("zone consensus protocols", "did:key:wyrd");
        assertThat(rl.size()).isEqualTo(3);

        var reloaded = new ReadingLog(tmp);
        assertThat(reloaded.size()).isEqualTo(3);

        var recent = reloaded.recent(5);
        assertThat(recent).hasSize(3);
        assertThat(recent.getFirst().query()).isEqualTo("zone consensus protocols");
        assertThat(recent.getFirst().fallbackKind()).isEqualTo("none");
    }

    @Test
    void reading_log_top_repeated_terms_finds_recurring_topics(@TempDir Path tmp) {
        var rl = new ReadingLog(tmp);
        for (int i = 0; i < 4; i++) {
            rl.recordWebFallback("how does homomorphic encryption work?", "did:key:w", 0);
        }
        rl.recordWebFallback("rust ownership rules", "did:key:w", 0);

        var top = rl.topRepeatedTerms(20, 3);
        assertThat(top).containsKey("homomorphic");
        assertThat(top.get("homomorphic")).isGreaterThanOrEqualTo(3);
        assertThat(top).doesNotContainKey("rust"); // below minCount
    }

    @Test
    void arrival_table_auto_approves_paper_proposals(@TempDir Path tmp) {
        var at = new ArrivalTable(tmp);
        var paper = ProposedPack.of("Roman empire", "Pack on Roman history",
            Provenance.TrustTier.PAPER, "explicit_request", "did:key:operator");
        var stored = at.propose(paper);

        assertThat(stored.status()).isEqualTo(ProposedPack.Status.APPROVED);
        assertThat(at.pending()).isEmpty();
        assertThat(at.approved()).hasSize(1);
    }

    @Test
    void arrival_table_holds_blog_proposals_pending_until_steward(@TempDir Path tmp) {
        var at = new ArrivalTable(tmp);
        var blog = ProposedPack.of("Random ML blog", "Personal blog series",
            Provenance.TrustTier.BLOG, "gap_signal", "did:key:wyrd");
        var stored = at.propose(blog);

        assertThat(stored.status()).isEqualTo(ProposedPack.Status.PENDING);
        assertThat(at.pending()).hasSize(1);

        // Steward approves.
        var approved = at.approve(stored.id(), "did:key:operator").orElseThrow();
        assertThat(approved.status()).isEqualTo(ProposedPack.Status.APPROVED);
        assertThat(approved.reviewedBy()).isEqualTo("did:key:operator");
    }

    @Test
    void arrival_table_round_trips_to_disk(@TempDir Path tmp) {
        var at = new ArrivalTable(tmp);
        at.propose(ProposedPack.of("topic1", "s1",
            Provenance.TrustTier.WIKI, "trigger1", "did:key:wyrd"));
        at.propose(ProposedPack.of("topic2", "s2",
            Provenance.TrustTier.BLOG, "trigger2", "did:key:wyrd"));

        var reloaded = new ArrivalTable(tmp);
        assertThat(reloaded.size()).isEqualTo(2);
        assertThat(reloaded.approved()).hasSize(1); // wiki auto-approves
        assertThat(reloaded.pending()).hasSize(1);  // blog stays pending
    }

    /** #476 — async scout writes summary + sources back via {@link ArrivalTable#enrich}. */
    @Test
    void arrival_table_enrich_updates_pending_proposal(@TempDir Path tmp) {
        var at = new ArrivalTable(tmp);
        var stored = at.propose(ProposedPack.of("topic", "baseline summary",
            Provenance.TrustTier.BLOG, "agent_proposal", "did:key:wyrd"));
        assertThat(stored.status()).isEqualTo(ProposedPack.Status.PENDING);

        var enrichedSources = List.of(
            new Provenance.Source("blog", null, "https://example.com/a", "A", List.of(), null),
            new Provenance.Source("blog", null, "https://example.com/b", "B", List.of(), null));
        var updated = at.enrich(stored.id(), "scout: 2 candidate sources for topic. Top: A / B.",
            enrichedSources).orElseThrow();

        assertThat(updated.summary()).contains("scout: 2 candidate");
        assertThat(updated.sources()).hasSize(2);
        // Status preserved — enrichment is a backfill, not a review.
        assertThat(updated.status()).isEqualTo(ProposedPack.Status.PENDING);

        // Persisted across reload.
        var reloaded = new ArrivalTable(tmp).get(stored.id()).orElseThrow();
        assertThat(reloaded.sources()).hasSize(2);
        assertThat(reloaded.summary()).contains("scout:");
    }

    /** #476 — enrichment is a no-op once the proposal has been ingested or rejected. */
    @Test
    void arrival_table_enrich_is_noop_after_terminal_status(@TempDir Path tmp) {
        var at = new ArrivalTable(tmp);
        var stored = at.propose(ProposedPack.of("topic", "summary",
            Provenance.TrustTier.BLOG, "agent_proposal", "did:key:wyrd"));
        at.reject(stored.id(), "did:key:operator", "duplicate");

        var afterEnrich = at.enrich(stored.id(), "should-not-overwrite",
            List.of()).orElseThrow();
        assertThat(afterEnrich.status()).isEqualTo(ProposedPack.Status.REJECTED);
        assertThat(afterEnrich.summary()).isEqualTo("summary");
    }

    /** #476 — withEnrichment on the record itself preserves status and review-state fields. */
    @Test
    void proposed_pack_with_enrichment_preserves_status() {
        var approved = ProposedPack.of("topic", "summary",
            Provenance.TrustTier.WIKI, "agent_proposal", "did:key:wyrd")
            .approve("auto:high-tier");
        var enriched = approved.withEnrichment("new summary", List.of(
            new Provenance.Source("wiki", null, "https://en.wikipedia.org/wiki/Topic",
                "Topic", List.of(), null)));
        assertThat(enriched.status()).isEqualTo(ProposedPack.Status.APPROVED);
        assertThat(enriched.reviewedBy()).isEqualTo("auto:high-tier");
        assertThat(enriched.summary()).isEqualTo("new summary");
        assertThat(enriched.sources()).hasSize(1);
    }
}
