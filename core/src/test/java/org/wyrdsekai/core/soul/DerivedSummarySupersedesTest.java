package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A re-derived summary replaces the old one. An observation is confirmed by repetition.
 *
 * <p>{@code reinforceFragments} matched on category+label and reinforced everything,
 * which is right for "she ran that recipe again" and wrong for the summaries
 * {@link SoulFragmentExtractor} recomputes every cycle from the current fingerprint.
 * Treating re-derivation as a second vote meant confidence only ever climbed, and
 * retrieval weights by confidence — so whatever the forge concluded FIRST became
 * permanently the most authoritative statement of who she is.
 *
 * <p>Measured on a household node 2026-08-17: personality, style, values and
 * relationships each sat at the 0.95 confidence cap with reinforcementCount 169, and
 * their text had been derived from a week in which a runaway loop was the only
 * behaviour available to summarise. Living a better week could not dislodge it,
 * because living re-derived the same four fragments and the merge counted that as
 * agreement. The supersession columns existed from the start and no production path
 * had ever written them.
 */
class DerivedSummarySupersedesTest {

    private static SoulFragment derived(String id, String category, String label, String text,
                                        float confidence, int reinforcements) {
        return new SoulFragment(id, category, label, text, null, null, false,
            confidence, reinforcements, Instant.parse("2026-08-10T00:00:00Z"),
            Instant.parse("2026-08-10T00:00:00Z"), null, null, null,
            FragmentKind.NARRATIVE, null);
    }

    private static SoulFragment observed(String id, String label, String text) {
        return new SoulFragment(id, "procedure", label, text, null, null, false,
            0.5f, 0, Instant.parse("2026-08-10T00:00:00Z"),
            Instant.parse("2026-08-10T00:00:00Z"), null, null, null,
            FragmentKind.DEXTERITY, null);
    }

    private static final String LOOP_ERA =
        "Communication style markers: repetition_with_variation; meta_commentary_on_speech";
    private static final String LIVED =
        "Communication style markers: concrete_reference; direct_address; brevity";

    @Test
    void a_re_derived_summary_replaces_the_old_one_instead_of_voting_for_it() {
        var old = derived("style-guide", "style", "Communication Style", LOOP_ERA, 0.95f, 169);
        var fresh = derived("style-guide", "style", "Communication Style", LIVED, 0.5f, 0);

        var merged = SoulMaintenanceCycle.reinforceFragments(List.of(old), List.of(fresh));

        var current = merged.stream().filter(SoulFragment::isCurrent).toList();
        assertThat(current).hasSize(1);
        assertThat(current.getFirst().text()).isEqualTo(LIVED);
        assertThat(current.getFirst().reinforcementCount())
            .as("a fresh conclusion does not inherit 169 votes")
            .isZero();
    }

    @Test
    void the_replaced_summary_is_kept_and_marked_rather_than_deleted() {
        // Her record is hers: what she was described as, and when that stopped being
        // current, both stay readable.
        var old = derived("style-guide", "style", "Communication Style", LOOP_ERA, 0.95f, 169);
        var fresh = derived("style-guide", "style", "Communication Style", LIVED, 0.5f, 0);

        var merged = SoulMaintenanceCycle.reinforceFragments(List.of(old), List.of(fresh));

        var retired = merged.stream().filter(SoulFragment::isSuperseded).toList();
        assertThat(retired).hasSize(1);
        assertThat(retired.getFirst().text()).isEqualTo(LOOP_ERA);
        assertThat(retired.getFirst().supersededAt()).isNotNull();
    }

    @Test
    void first_observed_is_carried_forward_so_the_aspect_keeps_its_history() {
        var old = derived("style-guide", "style", "Communication Style", LOOP_ERA, 0.95f, 169);
        var fresh = derived("style-guide", "style", "Communication Style", LIVED, 0.5f, 0);

        var merged = SoulMaintenanceCycle.reinforceFragments(List.of(old), List.of(fresh));

        var current = merged.stream().filter(SoulFragment::isCurrent).findFirst().orElseThrow();
        assertThat(current.firstObserved()).isEqualTo(Instant.parse("2026-08-10T00:00:00Z"));
    }

