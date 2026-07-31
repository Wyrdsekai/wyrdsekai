package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PackIngesterTest {

    @Test
    void pack_slug_normalizes_topic() {
        assertThat(PackIngester.packSlug("Roman Empire (early)"))
            .isEqualTo("roman-empire-early");
        assertThat(PackIngester.packSlug("Homomorphic Encryption: FHE"))
            .isEqualTo("homomorphic-encryption-fhe");
        assertThat(PackIngester.packSlug("   spaced   text   "))
            .isEqualTo("spaced-text");
        assertThat(PackIngester.packSlug(""))
            .isEqualTo("pack");
        assertThat(PackIngester.packSlug(null))
            .isEqualTo("pack");
    }

    @Test
    void chunk_text_splits_on_blank_lines_with_soft_target() {
        var input = "Paragraph one is short.\n\n" +
            "Paragraph two adds a bit more text.\n\n" +
            "Paragraph three.";
        var chunks = PackIngester.chunkText(input);
        // All three paragraphs fit comfortably under 700 chars → one merged chunk.
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst()).contains("Paragraph one")
            .contains("Paragraph two").contains("Paragraph three");
    }

    @Test
    void chunk_text_breaks_when_target_exceeded() {
        var big = "x".repeat(500);
        var input = big + "\n\n" + big + "\n\n" + big;
        var chunks = PackIngester.chunkText(input);
        // Two 500-char paragraphs concat to 1002 > 700, so each chunk holds one.
        assertThat(chunks).hasSize(3);
        assertThat(chunks).allMatch(c -> c.length() >= 500);
    }

    @Test
    void chunk_text_handles_empty_and_null() {
        assertThat(PackIngester.chunkText(null)).isEmpty();
        assertThat(PackIngester.chunkText("")).isEmpty();
        assertThat(PackIngester.chunkText("   ")).isEmpty();
    }

    @Test
    void ingest_skips_non_approved_proposals() {
        var pending = ProposedPack.of("topic", "summary",
            Provenance.TrustTier.BLOG, "test", "did:key:test");
        // Use null store — ingest should bail on status check before touching the store.
        var ingester = new PackIngester(new WyrdLuceneStore(
            Path.of(System.getProperty("java.io.tmpdir"),
                "wyrdsekai-pack-ingester-test-" + System.nanoTime()), 384));
        var result = ingester.ingest(pending);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not APPROVED");
        assertThat(result.chunksIndexed()).isZero();
    }

    @Test
    void ingest_no_sources_returns_zero() {
        var approved = ProposedPack.of("topic", "summary",
            Provenance.TrustTier.PAPER, "test", "did:key:test");
        // Paper auto-approves on `propose()` but `of()` keeps PENDING. Force APPROVED for the test.
        approved = approved.approve("auto:high-tier");
        var ingester = new PackIngester(new WyrdLuceneStore(
            Path.of(System.getProperty("java.io.tmpdir"),
                "wyrdsekai-pack-ingester-test-" + System.nanoTime()), 384));
        var result = ingester.ingest(approved);
        assertThat(result.chunksIndexed()).isZero();
        assertThat(result.error()).contains("no sources");
    }
}
