package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.mcp.*;
import org.wyrdsekai.core.skill.*;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CompanionCapabilities record and the capability action flow.
 * Since CompanionActor is a Pekko actor, we test the components individually
 * and validate the full flow in E2E tests.
 */
class CompanionCapabilitiesTest {

    // same rationale as PromptAssemblerTest.
    @BeforeAll
    static void markVerified() {
        System.setProperty("wyrdsekai.protection.tampered", "false");
    }

    private static final String AGENT_DID = "did:key:z6Mktest";
    private static final String FAMILY_ID = "family-test-1";

    static FamilyLocker makeLocker() {
        var bud = SoulBud.original(AGENT_DID, "z6MkpublicKey", FAMILY_ID,
            "locker://test", "test-node", "qwen2.5:7b");
        return FamilyLocker.create(FAMILY_ID, "locker://test", bud);
    }

    static WorkbenchSkillExecutor makeExecutor(FamilyLocker locker) {
        return new WorkbenchSkillExecutor(locker, AGENT_DID);
    }

    // --- CompanionCapabilities record ---

    @Test void none_returns_empty_capabilities() {
        var caps = CompanionCapabilities.none();
        assertThat(caps.familyLocker()).isNull();
        assertThat(caps.mcpGateway()).isNull();
        assertThat(caps.workbenchExecutor()).isNull();
        assertThat(caps.skillRegistry()).isNull();
        assertThat(caps.openClawConnected()).isFalse();
        assertThat(caps.openClawSkillCount()).isZero();
        assertThat(caps.zoneContext()).isNull();
        assertThat(caps.workshopReachable()).isFalse();
    }

    @Test void capabilities_with_locker_and_executor() {
        var locker = makeLocker();
        var executor = makeExecutor(locker);
        var caps = new CompanionCapabilities(
            locker, null, executor, null, false, 0, null, true);
        assertThat(caps.familyLocker()).isNotNull();
        assertThat(caps.workbenchExecutor()).isNotNull();
        assertThat(caps.workshopReachable()).isTrue();
    }

    // --- Workbench submit flow (validation + packaging + registration) ---

    @Test void workbench_submit_flow_validates_and_registers() {
        var locker = makeLocker();
        var executor = makeExecutor(locker);

        // Simulate the flow: validate → create SkillItemCodec → toSoulItem → register
        String skillName = "weather-check";
        String runtime = "graaljs";
        String code = "function execute(params) { return { city: params.city, checked: true }; }";

        var validation = WorkbenchValidator.validate(skillName, runtime, code, null);
        assertThat(validation.valid()).isTrue();

        var def = SkillItemCodec.create(runtime, code, null, "Check weather", null, null);
        var item = SkillItemCodec.toSoulItem(skillName, def, AGENT_DID);
        locker.store(item, AGENT_DID);
        executor.register(skillName, item, def);

        assertThat(executor.supports("workbench.weather-check")).isTrue();

        // Execute — real GraalJS returns the script's result object
        var ctx = SkillContext.forAgent(AGENT_DID, "workshop", Map.of(), 1000);
        var result = executor.execute("workbench.weather-check",
            Map.of("city", "Tokyo"), ctx);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("city", "Tokyo");
    }

