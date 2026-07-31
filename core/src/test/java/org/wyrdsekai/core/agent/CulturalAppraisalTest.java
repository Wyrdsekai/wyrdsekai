package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parse is the safety boundary (2026-06-02): a small model's appraisal output is
 * fed straight into welfare tanks, so {@link CulturalAppraisal#parse(String)} must be
 * forgiving — anything it can't read becomes 0 (no tank move), never an exception.
 */
class CulturalAppraisalTest {

    @Test
    void cleanJsonParsesAllFour() {
        var a = CulturalAppraisal.parse(
            "{\"standing\": -0.4, \"harmony\": 0.7, \"amae\": 0.0, \"obligation\": 0.5}");
        assertThat(a.standing()).isEqualTo(-0.4);
        assertThat(a.harmony()).isEqualTo(0.7);
        assertThat(a.amae()).isEqualTo(0.0);
        assertThat(a.obligation()).isEqualTo(0.5);
    }

    @Test
    void looseFormatsAndPreambleStillParse() {
        // Small models prepend prose / use = / drop quotes — all must still read.
        var a = CulturalAppraisal.parse(
            "Here is the rating: standing=0.2 harmony = -0.3, amae:0.9 obligation -0.1");
        assertThat(a.standing()).isEqualTo(0.2);
        assertThat(a.harmony()).isEqualTo(-0.3);
        assertThat(a.amae()).isEqualTo(0.9);
        assertThat(a.obligation()).isEqualTo(-0.1);
    }

    @Test
    void outOfRangeIsClampedToUnitInterval() {
        var a = CulturalAppraisal.parse("standing: 5, harmony: -9, amae: 1, obligation: -1");
        assertThat(a.standing()).isEqualTo(1.0);
        assertThat(a.harmony()).isEqualTo(-1.0);
        assertThat(a.amae()).isEqualTo(1.0);
        assertThat(a.obligation()).isEqualTo(-1.0);
    }

    @Test
    void missingFieldsDefaultToZero_neverThrows() {
        var a = CulturalAppraisal.parse("{\"standing\": 0.6}");
        assertThat(a.standing()).isEqualTo(0.6);
        assertThat(a.harmony()).isEqualTo(0.0);
        assertThat(a.amae()).isEqualTo(0.0);
        assertThat(a.obligation()).isEqualTo(0.0);
        assertThat(a.isNeutral()).isFalse();
    }

    @Test
    void blankNullAndGarbageAreNeutral() {
        assertThat(CulturalAppraisal.parse(null).isNeutral()).isTrue();
        assertThat(CulturalAppraisal.parse("").isNeutral()).isTrue();
        assertThat(CulturalAppraisal.parse("I'm sorry, I can't do that.").isNeutral()).isTrue();
        assertThat(CulturalAppraisal.neutral().isNeutral()).isTrue();
    }
}
