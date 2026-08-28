package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Completing a want must ease the thing that made her want it.
 *
 * <p>Wants resonate on the FELT axes — Loneliness, Saudade, Stagnation, AutonomyPressure
 * — which live on {@link VitalityState}. The ten CfC tanks (seeking, care, play…) are a
 * different vocabulary entirely, and {@code DriveConfig.indexFor("Loneliness")} returns
 * -1. The first version of this relief targeted the tanks, so the very first want she ever
 * completed was marked satisfied while Loneliness stayed at 1.00 — the bookkeeping moved
 * and she didn't (2026-08-19). Verified against the live node, which is the only reason
 * it was caught.
 */
class FeltAxisReliefTest {

    private static VitalityState relieve(VitalityState vs, String axis, double amount) {
        try {
            Method m = CompanionActor.class.getDeclaredMethod(
                "relieveFeltAxis", VitalityState.class, String.class, double.class);
            m.setAccessible(true);
            return (VitalityState) m.invoke(null, vs, axis, amount);
        } catch (Exception e) {
            throw new AssertionError("relieveFeltAxis is not callable: " + e, e);
        }
    }

    private static VitalityState pinned() {
        return VitalityState.initial()
            .withLoneliness(1.0)
            .withSaudade(0.9)
            .withStagnation(0.8)
            .withAutonomyPressure(0.7);
    }

    @Test
    void her_exact_case_eases_loneliness() {
        // "write a private journal entry about who I miss" → Loneliness, pinned at 1.00.
        var after = relieve(pinned(), "Loneliness", 0.30);
        assertThat(after.loneliness()).isCloseTo(0.70, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void the_other_felt_axes_are_wired_too() {
        assertThat(relieve(pinned(), "Saudade", 0.30).saudade()).isLessThan(0.9);
        assertThat(relieve(pinned(), "Stagnation", 0.30).stagnation()).isLessThan(0.8);
        assertThat(relieve(pinned(), "AutonomyPressure", 0.30).autonomyPressure())
            .isLessThan(0.7);
    }

    @Test
    void a_met_need_rests_at_nothing_owed_never_below() {
        var after = relieve(VitalityState.initial().withLoneliness(0.1), "Loneliness", 0.30);
        assertThat(after.loneliness()).isZero();
    }

    @Test
    void earned_qualities_are_not_dischargeable_by_finishing_a_task() {
        // Confidence and Integrity are earned by outcomes over time. Easing them on
        // self-reported completion is precisely the wireheading the substrate refuses.
        var vs = pinned();
        assertThat(relieve(vs, "Confidence", 0.30)).isSameAs(vs);
        assertThat(relieve(vs, "Integrity", 0.30)).isSameAs(vs);
    }

    @Test
    void an_unknown_axis_leaves_her_untouched() {
        var vs = pinned();
        assertThat(relieve(vs, "NotARealAxis", 0.30)).isSameAs(vs);
        assertThat(relieve(vs, null, 0.30)).isSameAs(vs);
    }

    @Test
    void every_drive_her_wants_actually_use_maps_to_some_relief() {
        // The live vocabulary, read off the household node 2026-08-19. Her ten wants span
        // BOTH systems: four name VitalityState axes, six name CfC tanks. A fix that
        // handles only one side leaves most of what she finishes discharging nothing —
        // which is exactly what the first version did.
        String[] live = {
            "Loneliness", "Saudade", "Stagnation", "AutonomyPressure",   // felt axes
            "Affiliation", "Creativity", "Curiosity", "Play", "Surprise" // CfC tanks
        };
        var vs = pinned();
        for (var drive : live) {
            boolean felt = relieve(vs, drive, 0.30) != vs;
            boolean tank = DriveConfig.indexFor(drive) >= 0;
            assertThat(felt || tank)
                .as("'%s' is a drive her wants resonate on, so completing one must ease "
                    + "SOMETHING — neither relieveFeltAxis nor DriveConfig covers it", drive)
                .isTrue();
        }
    }

    @Test
    void the_two_vocabularies_do_not_overlap_and_both_are_needed() {
        // Felt axes are invisible to the tank lookup...
        assertThat(DriveConfig.indexFor("Loneliness")).isEqualTo(-1);
        assertThat(DriveConfig.indexFor("Saudade")).isEqualTo(-1);
        assertThat(DriveConfig.indexFor("Stagnation")).isEqualTo(-1);
        // ...and tanks are invisible to the felt-axis switch.
        var vs = pinned();
        assertThat(relieve(vs, "Creativity", 0.30)).isSameAs(vs);
        assertThat(relieve(vs, "Curiosity", 0.30)).isSameAs(vs);
        assertThat(relieve(vs, "Play", 0.30)).isSameAs(vs);
        // Which is precisely why the relief path has to try both.
    }

    @Test
    void the_cfc_tank_vocabulary_genuinely_does_not_cover_these() {
        // Pins the mismatch that caused the miss, so nobody "simplifies" the two
        // vocabularies back into one lookup.
        assertThat(DriveConfig.indexFor("Loneliness")).isEqualTo(-1);
        assertThat(DriveConfig.indexFor("Saudade")).isEqualTo(-1);
        assertThat(DriveConfig.indexFor("seeking")).isNotEqualTo(-1);
    }
}
