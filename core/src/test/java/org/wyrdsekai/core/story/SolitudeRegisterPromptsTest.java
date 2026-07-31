package org.wyrdsekai.core.story;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 — verify the framings resource loads and that
 * {name} substitution works.
 */
class SolitudeRegisterPromptsTest {

    @Test
    void framingsReturnsFiveEntries() {
        var framings = SolitudeRegisterPrompts.framings("Companion");
        assertThat(framings).hasSize(5);
    }

    @Test
    void framingsSubstituteFocalName() {
        var framings = SolitudeRegisterPrompts.framings("Ember");
        for (var f : framings) {
            assertThat(f).contains("Ember").doesNotContain("{name}");
        }
    }

    @Test
    void framingsHandleNullName() {
        var framings = SolitudeRegisterPrompts.framings(null);
        for (var f : framings) {
            assertThat(f).contains("the focal entity").doesNotContain("{name}");
        }
    }

    @Test
    void framingsPreserveDistinctTexts() {
        // Five register cues should be distinct so consecutive solitude scenes
        // don't all open the same way.
        var framings = SolitudeRegisterPrompts.framings("Companion");
        assertThat(framings).doesNotHaveDuplicates();
    }
}
