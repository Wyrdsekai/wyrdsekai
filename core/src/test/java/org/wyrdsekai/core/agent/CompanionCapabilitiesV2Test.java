package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.empathy.EpigeneticModifier;
import org.wyrdsekai.core.item.*;
import org.wyrdsekai.core.skill.*;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Companion Capabilities v2 — validates all wave features
 * working together: equipment, proactivity, self-assessment, delegation chains,
 * skills.sh interop, collaborative crafting, room imprinting, starter kit.
 *
 * These tests use real objects (no Pekko, no mocks) to verify cross-component
 * interactions that the individual unit tests don't cover.
 */
class CompanionCapabilitiesV2Test {

    private static final String AGENT_DID = "did:key:z6MkV2Test";
    private static final String FAMILY_ID = "family-v2-test";

    private FamilyLocker locker;
    private WorkbenchSkillExecutor workbenchExecutor;
    private EquipmentService equipmentService;
    private SkillUsageTracker usageTracker;

    @BeforeEach void setUp() {
        var bud = SoulBud.original(AGENT_DID, "z6MkPubV2", FAMILY_ID,
            "locker://test", "test-node", "qwen2.5:7b");
        locker = FamilyLocker.create(FAMILY_ID, "locker://test", bud);
        workbenchExecutor = new WorkbenchSkillExecutor(locker, AGENT_DID);
        equipmentService = new EquipmentService();
        usageTracker = new SkillUsageTracker();
    }

    // --- 1. Starter kit → equipment → prompt context pipeline ---

    @Test void starter_kit_items_equippable_and_produce_prompt_context() {
        // Provision starter kit → items stored in locker
        var items = StarterKitProvisioner.provision(AGENT_DID, 8192, locker);
        assertThat(items).hasSize(7);

        // Find Focused Mode aspect in locker
        var aspects = locker.byCategory("aspect", AGENT_DID);
        assertThat(aspects).isNotEmpty();
        var focused = aspects.stream()
            .filter(i -> i.label().contains("Focused"))
            .findFirst().orElseThrow();

        // Equip it
        assertThat(equipmentService.equip(AGENT_DID, focused)).isTrue();

        // Prompt context should include the overlay
        var ctx = equipmentService.buildPromptContext(AGENT_DID);
        assertThat(ctx).isNotNull();
        assertThat(ctx).contains("Focused Mode");
        assertThat(ctx).contains("methodical");
        assertThat(ctx).contains("reading glasses");
    }

    // --- 2. Equipment vitality shifts from equipped aspects ---

    @Test void equipped_aspects_produce_vitality_shifts() {
        var items = StarterKitProvisioner.standardKit(AGENT_DID);
        // Equip Focused Mode and Social Mode — they have opposing shifts
        var focused = items.stream().filter(i -> i.label().equals("Focused Mode")).findFirst().orElseThrow();
        var social = items.stream().filter(i -> i.label().equals("Social Mode")).findFirst().orElseThrow();

        equipmentService.equip(AGENT_DID, focused);
        var shifts = equipmentService.computeVitalityShifts(AGENT_DID);
        assertThat(shifts).containsEntry("focus", 0.15);
        assertThat(shifts).containsEntry("curiosity", 0.1);
        assertThat(shifts).containsEntry("rapport", -0.05);

        equipmentService.equip(AGENT_DID, social);
        var combined = equipmentService.computeVitalityShifts(AGENT_DID);
        // Stacked: focus = 0.15 + (-0.05) = 0.10, rapport = -0.05 + 0.15 = 0.10
        assertThat(combined.get("focus")).isEqualTo(0.10, org.assertj.core.data.Offset.offset(0.001));
        assertThat(combined.get("rapport")).isEqualTo(0.10, org.assertj.core.data.Offset.offset(0.001));
    }

    // --- 3. Reagent consume → active effect → expiry ---

