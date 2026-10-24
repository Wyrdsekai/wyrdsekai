package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An item whose brain call failed handed the failure back in its findings slot, and the node
 * kept "[error] LLM call timed out or failed: null" as a draft finding in her Study and spoke
 * it (household node, 2026-10-23). A failure in the findings slot is a tool error, whatever
 * the script called it.
 */
class AnErrorIsNotAFindingTest {

    @Test
    void an_error_marker_is_recognised_however_it_is_spaced_or_cased() {
        assertThat(CompanionActor.looksLikeErrorMarker("[error] LLM call timed out or failed: null")).isTrue();
        assertThat(CompanionActor.looksLikeErrorMarker("  [ERROR] the brain did not answer within 90 s")).isTrue();
        assertThat(CompanionActor.looksLikeErrorMarker("[Error]")).isTrue();
    }

    @Test
    void findings_that_merely_mention_an_error_are_findings() {
        assertThat(CompanionActor.looksLikeErrorMarker("The sources describe an error in the 1911 census.")).isFalse();
        assertThat(CompanionActor.looksLikeErrorMarker("[S1] says the tide turns at six.")).isFalse();
        assertThat(CompanionActor.looksLikeErrorMarker("")).isFalse();
        assertThat(CompanionActor.looksLikeErrorMarker(null)).isFalse();
    }
}
