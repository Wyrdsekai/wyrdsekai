package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.mcp.*;
import org.wyrdsekai.core.skill.SkillItemCodec;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityContextBuilderTest {

    private static final String AGENT_DID = "did:key:z6Mktest";
    private static final String FAMILY_ID = "family-test-1";

    static SoulItem makeSkill(String name, String description) {
        var def = SkillItemCodec.create("graaljs", "function execute(p) {}",
            null, description, null, null);
        return SkillItemCodec.toSoulItem(name, def, AGENT_DID);
    }

    static FamilyLocker lockerWithSkills(SoulItem... items) {
        var bud = SoulBud.original(AGENT_DID, "z6MkpublicKey", FAMILY_ID,
            "locker://test", "test-node", "qwen2.5:7b");
        var locker = FamilyLocker.create(FAMILY_ID, "locker://test", bud);
        for (var item : items) {
            locker.store(item, AGENT_DID);
        }
        return locker;
    }

    static McpGatewayService gatewayWithServices(McpServiceConfig... configs) {
        var registry = new McpServiceRegistry();
        for (var config : configs) registry.register(config);
        return new McpGatewayService(registry,
            new McpRateLimiter(), new McpCircuitBreaker(), null);
    }

    static McpServiceConfig service(String id, String name) {
        return new McpServiceConfig(id, name, "http", "http://localhost", "local",
            null, null, true);
    }

    @Test
    void minimal_state_contains_only_built_in_actions() {
        var result = CapabilityContextBuilder.build(AGENT_DID, null, null, null, null);
        // No skills, MCP, OpenClaw, zone, workshop, or vitality — only built-in actions
        assertTrue(result.contains("## Built-in Actions"));
        assertFalse(result.contains("## Available Capabilities"));
        assertFalse(result.contains("## MCP Services"));
        assertFalse(result.contains("OpenClaw"));
        assertFalse(result.contains("Workshop"));
        assertFalse(result.contains("Energy:"));
    }

    @Test
    void skill_items_no_longer_in_prompt_text() {
        // Skills are now discovered via tool definitions (API parameter),
        // not listed in prompt text ( migration).
        var locker = lockerWithSkills(
            makeSkill("weather-check", "Fetch weather for a city"));
        var result = CapabilityContextBuilder.build(
            AGENT_DID, locker, null, null, "weather");
        // Skills section removed — tools handle discovery
        assertFalse(result.contains("## Available Capabilities"),
            "Skills should no longer appear as prompt text");
    }

    @Test
    void multiple_skills_not_in_prompt() {
        var locker = lockerWithSkills(
            makeSkill("weather", "Get weather"),
            makeSkill("stocks", "Get stock prices"));
        var result = CapabilityContextBuilder.build(
            AGENT_DID, locker, null, null, null);
        // Placeholder present, but no individual skill listings
        assertTrue(result.contains("## Built-in Actions"));
    }

    @Test
    void mcp_services_no_longer_in_prompt_text() {
        // MCP services are now discoverable via tool calling, not prompt text.
        var gateway = gatewayWithServices(
            service("searxng", "Web search"),
            service("hearth-ha", "Home Assistant"));
        var result = CapabilityContextBuilder.build(AGENT_DID, null, gateway, null, null);
        assertFalse(result.contains("## MCP Services"),
            "MCP should no longer appear as prompt text section");
    }

    @Test
    void disabled_mcp_services_excluded() {
        // MCP services no longer in prompt text at all
        var registry = new McpServiceRegistry();
        registry.register(service("active", "Active Service"));
        registry.register(new McpServiceConfig("disabled", "Off", "http",
            "http://localhost", "local", null, null, false));
        var gateway = new McpGatewayService(registry,
            new McpRateLimiter(), new McpCircuitBreaker(), null);

        var result = CapabilityContextBuilder.build(AGENT_DID, null, gateway, null, null);
        assertFalse(result.contains("## MCP Services"));
    }

    @Test
    void openclaw_no_longer_in_prompt_text() {
        // OpenClaw listed via tool calling, not prompt text
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, true, 13729, null, null, null, false);
        assertFalse(result.contains("OpenClaw"),
            "OpenClaw should no longer appear in prompt text");
    }

    @Test
    void openclaw_disconnected_not_shown() {
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, null, null, null, false);
        assertFalse(result.contains("OpenClaw"));
    }

    @Test
    void zone_context_included() {
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, null, null,
            "## Household Zone (2 nodes)\n- phone: 3B\n- laptop: 7B", false);
        assertTrue(result.contains("Household Zone"));
        assertTrue(result.contains("phone: 3B"));
    }

    @Test
    void workshop_reachable_shown() {
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, null, null, null, true);
        assertTrue(result.contains("Workshop: reachable"));
    }

    @Test
    void workshop_not_reachable_not_shown() {
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, null, null, null, false);
        assertFalse(result.contains("Workshop"));
    }

    @Test
    void vitality_shown() {
        var vitality = VitalityState.initial();
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, vitality, null, null, false);
        assertTrue(result.contains("Energy:"));
        assertTrue(result.contains("Curiosity:"));
    }

    @Test
    void null_vitality_no_crash() {
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, null, null, null, false);
        assertFalse(result.contains("Energy:"));
    }

    @Test
    void full_context_combines_remaining_sections() {
        // Post-migration: skills/MCP/OpenClaw removed from prompt text.
        // Only zone, workshop, placeholder, and vitality remain.
        var locker = lockerWithSkills(makeSkill("weather", "Get weather"));
        var gateway = gatewayWithServices(service("searxng", "Web search"));
        var vitality = VitalityState.initial();

        var result = CapabilityContextBuilder.build(
            AGENT_DID, locker, gateway, true, 5000, vitality, "weather",
            "## Zone (2 nodes)", true);

        assertTrue(result.contains("Zone"));
        assertTrue(result.contains("Workshop: reachable"));
        assertTrue(result.contains("## Built-in Actions"));
        assertTrue(result.contains("Energy:"));
        // These are now handled by tool calling, not prompt text
        assertFalse(result.contains("## Available Capabilities"));
        assertFalse(result.contains("## MCP Services"));
        assertFalse(result.contains("OpenClaw"));
    }

    @Test
    void built_in_actions_placeholder_present() {
        // The placeholder section exists (replaced by CompanionActor at runtime
        // with consolidated inventory + tool definitions via API parameter)
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, null, null, null, false);
        assertTrue(result.contains("## Built-in Actions"));
        assertTrue(result.contains("replaced at runtime"));
        // Individual action schemas no longer in prompt text
        assertFalse(result.contains("go_to_room"),
            "Action schemas moved to API tool definitions");
    }

    @Test
    void built_in_actions_schemas_moved_to_tool_definitions() {
        // Verify action schemas are NOT in prompt text (moved to API tools parameter)
        var result = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, null, null, null, false);
        assertFalse(result.contains("\"action\": \"go_to_room\""),
            "Action schemas should be in tool definitions, not prompt");
        assertFalse(result.contains("\"action\": \"library_search\""));
        assertFalse(result.contains("\"action\": \"remember\""));
    }

    @Test
    void built_in_actions_appear_in_full_context() {
        var locker = lockerWithSkills(makeSkill("weather", "Get weather"));
        var gateway = gatewayWithServices(service("searxng", "Web search"));
        var vitality = VitalityState.initial();

        var result = CapabilityContextBuilder.build(
            AGENT_DID, locker, gateway, true, 5000, vitality, "weather",
            "## Zone (2 nodes)", true);

        // Built-in actions should be between Workshop and Vitality sections
        assertTrue(result.contains("## Built-in Actions"));
        int workshopIdx = result.indexOf("Workshop: reachable");
        int actionsIdx = result.indexOf("## Built-in Actions");
        int vitalityIdx = result.indexOf("## How You Feel");
        assertTrue(workshopIdx < actionsIdx, "Built-in actions should come after Workshop");
        assertTrue(actionsIdx < vitalityIdx, "Built-in actions should come before Vitality");
    }

    @Test
    void unauthorized_locker_gracefully_handled() {
        // Create locker with a different DID — AGENT_DID is not authorized
        var otherBud = SoulBud.original("did:key:z6Other", "z6MkOtherKey", "family-other",
            "locker://test", "test-node", "qwen2.5:7b");
        var locker = FamilyLocker.create("family-other", "locker://test", otherBud);
        var result = CapabilityContextBuilder.build(
            AGENT_DID, locker, null, null, "weather");
        // Should not crash, should not show skills
        assertFalse(result.contains("## Available Capabilities"));
    }

    @Test
    void null_agent_did_gracefully_handled() {
        var result = CapabilityContextBuilder.build(
            null, null, null, null, null);
        // No skills shown (null DID), but built-in actions still present
        assertFalse(result.contains("## Available Capabilities"));
        assertTrue(result.contains("## Built-in Actions"));
    }
}
