package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * unit tests for the Forge fragment-kind
 * taxonomy. Covers the enum's parse / default / round-trip contract that
 * SoulFragment + SoulFragmentStore depend on.
 */
class FragmentKindTest {

    @Test void all_kinds_present() {
        // §17.6 declared the first four; adds EPISODIC.
        assertThat(FragmentKind.values())
            .containsExactly(
                FragmentKind.NARRATIVE,
                FragmentKind.DEXTERITY,
                FragmentKind.CONVENTION,
                FragmentKind.STRUCTURAL,
                FragmentKind.EPISODIC);
    }

    @Test void default_is_narrative_per_spec() {
        // §17.6: "Existing rows default to NARRATIVE."
        assertThat(FragmentKind.DEFAULT).isEqualTo(FragmentKind.NARRATIVE);
    }

    @Test void parse_round_trip_canonical_values() {
        for (var k : FragmentKind.values()) {
            assertThat(FragmentKind.parse(k.name())).isEqualTo(k);
        }
    }

    @Test void parse_is_case_insensitive_and_trims() {
        assertThat(FragmentKind.parse("dexterity")).isEqualTo(FragmentKind.DEXTERITY);
        assertThat(FragmentKind.parse("  Convention  ")).isEqualTo(FragmentKind.CONVENTION);
        assertThat(FragmentKind.parse("STRUCTURAL")).isEqualTo(FragmentKind.STRUCTURAL);
    }

    @Test void parse_unknown_falls_back_to_default() {
        // Backward-compat: any pre-§17.6 garbage or null in the column
        // becomes NARRATIVE so existing companion soul behavior is unchanged.
        assertThat(FragmentKind.parse(null)).isEqualTo(FragmentKind.DEFAULT);
        assertThat(FragmentKind.parse("")).isEqualTo(FragmentKind.DEFAULT);
        assertThat(FragmentKind.parse("   ")).isEqualTo(FragmentKind.DEFAULT);
        assertThat(FragmentKind.parse("not-a-real-kind")).isEqualTo(FragmentKind.DEFAULT);
    }
}
