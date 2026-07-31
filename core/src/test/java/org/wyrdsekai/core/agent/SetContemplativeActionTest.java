package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1C: smoke tests for the new {@code set_contemplative}
 * action surface. Covers parser → policy → schema wiring; the actor-handler emotional-context
 * gate is exercised separately via {@link CompanionActor#handleSetContemplative} when the
 * companion is bootstrapped in the integration tier.
 */
class SetContemplativeActionTest {

    // ── Parser ──────────────────────────────────────────────────────

    @Test void parses_set_contemplative_on_true() {
        var input = """
            ```json
            {"action": "set_contemplative", "on": true}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.SetContemplative.class);
        assertThat(((ActionParser.AgentAction.SetContemplative) action).on()).isTrue();
    }

    @Test void parses_set_contemplative_on_false() {
        var input = """
            ```json
            {"action": "set_contemplative", "on": false}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.SetContemplative.class);
        assertThat(((ActionParser.AgentAction.SetContemplative) action).on()).isFalse();
    }

    @Test void parses_set_contemplative_default_on_true() {
        var input = """
            ```json
            {"action": "set_contemplative"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.SetContemplative.class);
        assertThat(((ActionParser.AgentAction.SetContemplative) action).on()).isTrue();
    }

    @Test void parses_set_contemplative_string_falsey() {
        var input = """
            ```json
            {"action": "set_contemplative", "on": "off"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.SetContemplative.class);
        assertThat(((ActionParser.AgentAction.SetContemplative) action).on()).isFalse();
    }

    // ── Policy ──────────────────────────────────────────────────────

    @Test void policy_registry_has_entry() {
        var policy = ActionPolicy.forAction("set_contemplative");
        assertThat(policy).isNotNull();
        assertThat(policy.actionType()).isEqualTo("set_contemplative");
        assertThat(policy.requiredTier()).isEqualTo(1);
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test void policy_actionTypeOf_resolves_to_set_contemplative() {
        var action = new ActionParser.AgentAction.SetContemplative(true);
        assertThat(ActionPolicy.actionTypeOf(action)).isEqualTo("set_contemplative");
    }

    @Test void policy_describes_directionally() {
        var on = new ActionParser.AgentAction.SetContemplative(true);
        var off = new ActionParser.AgentAction.SetContemplative(false);
        assertThat(ActionPolicy.describeAction(on)).contains("enter");
        assertThat(ActionPolicy.describeAction(off)).contains("leave");
    }

    // ── Schema ──────────────────────────────────────────────────────

    @Test void schema_registered_with_optional_on_field() {
        assertThat(ActionSchemas.hasSchema("set_contemplative")).isTrue();
    }

    // ── Acknowledgment heuristic ────────────────────────────────────

    @Test void looksLikeAcknowledgment_english() {
        assertThat(CompanionActor.looksLikeAcknowledgment("thanks for the note")).isTrue();
        assertThat(CompanionActor.looksLikeAcknowledgment("I appreciate that")).isTrue();
        assertThat(CompanionActor.looksLikeAcknowledgment("loved your write-up")).isTrue();
    }

    @Test void looksLikeAcknowledgment_japanese() {
        assertThat(CompanionActor.looksLikeAcknowledgment("読みました、ありがとう")).isTrue();
    }

    @Test void looksLikeAcknowledgment_spanish() {
        assertThat(CompanionActor.looksLikeAcknowledgment("gracias por la nota")).isTrue();
        assertThat(CompanionActor.looksLikeAcknowledgment("me gustó mucho")).isTrue();
    }

    @Test void looksLikeAcknowledgment_negative() {
        assertThat(CompanionActor.looksLikeAcknowledgment("hello there")).isFalse();
        assertThat(CompanionActor.looksLikeAcknowledgment("")).isFalse();
        assertThat(CompanionActor.looksLikeAcknowledgment(null)).isFalse();
    }
}
