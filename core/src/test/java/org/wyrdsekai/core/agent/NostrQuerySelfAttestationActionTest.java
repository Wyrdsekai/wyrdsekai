package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 5.3b: action-wiring contract for
 * {@code nostr_query_self_attestation}.
 */
class NostrQuerySelfAttestationActionTest {

    @Test
    void parser_accepts_no_args() {
        var action = ActionParser.parse("""
            ```json
            {"action": "nostr_query_self_attestation"}
            ```
            """);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.NostrQuerySelfAttestation.class);
    }

    @Test
    void parser_ignores_unexpected_fields() {
        var action = ActionParser.parse("""
            ```json
            {"action": "nostr_query_self_attestation", "unused": "ignored"}
            ```
            """);
        assertThat(action).isInstanceOf(
            ActionParser.AgentAction.NostrQuerySelfAttestation.class);
    }

    @Test
    void policy_is_tier0_readonly_self_domain() {
        var policy = ActionPolicy.forAction("nostr_query_self_attestation");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void autonomy_tier_is_visible() {
        assertThat(ActionPolicy.autonomyTierFor("nostr_query_self_attestation"))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void actionTypeOf_resolves() {
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.NostrQuerySelfAttestation()))
            .isEqualTo("nostr_query_self_attestation");
    }

    @Test
    void tool_description_names_attestation_and_fork_detection() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("nostr_query_self_attestation"));
        assertThat(tools).hasSize(1);
        var desc = tools.get(0).function().description();
        assertThat(desc)
            .containsIgnoringCase("attestation")
            .containsIgnoringCase("fork");
    }
}