    @Test void reagent_lifecycle_consume_tick_expire() {
        var items = StarterKitProvisioner.standardKit(AGENT_DID);
        var draught = items.stream()
            .filter(i -> i.label().equals("Restoring Draught"))
            .findFirst().orElseThrow();

        // Consume
        var effect = equipmentService.consume(AGENT_DID, draught);
        assertThat(effect).isNotNull();
        assertThat(effect.label()).isEqualTo("Restoring Draught");
        assertThat(equipmentService.getActiveEffects(AGENT_DID)).hasSize(1);

        // Prompt context includes active effect
        var ctx = equipmentService.buildPromptContext(AGENT_DID);
        assertThat(ctx).contains("Restoring Draught");
        assertThat(ctx).contains("remaining");

        // Tick until expiry (duration = 600 ticks)
        for (int i = 0; i < 600; i++) {
            equipmentService.tick(AGENT_DID);
        }
        assertThat(equipmentService.getActiveEffects(AGENT_DID)).isEmpty();
    }

    // --- 4. Proactivity + usage tracking integration ---

    @Test void proactivity_gated_by_vitality_and_usage() {
        var policy = new ProactivityPolicy(
            List.of("weather.*"), 0.4, 0.5, 3, Duration.ofMinutes(10));

        // Fresh vitality — should allow proactivity
        var vitality = VitalityState.initial();
        assertThat(policy.isActive(vitality.energy(), vitality.confidence())).isTrue();

        // Depleted energy — should NOT allow
        var depleted = vitality.withEnergy(0.3);
        assertThat(policy.isActive(depleted.energy(), depleted.confidence())).isFalse();

        // Record usages to fill the window
        usageTracker.record("weather.check", true, 100, "nexus");
        usageTracker.record("weather.check", true, 80, "nexus");
        usageTracker.record("weather.check", true, 90, "nexus");

        // Usage stats available
        assertThat(usageTracker.totalInvocations()).isEqualTo(3);
    }

    // --- 5. Self-assessment trigger from usage gaps ---

    @Test void assessment_triggers_on_gap_accumulation() {
        // Same gap 3 times reaches GAP_TRIGGER_THRESHOLD
        usageTracker.recordGap("calendar.check");
        usageTracker.recordGap("calendar.check");
        usageTracker.recordGap("calendar.check");

        // 3 occurrences of same gap should trigger assessment
        assertThat(SelfAssessor.shouldTrigger(usageTracker, null, false)).isTrue();

        // Build assessment prompt contains gap info
        var prompt = SelfAssessor.buildAssessmentPrompt(usageTracker);
        assertThat(prompt).contains("calendar.check");
    }

    // --- 6. Delegation chain with workbench skills ---

    @Test void delegation_chain_executes_through_workbench() {
        // Register a test skill
        var def = SkillItemCodec.create("graaljs",
            "function execute(p) { return p.city || 'world'; }",
            null, "Greet a city", null, null);
        var item = SkillItemCodec.toSoulItem("greet", def, AGENT_DID);
        workbenchExecutor.register("greet", item, def);

        var chainExec = DelegationChainExecutor.serverDefault(
            null, workbenchExecutor, usageTracker);

        var steps = List.of(
            new DelegationChainState.ChainStep("greet", Map.of("city", "Tokyo"), "Greet Tokyo"),
            new DelegationChainState.ChainStep("greet", Map.of("city", "Kyoto"), "Greet Kyoto")
        );

        var error = chainExec.startChain("Tour Japan", steps, 0.80);
        assertThat(error).isNull();

        // Execute both steps
        var out1 = chainExec.executeCurrentStep(AGENT_DID, "nexus", 0.75);
        assertThat(out1).isNotNull();
        assertThat(out1.stepResult().success()).isTrue();
        assertThat(out1.chainDone()).isFalse();

        var out2 = chainExec.executeCurrentStep(AGENT_DID, "nexus", 0.70);
        assertThat(out2).isNotNull();
        assertThat(out2.chainDone()).isTrue();

        // Usage was tracked
        assertThat(usageTracker.totalInvocations()).isEqualTo(2);
    }

    // --- 7. skills.sh import + prompt execution ---

