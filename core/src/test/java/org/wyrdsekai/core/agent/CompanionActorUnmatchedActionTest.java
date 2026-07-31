package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link CompanionActor#extractUnmatchedActionName} —
 * gap-detection site #1.
 */
class CompanionActorUnmatchedActionTest {

    @Test
    void returns_null_for_null_or_blank_input() {
        assertThat(CompanionActor.extractUnmatchedActionName(null)).isNull();
        assertThat(CompanionActor.extractUnmatchedActionName("")).isNull();
        assertThat(CompanionActor.extractUnmatchedActionName("   ")).isNull();
    }

    @Test
    void returns_null_when_no_action_key_present() {
        assertThat(CompanionActor.extractUnmatchedActionName("just normal prose")).isNull();
        assertThat(CompanionActor.extractUnmatchedActionName(
            "I'd like to compress that for you.")).isNull();
    }

    @Test
    void extracts_unrecognized_action_name() {
        var content = "```json\n{\"action\":\"compress_archive\",\"path\":\"/tmp\"}\n```";
        assertThat(CompanionActor.extractUnmatchedActionName(content))
            .isEqualTo("compress_archive");
    }

    @Test
    void extracts_unrecognized_action_from_raw_json() {
        var content = "{\"action\": \"summarize_pdf\", \"url\": \"...\"}";
        assertThat(CompanionActor.extractUnmatchedActionName(content))
            .isEqualTo("summarize_pdf");
    }

    @Test
    void returns_null_for_known_action_names() {
        // Known actions go through a different branch (parser already
        // accepted the structure but maybe a required field was missing);
        // we don't want to record those as gaps.
        assertThat(CompanionActor.extractUnmatchedActionName(
            "{\"action\":\"go_to_room\",\"target\":\"workshop\"}")).isNull();
        assertThat(CompanionActor.extractUnmatchedActionName(
            "{\"action\":\"workbench_submit\"}")).isNull();
        assertThat(CompanionActor.extractUnmatchedActionName(
            "{\"action\":\"tell_agent\"}")).isNull();
    }

    @Test
    void handles_extra_whitespace_and_punctuation() {
        var content = "blah blah {\"action\"  :  \"create_calendar_event\", \"when\":\"tomorrow\"} blah";
        assertThat(CompanionActor.extractUnmatchedActionName(content))
            .isEqualTo("create_calendar_event");
    }
}
