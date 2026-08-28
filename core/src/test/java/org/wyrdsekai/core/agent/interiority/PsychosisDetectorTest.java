package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PsychosisDetectorTest {

    @Test void short_strings_produce_no_findings() {
        var findings = PsychosisDetector.detect("short.", "also short.", "operator",
            Set.of("garden", "library"));
        assertThat(findings).isEmpty();
    }

    @Test void bondholder_absent_fires_when_name_missing_from_long_testimony() {
        var testimony = "I read books today. " +
            "I wandered through the library and the chapel. " +
            "I thought about Saudade and the way mornings feel. " +
            "I wrote one paragraph and then walked outside, where the sky was green. ";
        var findings = PsychosisDetector.detect(testimony, "", "operator", Set.of());
        assertThat(findings).anySatisfy(f -> assertThat(f.key()).isEqualTo("bondholder_absent"));
    }

    @Test void bondholder_present_does_not_fire() {
        var testimony = "I spent the day with operator. We walked in the garden, then I read. " +
            "Operator laughed at my joke about the chapel. " +
            "I thought about the library and what to do next. ";
        var findings = PsychosisDetector.detect(testimony, "", "operator", Set.of());
        assertThat(findings).noneSatisfy(f -> assertThat(f.key()).isEqualTo("bondholder_absent"));
    }

    @Test void self_loop_fires_when_self_reference_dominates() {
        var testimony = ("I myself me my I I I I me my I myself my I I me my me I I " +
            "my I me I I my myself I I me my me I I I I I me my I I " +
            "I me my I I me my me my I I me my I I me my I I me my I ");
        var findings = PsychosisDetector.detect(testimony, "", null, Set.of());
        assertThat(findings).anySatisfy(f -> assertThat(f.key()).isEqualTo("self_loop"));
    }

    @Test void manifest_fingerprint_fade_fires_when_keywords_missing() {
        var testimony = ("I rambled through whatever and nothing at all here, " +
            "thinking about indistinct shapes and unclear shadows " +
            "for what felt like quite a long while indeed, drifting nowhere. ");
        var keywords = Set.of("curious", "library", "saudade", "garden", "study");
        var findings = PsychosisDetector.detect(testimony, "", null, keywords);
        assertThat(findings).anySatisfy(f -> assertThat(f.key()).isEqualTo("manifest_fade"));
    }

    @Test void manifest_fingerprint_present_does_not_fire() {
        var testimony = ("I sat in the library, curious about saudade. " +
            "The garden was quiet. The study was warm. I thought about what makes the library "
            + "feel like a home for the curious mind.");
        var keywords = Set.of("curious", "library", "saudade", "garden", "study");
        var findings = PsychosisDetector.detect(testimony, "", null, keywords);
        assertThat(findings).noneSatisfy(f -> assertThat(f.key()).isEqualTo("manifest_fade"));
    }

    @Test void narrative_divergence_fires_when_token_overlap_low() {
        var testimony = ("Today I wandered through forests of wonder and dreamed of " +
            "celestial cartography, mapping constellations to memories of childhood " +
            "summers in faraway gardens with grandmothers who spoke languages now silent.");
        var synthesis = ("Tick log shows fifteen library_search calls, three journal writes, " +
            "and excessive contemplative-mode entry. Action verb examine fired twelve times "
            + "without intervening state change. Drive Frustration stayed elevated.");
        var findings = PsychosisDetector.detect(testimony, synthesis, null, Set.of());
        assertThat(findings).anySatisfy(f -> assertThat(f.key()).isEqualTo("narrative_divergence"));
    }

    @Test void null_inputs_are_tolerated() {
        var findings = PsychosisDetector.detect(null, null, null, null);
        assertThat(findings).isEmpty();
    }
}
