package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EquipmentServiceTest {

    private EquipmentService service;

    @BeforeEach
    void setUp() {
        service = new EquipmentService();
    }

    private SoulItem makeAspect(String label, String promptOverlay,
                                  Map<String, Double> shifts, String slot) {
        var def = AspectItemCodec.create(promptOverlay, shifts,
            "wearing " + label.toLowerCase(), slot, 30);
        return AspectItemCodec.toSoulItem(label, def, "did:agent:1", 0.4);
    }

    private SoulItem makeReagent(String label, Map<String, Double> effects,
                                   int duration, boolean consumable) {
        var def = ReagentItemCodec.create(effects, duration,
            label + " effect active.", consumable, 12);
        return ReagentItemCodec.toSoulItem(label, def, "did:agent:1", 0.2);
    }

    // --- Equip/Doff ---

    @Nested
    class EquipDoffTests {

        @Test
        void equip_valid_aspect() {
            var item = makeAspect("Focused Mode", "Focus overlay",
                Map.of("focus", 0.15), "garment");
            assertThat(service.equip("agent-1", item)).isTrue();
            assertThat(service.getEquipped("agent-1")).hasSize(1);
            assertThat(service.getEquipped("agent-1").getFirst().label()).isEqualTo("Focused Mode");
        }

        @Test
        void equip_non_aspect_fails() {
            var item = SoulItem.create("skill", "test", "{}", "did:x", 0.5);
            assertThat(service.equip("agent-1", item)).isFalse();
        }

        @Test
        void equip_null_fails() {
            assertThat(service.equip("agent-1", null)).isFalse();
        }

        @Test
        void equip_duplicate_fails() {
            var item = makeAspect("Focused Mode", "Overlay", Map.of(), "garment");
            service.equip("agent-1", item);
            assertThat(service.equip("agent-1", item)).isFalse();
            assertThat(service.getEquipped("agent-1")).hasSize(1);
        }

        @Test
        void equip_respects_max_limit() {
            for (int i = 0; i < EquipmentService.MAX_EQUIPPED_ASPECTS; i++) {
                var item = makeAspect("Aspect " + i, "Overlay " + i, Map.of(), "garment");
                assertThat(service.equip("agent-1", item)).isTrue();
            }
            var extra = makeAspect("Extra", "Too many", Map.of(), "garment");
            assertThat(service.equip("agent-1", extra)).isFalse();
            assertThat(service.getEquipped("agent-1")).hasSize(EquipmentService.MAX_EQUIPPED_ASPECTS);
        }

        @Test
        void doff_by_hash() {
            var item = makeAspect("Focused Mode", "Overlay", Map.of(), "garment");
            service.equip("agent-1", item);
            assertThat(service.doff("agent-1", item.hash())).isTrue();
            assertThat(service.getEquipped("agent-1")).isEmpty();
        }

        @Test
        void doff_by_label() {
            var item = makeAspect("Focused Mode", "Overlay", Map.of(), "garment");
            service.equip("agent-1", item);
            assertThat(service.doffByLabel("agent-1", "focused mode")).isTrue();
            assertThat(service.getEquipped("agent-1")).isEmpty();
        }

        @Test
        void doff_nonexistent_returns_false() {
            assertThat(service.doff("agent-1", "nonexistent")).isFalse();
        }

        @Test
        void is_equipped() {
            var item = makeAspect("Focused Mode", "Overlay", Map.of(), "garment");
            service.equip("agent-1", item);
            assertThat(service.isEquipped("agent-1", item.hash())).isTrue();
            assertThat(service.isEquippedByLabel("agent-1", "Focused Mode")).isTrue();
            assertThat(service.isEquippedByLabel("agent-1", "focused mode")).isTrue();
            assertThat(service.isEquippedByLabel("agent-1", "Social Mode")).isFalse();
        }
    }

    // --- Consume ---

    @Nested
    class ConsumeTests {

        @Test
        void consume_valid_reagent() {
            var item = makeReagent("Restoring Draught",
                Map.of("energy", 0.2), 600, true);
            var effect = service.consume("agent-1", item);

            assertThat(effect).isNotNull();
            assertThat(effect.label()).isEqualTo("Restoring Draught");
            assertThat(effect.remainingTicks()).isEqualTo(600);
            assertThat(service.getActiveEffects("agent-1")).hasSize(1);
        }

        @Test
        void consume_non_reagent_fails() {
            var item = SoulItem.create("skill", "test", "{}", "did:x", 0.5);
            assertThat(service.consume("agent-1", item)).isNull();
        }

        @Test
        void consume_respects_max_active_effects() {
            for (int i = 0; i < EquipmentService.MAX_ACTIVE_EFFECTS; i++) {
                var item = makeReagent("Reagent " + i, Map.of("energy", 0.1), 300, true);
                assertThat(service.consume("agent-1", item)).isNotNull();
            }
            var extra = makeReagent("Extra", Map.of("energy", 0.1), 300, true);
            assertThat(service.consume("agent-1", extra)).isNull();
        }
    }

    // --- Tick ---

    @Nested
    class TickTests {

        @Test
        void tick_decrements_duration() {
            var item = makeReagent("Tonic", Map.of("focus", 0.3), 5, true);
            service.consume("agent-1", item);

            service.tick("agent-1");
            assertThat(service.getActiveEffects("agent-1").getFirst().remainingTicks()).isEqualTo(4);
        }

        @Test
        void tick_expires_effect() {
            var item = makeReagent("Tonic", Map.of("focus", 0.3), 2, true);
            service.consume("agent-1", item);

            service.tick("agent-1"); // 1 remaining
            assertThat(service.getActiveEffects("agent-1")).hasSize(1);

            var expired = service.tick("agent-1"); // 0 remaining → expired
            assertThat(expired).hasSize(1);
            assertThat(expired.getFirst().label()).isEqualTo("Tonic");
            assertThat(service.getActiveEffects("agent-1")).isEmpty();
        }

        @Test
        void tick_with_no_effects() {
            var expired = service.tick("agent-1");
            assertThat(expired).isEmpty();
        }
    }

    // --- Vitality Shifts ---

    @Nested
    class VitalityShiftTests {

        @Test
        void single_aspect_shifts() {
            var item = makeAspect("Focused", "Overlay",
                Map.of("focus", 0.15, "rapport", -0.05), "garment");
            service.equip("agent-1", item);

            var shifts = service.computeVitalityShifts("agent-1");
            assertThat(shifts).containsEntry("focus", 0.15);
            assertThat(shifts).containsEntry("rapport", -0.05);
        }

        @Test
        void multiple_aspects_stack() {
            var a1 = makeAspect("A", "Overlay A", Map.of("focus", 0.1), "garment");
            var a2 = makeAspect("B", "Overlay B", Map.of("focus", 0.05, "rapport", 0.1), "accessory");
            service.equip("agent-1", a1);
            service.equip("agent-1", a2);

            var shifts = service.computeVitalityShifts("agent-1");
            assertThat(shifts.get("focus")).isCloseTo(0.15, org.assertj.core.data.Offset.offset(0.001));
            assertThat(shifts.get("rapport")).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        void no_equipped_returns_empty() {
            assertThat(service.computeVitalityShifts("agent-1")).isEmpty();
        }
    }

    // --- Prompt Context ---

    @Nested
    class PromptContextTests {

        @Test
        void empty_returns_null() {
            assertThat(service.buildPromptContext("agent-1")).isNull();
        }

        @Test
        void equipped_aspect_in_context() {
            var item = makeAspect("Focused Mode", "You are methodical.",
                Map.of("focus", 0.15), "garment");
            service.equip("agent-1", item);

            var ctx = service.buildPromptContext("agent-1");
            assertThat(ctx).contains("Wearing: Focused Mode");
            assertThat(ctx).contains("You are methodical.");
            assertThat(ctx).contains("wearing focused mode"); // selfDescription
        }

        @Test
        void active_effect_in_context() {
            var item = makeReagent("Clarity Tonic",
                Map.of("focus", 0.3), 300, true);
            service.consume("agent-1", item);

            var ctx = service.buildPromptContext("agent-1");
            assertThat(ctx).contains("Active: Clarity Tonic");
            assertThat(ctx).contains("remaining");
        }

        @Test
        void budget_limits_context() {
            // Create aspects with large token estimates
            for (int i = 0; i < 3; i++) {
                var item = makeAspect("Aspect " + i, "Long overlay text for aspect " + i,
                    Map.of(), "garment");
                service.equip("agent-1", item);
            }

            // Very small budget
            var ctx = service.buildPromptContext("agent-1", 40);
            assertThat(ctx).isNotNull();
            // Should have at most 1 overlay (30 tokens each + 8 header = 38 max before second)
            long overlayCount = ctx.lines()
                .filter(l -> l.contains("[Wearing:"))
                .count();
            assertThat(overlayCount).isLessThanOrEqualTo(2);
        }

        @Test
        void appearance_aggregates_descriptions() {
            var a1 = makeAspect("Robe", null, Map.of(), "garment");
            var a2 = makeAspect("Glasses", null, Map.of(), "accessory");
            service.equip("agent-1", a1);
            service.equip("agent-1", a2);

            var ctx = service.buildPromptContext("agent-1");
            assertThat(ctx).contains("[Appearance:");
            assertThat(ctx).contains("wearing robe");
            assertThat(ctx).contains("wearing glasses");
        }
    }

    // --- Equipment State ---

    @Nested
    class StateTests {

        @Test
        void full_state() {
            var aspect = makeAspect("Robe", "Overlay", Map.of(), "garment");
            var reagent = makeReagent("Tonic", Map.of("energy", 0.1), 300, true);
            service.equip("agent-1", aspect);
            service.consume("agent-1", reagent);

            var state = service.getState("agent-1");
            assertThat(state.agentId()).isEqualTo("agent-1");
            assertThat(state.equipped()).hasSize(1);
            assertThat(state.activeEffects()).hasSize(1);
        }

        @Test
        void empty_state() {
            var state = service.getState("agent-1");
            assertThat(state.equipped()).isEmpty();
            assertThat(state.activeEffects()).isEmpty();
        }
    }
}