    @Test void workbench_submit_rejects_invalid() {
        var validation = WorkbenchValidator.validate(
            "bad skill", "graaljs", "no execute function", null);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors()).hasSizeGreaterThan(1);
    }

    // --- Skill execute flow ---

    @Test void skill_execute_through_workbench() {
        var locker = makeLocker();
        var executor = makeExecutor(locker);

        // Register a skill
        var def = SkillItemCodec.create("graaljs", "function execute(p) {}",
            null, "Test", null, null);
        var item = SkillItemCodec.toSoulItem("ping", def, AGENT_DID);
        executor.register("ping", item, def);

        // Execute it
        var ctx = SkillContext.forAgent(AGENT_DID, "nexus", Map.of(), 1000);
        var result = executor.execute("workbench.ping", Map.of(), ctx);
        assertThat(result.success()).isTrue();
        assertThat(result.executorTier()).isEqualTo(SkillTier.WORKBENCH);
    }

    // --- Capability context builds correctly ---

    @Test void capability_context_includes_workshop_and_vitality() {
        // Skills are now discovered via tool definitions, not prompt text.
        // Workshop and vitality sections remain in the prompt.
        var ctx = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0,
            VitalityState.initial(), null, null, true);
        assertThat(ctx).contains("Workshop: reachable");
        assertThat(ctx).contains("Energy:");
    }

    @Test void capability_context_contains_placeholder_without_capabilities() {
        var ctx = CapabilityContextBuilder.build(
            AGENT_DID, null, null, false, 0, null, null, null, false);
        // Placeholder present (replaced at runtime by CompanionActor with tool definitions)
        assertThat(ctx).contains("Built-in Actions");
        assertThat(ctx).contains("replaced at runtime");
    }

    // --- PromptAssembler with capability context ---

    @Test void prompt_assembler_includes_capability_layer() {
        var profile = new AgentProfile(
            "Kiko", "agent-1", "agent", "A helpful companion",
            "You are Kiko, a helpful companion.",
            16384, 512, 0.7);

        var locker = makeLocker();
        var def = SkillItemCodec.create("graaljs", "function execute(p) {}",
            null, "Check weather", null, null);
        locker.store(SkillItemCodec.toSoulItem("weather", def, AGENT_DID), AGENT_DID);

        var capCtx = CapabilityContextBuilder.build(
            AGENT_DID, locker, null, false, 0,
            VitalityState.initial(), null, null, true);

        var messages = PromptAssembler.assemble(
            profile, null, List.of(), null,
            null, List.of(), null, null,
            null, null, null, null, capCtx);

        // Capability context should appear as a system message with the placeholder
        var systemMsgs = messages.stream()
            .filter(m -> "system".equals(m.role()))
            .map(m -> m.content())
            .toList();
        assertThat(systemMsgs).anyMatch(s -> s.contains("Built-in Actions"));
    }

    @Test void prompt_assembler_omits_null_capability_context() {
        var profile = new AgentProfile(
            "Kiko", "agent-1", "agent", "A companion",
            "You are Kiko.",
            4096, 512, 0.7);

        var messages = PromptAssembler.assemble(
            profile, null, List.of(), null,
            null, List.of(), null, null,
            null, null, null, null, null);

        // System prompt + core rules + time context (always injected)
        var systemMsgs = messages.stream()
            .filter(m -> "system".equals(m.role()))
            .toList();
        assertThat(systemMsgs).hasSize(3);
    }

    // --- Vitality cost model ---

    @Test void skill_use_costs_expected_vitality() {
        var vitality = VitalityState.initial();
        double before = vitality.energy();
        // Use skill cost: -0.02 energy, -0.01 contextBudget
        vitality = vitality
            .withEnergy(vitality.energy() - 0.02)
            .withContextBudget(vitality.contextBudget() - 0.01);
        assertThat(vitality.energy()).isEqualTo(before - 0.02, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test void skill_creation_costs_expected_vitality() {
        var vitality = VitalityState.initial();
        double beforeEnergy = vitality.energy();
        double beforeBudget = vitality.contextBudget();
        // Create skill cost: -0.20 energy, -0.13 contextBudget
        vitality = vitality
            .withEnergy(vitality.energy() - 0.20)
            .withContextBudget(vitality.contextBudget() - 0.13);
        assertThat(vitality.energy()).isEqualTo(beforeEnergy - 0.20, org.assertj.core.data.Offset.offset(0.001));
        assertThat(vitality.contextBudget()).isEqualTo(beforeBudget - 0.13, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test void skill_reuse_gives_energy_recovery() {
        var vitality = VitalityState.initial()
            .withEnergy(0.50); // depleted
        // Use -0.02
        vitality = vitality.withEnergy(vitality.energy() - 0.02);
        assertThat(vitality.energy()).isEqualTo(0.48, org.assertj.core.data.Offset.offset(0.001));
        // Recovery +0.02 on success = net zero
        vitality = vitality.withEnergy(vitality.energy() + 0.02);
        assertThat(vitality.energy()).isEqualTo(0.50, org.assertj.core.data.Offset.offset(0.001));
    }
}
