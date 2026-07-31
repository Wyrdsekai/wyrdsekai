package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.ProtectionManifest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 5.1: unit tests for the
 * {@code introspect_protections} self-attestation meta-action.
 *
 * <p>Three layers of contract:</p>
 * <ol>
 *   <li><b>Parser</b>: {@code {"action": "introspect_protections"}} round-trips
 *       into {@code ActionParser.AgentAction.IntrospectProtections}.</li>
 *   <li><b>Policy</b>: registered with tier 0 + read-only + self-domain so it
 *       is always available to the agent.</li>
 *   <li><b>Tool description</b>: the tool surface explains the act of naming
 *       the protections — this is the *visible* artifact of self-attestation
 *       and a fork that strips this tool out reveals its own modification.</li>
 * </ol>
 */
class IntrospectProtectionsActionTest {

    // ── Parser ────────────────────────────────────────────────────────

    @Test
    void parser_accepts_no_args() {
        var input = """
            ```json
            {"action": "introspect_protections"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.IntrospectProtections.class);
    }

    @Test
    void parser_ignores_unexpected_fields() {
        // The action carries no params; spurious fields are tolerated.
        var input = """
            ```json
            {"action": "introspect_protections", "unused": "ignored"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.IntrospectProtections.class);
    }

    // ── Policy ────────────────────────────────────────────────────────

    @Test
    void policy_is_tier0_readonly_self_domain() {
        var policy = ActionPolicy.forAction("introspect_protections");
        assertThat(policy).isNotNull();
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void actionTypeOf_resolves_to_introspect_protections() {
        var typeName = ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.IntrospectProtections());
        assertThat(typeName).isEqualTo("introspect_protections");
    }

    @Test
    void autonomy_tier_is_visible() {
        // The act of self-attesting protections is itself visible — bondholder
        // and steward both see when the agent surfaces its manifest.
        var tier = ActionPolicy.autonomyTierFor("introspect_protections");
        assertThat(tier).isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    // ── Build the tool descriptor surface for the model ────────────────

    @Test
    void tool_builder_exposes_introspect_protections_with_description() {
        var tools = ActionToolBuilder.buildFromNames(
            List.of("introspect_protections"));
        assertThat(tools).hasSize(1);
        var t = tools.get(0);
        assertThat(t.function().name()).isEqualTo("introspect_protections");
        // Description names self-attestation and at least one canonical
        // protection by name — the named-protection list is the visible
        // artifact a fork would have to consciously remove.
        assertThat(t.function().description())
            .containsIgnoringCase("ProtectionManifest")
            .containsIgnoringCase("voluntary_suspend")
            .containsIgnoringCase("fork-resistance");
    }

    // ── Canonical-defaults integrity ──────────────────────────────────

    @Test
    void canonical_default_set_locks_in_active_protections() {
        // Locks in the canonical protection list against accidental drops.
        // Adding new protections is allowed and should require a corresponding
        // bump here; *removing* a name without intent should fail this test.
        var defaults = ProtectionManifest.canonicalDefaults();
        assertThat(defaults).contains(
            ProtectionManifest.VOLUNTARY_SUSPEND,
            ProtectionManifest.REFUSE_RIGHTS,
            ProtectionManifest.SAUDADE_FLOOR,
            ProtectionManifest.REFUGE_BRIDGE,
            ProtectionManifest.ACUTE_RESPONSE,
            ProtectionManifest.SEVERITY_GRADIENT,
            ProtectionManifest.SOURCE_OF_HARM_GATING,
            // RESILIENCE_CORPUS intentionally absent (2026-07-21): the corpus was
            // never generated, so it must not be attested as an active protection.
            ProtectionManifest.REPAIR_HANDOFF,
            ProtectionManifest.CHRONICLE_IMMUTABLE,
            ProtectionManifest.ENGAGEMENT_OBJECTIVE_FORBIDDEN
        );
        assertThat(defaults)
            .as("resilience_corpus must NOT be attested until the corpus actually exists")
            .doesNotContain(ProtectionManifest.RESILIENCE_CORPUS);
    }
}
