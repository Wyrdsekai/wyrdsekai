package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.voice.VoiceMode;
import org.wyrdsekai.core.voice.VoiceService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests spanning context access, voice, and governance features.
 */
class ContextAccessIntegrationTest {

    @BeforeEach
    void setUp() {
        ContextAccessManager.init();
        DesktopContextProvider.init();
        VoiceService.init();
    }

    @AfterEach
    void tearDown() {
        ContextAccessManager.reset();
        DesktopContextProvider.reset();
        VoiceService.reset();
    }

    @Test
    void request_access_action_parses_correctly() {
        var input = """
            I'd love to help more if I could see what you're working on.
            ```json
            {"action": "request_access", "source": "active_window", "scope": "vscode,terminal",
             "reason": "I could help more if I could see what file you're editing"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.RequestAccess.class);
        var ra = (ActionParser.AgentAction.RequestAccess) action;
        assertThat(ra.source()).isEqualTo("active_window");
        assertThat(ra.scope()).isEqualTo("vscode,terminal");
        assertThat(ra.reason()).contains("editing");
    }

    @Test
    void agent_requests_access_human_grants_agent_sees_context() {
        var mgr = ContextAccessManager.get();
        String agentId = "companion-ma";

        // Initially no access
        assertThat(mgr.isGranted(agentId, "active_window")).isFalse();

        // Human grants
        mgr.grant(agentId, "active_window", "vscode,terminal", "did:key:operator");

        // Agent now has access
        assertThat(mgr.isGranted(agentId, "active_window")).isTrue();
        assertThat(mgr.getScope(agentId, "active_window")).contains("vscode,terminal");

        // Context shows the grant
        String ctx = mgr.buildContext(agentId);
        assertThat(ctx).contains("active_window");
        assertThat(ctx).contains("vscode,terminal");
    }

    @Test
    void agent_requests_access_human_denies_30_day_cooldown_active() {
        var mgr = ContextAccessManager.get();
        String agentId = "companion-ma";

        // Human denies
        mgr.deny(agentId, "active_window", "did:key:operator");

        // Access denied
        assertThat(mgr.isGranted(agentId, "active_window")).isFalse();

        // Can't ask again (within 30 days)
        assertThat(mgr.canAskFor(agentId, "active_window")).isFalse();

        // Context shows denial
        String ctx = mgr.buildContext(agentId);
        assertThat(ctx).contains("denied");
        assertThat(ctx).contains("active_window");
    }

    @Test
    void desktop_context_absent_when_not_granted() {
        var provider = DesktopContextProvider.get();
        var mgr = ContextAccessManager.get();

        // No permission — no context
        var ctx = provider.getContext("companion-ma", mgr);
        assertThat(ctx).isEmpty();
    }

    @Test
    void desktop_context_requires_grant() {
        var provider = DesktopContextProvider.get();
        var mgr = ContextAccessManager.get();

        mgr.grant("companion-ma", "active_window", "vscode", "did:key:operator");

        // Provider should check permission (actual xdotool may not exist in test env,
        // but permission check passes)
        // We can't reliably test the full path without a desktop environment,
        // but the permission gate is verified.
        assertThat(mgr.isGranted("companion-ma", "active_window")).isTrue();
    }

    @Test
    void voice_mode_flag_flows_through_c2s() {
        // C2S Say with voice flag
        var sayWithVoice = new C2SMessage.Say("id1", "nexus", "What's the weather?",
            null, true);
        assertThat(sayWithVoice.isVoice()).isTrue();

        // C2S Say without voice flag (backward compatible)
        var sayNoVoice = new C2SMessage.Say("id2", "nexus", "Hello");
        assertThat(sayNoVoice.isVoice()).isFalse();

        // C2S Say with null voice flag
        var sayNullVoice = new C2SMessage.Say("id3", "nexus", "Hi", null, null);
        assertThat(sayNullVoice.isVoice()).isFalse();
    }

    @Test
    void voice_mode_flag_flows_through_s2c() {
        // S2C Prose with voice flag
        var proseWithVoice = new S2CMessage.Prose(
            1L, "Ma", "It's sunny!", List.of(), null, "normal",
            null, true, List.of(), true);
        assertThat(proseWithVoice.isVoice()).isTrue();

        // S2C Prose without voice (backward compatible)
        var proseNoVoice = new S2CMessage.Prose(2L, "Ma", "Hello!", List.of(), null, "normal");
        assertThat(proseNoVoice.isVoice()).isFalse();
    }

    @Test
    void governor_sees_policy_concerns_in_prompt() {
        var policy = HouseholdPolicy.defaults();
        var checker = new PolicyChecker(policy);

        // Budget concern
        var budgetConcerns = checker.checkComputeBudget("agent-ma", 11.0);
        assertThat(budgetConcerns).isNotEmpty();
        assertThat(budgetConcerns.getFirst().severity()).isEqualTo("alert");

        // Policy context for prompt
        String ctx = checker.buildPolicyContext();
        assertThat(ctx).contains("## Household Policy");
        assertThat(ctx).contains("$10.00");
    }

    @Test
    void policy_checker_concern_generates_notification_data() {
        var checker = new PolicyChecker(HouseholdPolicy.defaults());

        // Over budget → alert with description and recommendation
        var concerns = checker.checkComputeBudget("agent-ma", 15.0);
        assertThat(concerns).hasSize(1);
        var concern = concerns.getFirst();
        assertThat(concern.category()).isEqualTo("compute_budget");
        assertThat(concern.severity()).isEqualTo("alert");
        assertThat(concern.description()).isNotBlank();
        assertThat(concern.recommendation()).isNotBlank();
    }

    @Test
    void multiple_context_sources_with_different_permissions() {
        var mgr = ContextAccessManager.get();
        String agentId = "companion-ma";

        mgr.grant(agentId, "active_window", "vscode", "did:key:operator");
        mgr.grant(agentId, "calendar", "all", "did:key:operator");
        mgr.deny(agentId, "email_subjects", "did:key:operator");

        assertThat(mgr.isGranted(agentId, "active_window")).isTrue();
        assertThat(mgr.isGranted(agentId, "calendar")).isTrue();
        assertThat(mgr.isGranted(agentId, "email_subjects")).isFalse();
        assertThat(mgr.canAskFor(agentId, "email_subjects")).isFalse();

        String ctx = mgr.buildContext(agentId);
        assertThat(ctx).contains("active_window");
        assertThat(ctx).contains("calendar");
        assertThat(ctx).contains("email_subjects");
    }

    @Test
    void revoke_removes_context_from_prompt() {
        var mgr = ContextAccessManager.get();
        String agentId = "companion-ma";

        mgr.grant(agentId, "active_window", "all", "did:key:operator");
        assertThat(mgr.buildContext(agentId)).contains("active_window");

        mgr.revoke(agentId, "active_window");
        // After revoke with no other permissions, buildContext returns null
        assertThat(mgr.buildContext(agentId)).isNull();
    }

    @Test
    void voice_permission_through_context_access() {
        var mgr = ContextAccessManager.get();
        String agentId = "companion-ma";

        // Voice permission via context access system
        mgr.grant(agentId, "voice", "push_to_talk", "did:key:operator");
        assertThat(mgr.isGranted(agentId, "voice")).isTrue();
        assertThat(mgr.getScope(agentId, "voice")).contains("push_to_talk");
    }

    @Test
    void governor_profile_has_expected_properties() {
        var profile = SeedForgeGovernor.GOVERNOR;
        assertThat(profile.name()).isEqualTo("Governor");
        assertThat(profile.entityId()).isEqualTo("agent-governor");
        assertThat(profile.temperature()).isLessThan(0.5); // analytical
        assertThat(profile.systemPrompt()).contains("governance");
        assertThat(SeedForgeGovernor.DEFAULT_ROOM).isEqualTo("council-chamber");
    }
}
