package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PackProvenanceDefaultsTest {

    @Test
    void infer_known_packs() {
        assertThat(PackProvenanceDefaults.infer("simple-wikipedia"))
            .isEqualTo(Provenance.TrustTier.WIKI);
        assertThat(PackProvenanceDefaults.infer("WIKIPEDIA-Simple"))
            .isEqualTo(Provenance.TrustTier.WIKI);
        assertThat(PackProvenanceDefaults.infer("medquad"))
            .isEqualTo(Provenance.TrustTier.PAPER);
        assertThat(PackProvenanceDefaults.infer("stackexchange"))
            .isEqualTo(Provenance.TrustTier.FORUM);
        assertThat(PackProvenanceDefaults.infer("project-gutenberg"))
            .isEqualTo(Provenance.TrustTier.BOOK);
    }

    @Test
    void infer_loose_substring_match() {
        assertThat(PackProvenanceDefaults.infer("simple-wikipedia-en"))
            .isEqualTo(Provenance.TrustTier.WIKI);
        assertThat(PackProvenanceDefaults.infer("crypto-research-papers"))
            .isEqualTo(Provenance.TrustTier.PAPER);
        assertThat(PackProvenanceDefaults.infer("rust-blog-archive"))
            .isEqualTo(Provenance.TrustTier.BLOG);
    }

    @Test
    void infer_unknown_falls_through() {
        assertThat(PackProvenanceDefaults.infer(""))
            .isEqualTo(Provenance.TrustTier.UNKNOWN);
        assertThat(PackProvenanceDefaults.infer(null))
            .isEqualTo(Provenance.TrustTier.UNKNOWN);
        assertThat(PackProvenanceDefaults.infer("custom-zone-pack-42"))
            .isEqualTo(Provenance.TrustTier.UNKNOWN);
    }

    @Test
    void subjectWithTier_prepends_tier() {
        assertThat(PackProvenanceDefaults.subjectWithTier(
                Provenance.TrustTier.WIKI, "history|geography"))
            .isEqualTo("wiki|history|geography");
        assertThat(PackProvenanceDefaults.subjectWithTier(
                Provenance.TrustTier.PAPER, null))
            .isEqualTo("paper");
        assertThat(PackProvenanceDefaults.subjectWithTier(null, "x"))
            .isEqualTo("unknown|x");
    }

    @Test
    void subjectWithTier_idempotent_when_already_tagged() {
        assertThat(PackProvenanceDefaults.subjectWithTier(
                Provenance.TrustTier.WIKI, "wiki|already|tagged"))
            .isEqualTo("wiki|already|tagged");
        assertThat(PackProvenanceDefaults.subjectWithTier(
                Provenance.TrustTier.PAPER, "paper"))
            .isEqualTo("paper");
    }
}
