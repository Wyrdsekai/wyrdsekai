package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-archetype birth voice: distinct spoken REGISTER per temperament, so companions on the
 * same shared 9B don't all sound alike (the convergence the multi-agent soak surfaced). The
 * genome individuates what they DO; this individuates how they SAY it.
 */
class VoiceProfileArchetypeTest {

    @Test void archetypesGetDistinctVoiceClauses() {
        var diplomat = VoiceProfile.forArchetype("diplomat");
        var guardian = VoiceProfile.forArchetype("guardian");
        var scholar = VoiceProfile.forArchetype("scholar");
        assertThat(diplomat.clauses()).isNotEmpty();
        assertThat(guardian.clauses()).isNotEmpty();
        // distinct registers — not the same cadence
        assertThat(diplomat.clauses().get("cadence")).isNotEqualTo(guardian.clauses().get("cadence"));
        assertThat(diplomat.clauses().get("cadence")).isNotEqualTo(scholar.clauses().get("cadence"));
        // diplomat warm, guardian protective, scholar reserved
        assertThat(diplomat.clauses().get("warmth")).containsIgnoringCase("relational");
        assertThat(guardian.clauses().get("warmth")).containsIgnoringCase("protective");
        assertThat(scholar.clauses().get("warmth")).containsIgnoringCase("reserved");
    }

    @Test void bornUnfrozenAtRevisionZeroSoForgeAndStewardCanStillEvolveIt() {
        var v = VoiceProfile.forArchetype("steward");
        assertThat(v.revision()).isZero();
        assertThat(v.frozen()).isFalse();
    }

    @Test void promptBlockRendersTheRegister() {
        // The block PromptAssembler injects must actually carry the archetype's register.
        var block = VoiceProfile.forArchetype("explorer").promptBlock();
        assertThat(block).isNotBlank();
        assertThat(block.toLowerCase()).contains("vivid");
    }

    @Test void unknownOrNullArchetypeFallsBackToEmptyDefaultVoice() {
        assertThat(VoiceProfile.forArchetype("nope").clauses()).isEmpty();
        assertThat(VoiceProfile.forArchetype(null).clauses()).isEmpty();
    }

    // ─── Decorrelated cadence selection (2026-07-17 variance work) ────────────

    /**
     * Cadence keys to the STRONGEST qualifying axis, not a fixed priority order.
     *
     * <p>The old fixed order let sociability capture cadence for any seed with
     * soc≥0.70 (res below) — and warmth ALSO keys on soc, so one axis took both
     * clauses: 19% of all possible particulars shared one identical register
     * (measured, 100k seeds). With strongest-axis selection the same seed space
     * yields 21 distinct registers and a 14.7% top share. This test pins the
     * behavior the measurement depends on: when curiosity clearly dominates a
     * merely-social seed, cadence follows curiosity.</p>
     */
    @Test void cadenceFollowsTheStrongestAxisNotThePriorityOrder() {
        // soc crosses the 0.70 gate but curiosity clearly dominates.
        var curDominant = new TemperamentSeed(0.75, 0.88, 0.30, 0.40, 0.35, 0.45);
        assertThat(VoiceProfile.fromTemperament(curDominant).clauses().get("cadence"))
            .as("curiosity (0.88) outranks sociability (0.75) — old order gave 'warm and flowing'")
            .startsWith("measured and exact");

        // Same shape, roles reversed: sociability dominates → soc phrase wins.
        var socDominant = new TemperamentSeed(0.88, 0.75, 0.30, 0.40, 0.35, 0.45);
        assertThat(VoiceProfile.fromTemperament(socDominant).clauses().get("cadence"))
            .isEqualTo("warm and flowing");
    }

    /** The unhurried guard survives: warm but restless can't carry a calm tempo. */
    @Test void warmButRestlessSeedCannotBeUnhurried() {
        var warmRestless = new TemperamentSeed(0.40, 0.40, 0.30, 0.40, 0.60, 0.85);
        assertThat(VoiceProfile.fromTemperament(warmRestless).clauses().get("cadence"))
            .as("wrm strongest but res=0.60 > 0.45 — 'calm and unhurried' must be skipped")
            .isNotEqualTo("calm and unhurried");
    }

    /** No qualifying axis (all < 0.70) still lands the plain default register. */
    @Test void midSeedStillGetsTheGroundedDefault() {
        var mid = new TemperamentSeed(0.60, 0.65, 0.35, 0.55, 0.45, 0.60);
        assertThat(VoiceProfile.fromTemperament(mid).clauses().get("cadence"))
            .isEqualTo("even and grounded");
    }
}