    @Test void skills_sh_import_and_prompt_execution() {
        var skillMd = """
            ---
            name: summarize
            description: Summarize text concisely
            params:
              - name: text
                type: string
                required: true
            ---
            Summarize the following text in 2-3 sentences:
            {{text}}
            """;

        var format = SkillsMdImporter.parse(skillMd);
        assertThat(format).isNotNull();
        assertThat(format.name()).isEqualTo("summarize");

        var skillDef = SkillsMdImporter.toSkillDefinition(format);
        assertThat(skillDef).isNotNull();
        assertThat(skillDef.runtime()).isEqualTo("prompt");

        // Register and execute as prompt skill
        var executor = new PromptSkillExecutor();
        executor.register(format);
        var ctx = SkillContext.forAgent(AGENT_DID, "nexus", Map.of(), 1000);
        var result = executor.execute("summarize",
            Map.of("text", "A long article about AI safety and alignment research..."),
            ctx);
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("Summarize the following");
        assertThat(result.output()).contains("AI safety");
    }

    // --- 8. Collaborative crafting lifecycle ---

    @Test void craft_session_lifecycle_to_skill_creation() {
        var coordinator = new CraftCoordinator(locker);

        // Create session
        var session = coordinator.createSession("Build greeting tool", AGENT_DID);
        assertThat(session.status()).isEqualTo(CraftSession.SessionStatus.OPEN);

        // Contribute
        var contributed = coordinator.contribute(
            session.sessionId(), AGENT_DID, "implement",
            "function execute(p) { return 'Hello ' + p.name; }");
        assertThat(contributed.status()).isEqualTo(CraftSession.SessionStatus.IN_PROGRESS);

        // Submit for review
        var reviewed = coordinator.submitForReview(
            session.sessionId(),
            "function execute(p) { return 'Hello ' + p.name; }",
            AGENT_DID);
        assertThat(reviewed.status()).isEqualTo(CraftSession.SessionStatus.REVIEWING);

        // Complete → produces skill SoulItem
        var skillItem = coordinator.completeSession(
            session.sessionId(), "greeting-tool", AGENT_DID);
        assertThat(skillItem).isNotNull();
        assertThat(skillItem.category()).isEqualTo("skill");
        assertThat(skillItem.label()).contains("greeting-tool");

        // Stored in locker
        var skills = locker.byCategory("skill", AGENT_DID);
        assertThat(skills).anyMatch(i -> i.label().contains("greeting-tool"));
    }

    // --- 9. Room imprint tracking + threshold firing ---

    @Test void room_imprint_fires_at_threshold_and_modifies_epigenetics() {
        var tracker = new RoomImprintTracker();
        var modifier = new EpigeneticModifier();

        tracker.registerImprint("library",
            Map.of("curiosity", 0.3, "focus", 0.2),
            "scholarly research", 5);

        // Tick 4 times — should NOT fire
        for (int i = 0; i < 4; i++) {
            assertThat(tracker.tick(AGENT_DID, "library", modifier)).isFalse();
        }

        // 5th tick — should fire
        assertThat(tracker.tick(AGENT_DID, "library", modifier)).isTrue();

        // Room change resets counter
        tracker.tick(AGENT_DID, "nexus", modifier);
        assertThat(tracker.ticksInRoom(AGENT_DID)).isEqualTo(1);
        assertThat(tracker.currentRoom(AGENT_DID)).isEqualTo("nexus");
    }

    // --- 10. Full capability context with all v2 sections ---

    @Test void capability_context_includes_proactivity_and_assessment() {
        var policy = new ProactivityPolicy(
            List.of("weather.*"), 0.4, 0.5, 3, Duration.ofMinutes(10));
        var assessment = new SelfAssessment(
            "v2-test",
            Instant.now(),
            List.of(
                new SelfAssessment.SkillProficiency("weather-check", 0.9, 10),
                new SelfAssessment.SkillProficiency("room-creation", 0.7, 5)),
            List.of(new SelfAssessment.IdentifiedGap("calendar management", 3)),
            List.of(new SelfAssessment.GrowthGoal("Learn calendar APIs", "Import calendar MCP")),
            "Strong at weather and rooms, weak at calendar.");

        var ctx = CapabilityContextBuilder.build(
            AGENT_DID, locker, null, false, 0,
            VitalityState.initial(), null, null, true,
            policy, assessment);

        assertThat(ctx).isNotNull();
        // Proactivity section
        assertThat(ctx).contains("proactive");
        // Assessment section
        assertThat(ctx).contains("calendar management");
    }
}
