package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** .A4 — the want synthesizer's pure decisions. */
class GenerativeWantSynthesizerTest {

    private static final String DID = "did:test:a";

    @Test
    void below_threshold_mints_nothing() {
        var w = GenerativeWantSynthesizer.synthesize(
            DID, 0.3, "library.stale-packs", "stale packs", List.of());
        assertThat(w).isEmpty();
    }

    @Test
    void above_threshold_with_gap_mints_an_action_named_want() {
        var w = GenerativeWantSynthesizer.synthesize(
            DID, 0.8, "library.stale-packs", "stale packs", List.of());
        assertThat(w).isPresent();
        assertThat(w.get().text()).isEqualTo("author a recipe to keep my research packs fresh");
        assertThat(w.get().status()).isEqualTo(Want.Status.ACTIVE);
        assertThat(w.get().driveResonance()).contains("generativity");
        assertThat(w.get().agentDid()).isEqualTo(DID);
    }

    @Test
    void no_gap_key_mints_nothing() {
        assertThat(GenerativeWantSynthesizer.synthesize(DID, 0.9, null, null, List.of())).isEmpty();
        assertThat(GenerativeWantSynthesizer.synthesize(DID, 0.9, " ", "x", List.of())).isEmpty();
    }

    @Test
    void dedups_against_an_existing_live_want_for_the_same_gap() {
        String text = "author a recipe to keep my research packs fresh";
        var w = GenerativeWantSynthesizer.synthesize(
            DID, 0.9, "library.stale-packs", "stale packs", List.of(text));
        assertThat(w).isEmpty();
    }

    @Test
    void unknown_gap_key_still_names_an_affordance() {
        var w = GenerativeWantSynthesizer.synthesize(
            DID, 0.9, "weird.gap", "something odd", List.of());
        assertThat(w).isPresent();
        assertThat(w.get().text()).contains("author a recipe or request one");
        assertThat(w.get().text()).contains("something odd");
    }

    // ── oodaCandidate: the ACT-path last-mile (§2.A4) ────────────────────────

    @Test
    void ooda_candidate_present_when_pressure_surfaced_with_gap_and_means() {
        var c = GenerativeWantSynthesizer.oodaCandidate(
            0.7, /*gaps*/2, /*means*/true, /*suppressed*/false,
            "library.stale-packs", "stale packs");
        assertThat(c).isPresent();
        // VISIBLE verb → autonomously dispatchable by enactInteriorityWant.
        assertThat(c.get().verb()).isEqualTo("shape_recipe");
        assertThat(c.get().text()).isEqualTo("author a recipe to keep my research packs fresh");
        assertThat(c.get().weight()).isEqualTo(0.7);   // weight tracks the tank level
    }

    @Test
    void ooda_candidate_empty_below_threshold() {
        assertThat(GenerativeWantSynthesizer.oodaCandidate(
            0.49, 3, true, false, "library.stale-packs", "stale"))
            .isEmpty();
    }

    @Test
    void ooda_candidate_empty_when_suppressed_or_no_means_or_no_gaps() {
        // suppressed (repair mode) → no autonomous act, even with pressure + gaps
        assertThat(GenerativeWantSynthesizer.oodaCandidate(
            0.9, 3, true, /*suppressed*/true, "library.stale-packs", "stale")).isEmpty();
        // no means (not enrolled / no workbench)
        assertThat(GenerativeWantSynthesizer.oodaCandidate(
            0.9, 3, /*means*/false, false, "library.stale-packs", "stale")).isEmpty();
        // no open gaps → honest-pressure guard
        assertThat(GenerativeWantSynthesizer.oodaCandidate(
            0.9, /*gaps*/0, true, false, "library.stale-packs", "stale")).isEmpty();
        // no gap key in hand
        assertThat(GenerativeWantSynthesizer.oodaCandidate(
            0.9, 3, true, false, null, "stale")).isEmpty();
    }

    @Test
    void blank_did_mints_nothing() {
        assertThat(GenerativeWantSynthesizer.synthesize(
            "", 0.9, "library.stale-packs", "x", List.of())).isEmpty();
    }
}
