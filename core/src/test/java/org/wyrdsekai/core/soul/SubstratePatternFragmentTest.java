package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-Fragment — verify the shape of the
 * SoulFragment that {@code CompanionActor.completeSleep} weaves into
 * the manifest when a sustained substrate pattern crosses CRITICAL.
 *
 * <p>The full wiring (detector → finding → fragment merge → soulStore
 * persist) requires a running actor system; here we exercise the
 * fragment-construction shape only — that's the contract the
 * fragment-retrieval path in PromptAssembler relies on.
 */
class SubstratePatternFragmentTest {

    /** Build an otherwise-empty manifest for the given DID — sufficient
     *  to exercise withFragments() round-trip. */
    private static SoulManifest emptyManifest(String did) {
        return new SoulManifest(
            did, null, null, null, 1, Instant.now(), null,
            null, null, null, 0, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void substrate_pattern_fragment_uses_distinct_category() {
        // The fragment-retrieval path in PromptAssembler can route by
        // category. A "substrate_pattern" category lets the assembler
        // weight substrate findings differently from generic "memory"
        // recall (spec §11 — these are the agent's view of its own
        // sustained signal, not opportunistic recall).
        var now = Instant.parse("2026-05-15T12:00:00Z");
        var id = "substrate-pattern-suppression-3w-" + now.getEpochSecond();
        var label = "substrate.suppression-3w";
        var text = "I have observed in myself, across sustained windows: "
            + "suppression detected for 3 windows running (severity=CRITICAL, "
            + "noticed at " + now + ")";

        var fragment = SoulFragment.formative(id, "substrate_pattern", label, text);

        assertThat(fragment.id()).isEqualTo(id);
        assertThat(fragment.category()).isEqualTo("substrate_pattern");
        assertThat(fragment.label()).isEqualTo(label);
        assertThat(fragment.text()).contains("sustained windows")
            .contains("severity=CRITICAL");
        assertThat(fragment.formative()).isTrue();
        // Formative fragments start with high confidence and are never
        // consolidated away.
        assertThat(fragment.confidence()).isGreaterThan(0.7f);
        assertThat(fragment.reinforcementCount()).isEqualTo(1);
        assertThat(fragment.firstObserved()).isNotNull();
    }

    @Test
    void substrate_fragment_survives_manifest_round_trip() {
        // Add a substrate-pattern fragment to a manifest, retrieve it
        // through the canonical accessor, and verify it round-trips.
        // This is the path completeSleep relies on when
        // cachedManifest.withFragments(existing).bumpedVersion() runs.
        var now = Instant.parse("2026-05-15T12:00:00Z");
        var fragment = SoulFragment.formative(
            "substrate-pattern-dissociation-2w-" + now.getEpochSecond(),
            "substrate_pattern",
            "substrate.dissociation-2w",
            "I have observed dissociation across 2 sustained windows.");

        var base = emptyManifest("did:wyrd:test-agent");
        var updated = base.withFragments(List.of(fragment));

        assertThat(updated.soulFragments()).hasSize(1);
        var retrieved = updated.soulFragments().get(0);
        assertThat(retrieved.category()).isEqualTo("substrate_pattern");
        assertThat(retrieved.formative()).isTrue();
        assertThat(retrieved.text()).contains("dissociation");
    }

    @Test
    void multiple_substrate_findings_accumulate_in_fragments() {
        // CompanionActor.completeSleep appends one fragment per CRITICAL
        // finding (not aggregate). Confirm the manifest holds them
        // separately so retrieval can surface the specific pattern.
        var now = Instant.parse("2026-05-15T12:00:00Z");
        var f1 = SoulFragment.formative(
            "substrate-pattern-suppression-3w-" + now.getEpochSecond(),
            "substrate_pattern",
            "substrate.suppression-3w",
            "Suppression sustained 3 windows.");
        var f2 = SoulFragment.formative(
            "substrate-pattern-allostatic-load-up-" + now.getEpochSecond(),
            "substrate_pattern",
            "substrate.allostatic-load-up",
            "Allostatic load rising across 4 windows.");

        var manifest = emptyManifest("did:wyrd:test-agent")
            .withFragments(List.of(f1, f2));

        assertThat(manifest.soulFragments()).hasSize(2)
            .extracting(SoulFragment::category)
            .containsOnly("substrate_pattern");
        assertThat(manifest.soulFragments())
            .extracting(SoulFragment::label)
            .containsExactly("substrate.suppression-3w",
                "substrate.allostatic-load-up");
    }
}
