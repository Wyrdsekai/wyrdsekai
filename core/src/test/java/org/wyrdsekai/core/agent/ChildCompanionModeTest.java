package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChildCompanionModeTest {

    @Test void under_7_restrictions() {
        var mode = new ChildCompanionMode("child-1",
            ChildCompanionMode.AgeBracket.UNDER_7, "parent-1");

        assertThat(mode.isActive()).isTrue();
        assertThat(mode.maxResponseTokens()).isEqualTo(100);
        assertThat(mode.isTopicAllowed("violence")).isFalse();
        assertThat(mode.isTopicAllowed("adventure")).isTrue();
        assertThat(mode.isCommandAllowed("trade")).isFalse();
        assertThat(mode.isCommandAllowed("look")).isTrue();
    }

    @Test void under_13_restrictions() {
        var mode = new ChildCompanionMode("child-2",
            ChildCompanionMode.AgeBracket.UNDER_13, "parent-1");

        assertThat(mode.maxResponseTokens()).isEqualTo(250);
        assertThat(mode.isTopicAllowed("gambling")).isFalse();
        assertThat(mode.isCommandAllowed("vote")).isFalse();
    }

    @Test void under_18_restrictions() {
        var mode = new ChildCompanionMode("teen-1",
            ChildCompanionMode.AgeBracket.UNDER_18, "parent-1");

        assertThat(mode.maxResponseTokens()).isEqualTo(500);
        assertThat(mode.isTopicAllowed("adult_content")).isFalse();
        assertThat(mode.isTopicAllowed("science")).isTrue();
    }

    @Test void inactive_mode_allows_everything() {
        var mode = new ChildCompanionMode("child-1",
            ChildCompanionMode.AgeBracket.UNDER_7, "parent-1", false);

        assertThat(mode.isActive()).isFalse();
        assertThat(mode.isTopicAllowed("violence")).isTrue();
        assertThat(mode.isCommandAllowed("trade")).isTrue();
    }

    @Test void age_bracket_resolution() {
        assertThat(ChildCompanionMode.AgeBracket.forAge(5))
            .isEqualTo(ChildCompanionMode.AgeBracket.UNDER_7);
        assertThat(ChildCompanionMode.AgeBracket.forAge(10))
            .isEqualTo(ChildCompanionMode.AgeBracket.UNDER_13);
        assertThat(ChildCompanionMode.AgeBracket.forAge(16))
            .isEqualTo(ChildCompanionMode.AgeBracket.UNDER_18);
    }

    @Test void system_prompt_addendum() {
        var mode = new ChildCompanionMode("child-1",
            ChildCompanionMode.AgeBracket.UNDER_7, "parent-1");
        var addendum = mode.systemPromptAddendum();

        assertThat(addendum).contains("young child");
        assertThat(addendum).contains("simple words");
    }

    @Test void system_prompt_empty_when_inactive() {
        var mode = new ChildCompanionMode("child-1",
            ChildCompanionMode.AgeBracket.UNDER_7, "parent-1", false);
        assertThat(mode.systemPromptAddendum()).isEmpty();
    }

    @Test void prompt_restrictions_list() {
        var mode = new ChildCompanionMode("child-1",
            ChildCompanionMode.AgeBracket.UNDER_13, "parent-1");
        var restrictions = mode.promptRestrictions();

        assertThat(restrictions).hasSize(3);
        assertThat(restrictions.get(0)).contains("Do not discuss");
        assertThat(restrictions.get(1)).contains("250 tokens");
        assertThat(restrictions.get(2)).contains("parent-1");
    }

    @Test void restricted_topics_static() {
        assertThat(ChildCompanionMode.restrictedTopics()).contains("violence", "gambling");
    }

    @Test void restricted_commands_static() {
        assertThat(ChildCompanionMode.restrictedCommands()).contains("trade", "vote");
    }
}