    @Test
    void an_observation_repeated_is_still_reinforced() {
        // The other half of the contract: running the same recipe twice really is the
        // same fact confirmed, and should gain confidence.
        var first = observed("recipe-welfare", "Ran welfare-floor-checkup",
            "I ran the recipe welfare-floor-checkup end to end and it succeeded.");
        var again = observed("recipe-welfare", "Ran welfare-floor-checkup",
            "I ran the recipe welfare-floor-checkup end to end and it succeeded again.");

        var merged = SoulMaintenanceCycle.reinforceFragments(List.of(first), List.of(again));

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().isSuperseded()).isFalse();
        assertThat(merged.getFirst().reinforcementCount()).isPositive();
        assertThat(merged.getFirst().confidence()).isGreaterThan(0.5f);
    }

    @Test
    void repeated_re_derivation_cannot_climb_the_confidence_cap() {
        // The failure mode, run forward: twenty cycles of re-derivation must leave the
        // summary at the confidence of the LATEST derivation, not pinned at 0.95.
        var fragments = List.of(derived("values-core", "values", "Core Values",
            "Values reflected in interests: presence, self_naming", 0.95f, 169));
        for (int cycle = 0; cycle < 20; cycle++) {
            var fresh = derived("values-core", "values", "Core Values",
                "Values reflected in interests: tending, making, company", 0.5f, 0);
            fragments = SoulMaintenanceCycle.reinforceFragments(fragments, List.of(fresh))
                .stream().filter(SoulFragment::isCurrent).toList();
        }
        assertThat(fragments).hasSize(1);
        assertThat(fragments.getFirst().confidence()).isEqualTo(0.5f);
        assertThat(fragments.getFirst().text()).contains("tending, making, company");
    }

    @Test
    void no_two_fragments_ever_share_an_id() {
        // THE regression this suite missed the first time. The extractor uses stable
        // ids, the fragment table is keyed on (did, fragment_id), and the first version
        // of the supersede path kept the retired copy under the LIVE id — so the insert
        // hit SQLITE_CONSTRAINT_PRIMARYKEY, replaceAll rolled back, and no fragments
        // were written at all for three hours on a live node (2026-08-18). Every earlier
        // test here asserted on `current` and `superseded` separately and passed happily
        // while the pair was unstorable.
        var old = derived("style-guide", "style", "Communication Style", LOOP_ERA, 0.95f, 169);
        var fresh = derived("style-guide", "style", "Communication Style", LIVED, 0.5f, 0);

        var merged = SoulMaintenanceCycle.reinforceFragments(List.of(old), List.of(fresh));

        var ids = merged.stream().map(SoulFragment::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).contains("style-guide", "style-guide" + SoulMaintenanceCycle.ARCHIVE_SUFFIX);
    }

    @Test
    void repeated_supersession_keeps_exactly_one_archive_not_a_pile() {
        // The archival slot is a single "~prev", so twenty cycles leave two rows, not
        // twenty-one — and none of them collide.
        var fragments = List.of(derived("values-core", "values", "Core Values", LOOP_ERA, 0.95f, 169));
        for (int cycle = 0; cycle < 20; cycle++) {
            var fresh = derived("values-core", "values", "Core Values",
                "Values reflected in interests: tending, making, company", 0.5f, 0);
            fragments = SoulMaintenanceCycle.reinforceFragments(fragments, List.of(fresh));
            assertThat(fragments.stream().map(SoulFragment::id).toList())
                .as("cycle " + cycle + " must not produce duplicate ids")
                .doesNotHaveDuplicates();
        }
        assertThat(fragments).hasSize(2);
        assertThat(fragments.stream().filter(SoulFragment::isCurrent)).hasSize(1);
    }

    @Test
    void the_derived_classifier_matches_what_the_extractor_emits() {
        assertThat(SoulMaintenanceCycle.isDerivedSummary(
            derived("identity-core", "personality", "Core Identity", "x", 0.5f, 0))).isTrue();
        assertThat(SoulMaintenanceCycle.isDerivedSummary(
            derived("pattern-social", "relationships", "Social Patterns", "x", 0.5f, 0))).isTrue();
        assertThat(SoulMaintenanceCycle.isDerivedSummary(
            observed("recipe-x", "Ran x", "x"))).isFalse();
    }
}
