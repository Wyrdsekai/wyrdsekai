package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.offset;

/**
 * Tests for ZoneAesthetic system (Item 6b).
 */
class ZoneAestheticTest {

    @Test
    void preset_library_loads_all_presets() {
        for (var name : ZoneAesthetic.presetNames()) {
            var preset = ZoneAesthetic.preset(name);
            assertThat(preset.name()).isEqualTo(name);
            assertThat(preset.stylePrompt()).isNotNull();
            assertThat(preset.stylePrompt()).isNotBlank();
        }
    }

    @Test
    void arcane_preset_has_expected_modifiers() {
        var arcane = ZoneAesthetic.arcane();
        assertThat(arcane.name()).isEqualTo("arcane");
        assertThat(arcane.costModifier("craft_item")).isEqualTo(0.7);
        assertThat(arcane.costModifier("web_search")).isEqualTo(1.5);
        // Unmodified action returns 1.0
        assertThat(arcane.costModifier("go_to_room")).isEqualTo(1.0);
    }

    @Test
    void sanctuary_restricts_actions() {
        var sanctuary = ZoneAesthetic.sanctuary();
        assertThat(sanctuary.isRestricted("cast_vote")).isTrue();
        assertThat(sanctuary.isRestricted("delegate_chain")).isTrue();
        assertThat(sanctuary.isRestricted("reflect")).isFalse();
    }

    @Test
    void zone_cost_modifier_changes_effective_cost() {
        var steampunk = ZoneAesthetic.steampunk();
        // Craft item is cheaper in steampunk (0.6x)
        double baseCost = 0.20;  // creation action base cost
        double effectiveCost = baseCost * steampunk.costModifier("craft_item");
        assertThat(effectiveCost).isCloseTo(0.12, offset(0.001));

        // Web search is more expensive (1.5x)
        double searchBase = 0.05;
        double searchEffective = searchBase * steampunk.costModifier("web_search");
        assertThat(searchEffective).isCloseTo(0.075, offset(0.001));
    }

    @Test
    void room_aesthetic_overrides_zone() {
        var zoneAesthetic = ZoneAesthetic.arcane();
        var roomAesthetic = new RoomAesthetic("holodeck-1", ZoneAesthetic.cyberpunk());

        // Without room override, zone applies
        var effective = RoomAesthetic.resolve(zoneAesthetic, null);
        assertThat(effective.name()).isEqualTo("arcane");

        // With room override, room applies
        var overridden = RoomAesthetic.resolve(zoneAesthetic, roomAesthetic);
        assertThat(overridden.name()).isEqualTo("cyberpunk");
    }

    @Test
    void zone_aesthetic_service_resolves_effective() {
        var service = new ZoneAestheticService();
        service.setZoneAesthetic(ZoneAesthetic.garden());

        // Zone aesthetic applies everywhere by default
        assertThat(service.effectiveAesthetic("room-1").name()).isEqualTo("garden");
        assertThat(service.effectiveAesthetic("room-2").name()).isEqualTo("garden");

        // Set room override
        service.setRoomAesthetic("room-2", ZoneAesthetic.wild());
        assertThat(service.effectiveAesthetic("room-1").name()).isEqualTo("garden");
        assertThat(service.effectiveAesthetic("room-2").name()).isEqualTo("wild");

        // Remove room override
        service.setRoomAesthetic("room-2", null);
        assertThat(service.effectiveAesthetic("room-2").name()).isEqualTo("garden");
    }

    @Test
    void zone_aesthetic_in_prompt_overlay() {
        var service = new ZoneAestheticService();
        service.setZoneAesthetic(ZoneAesthetic.arcane());

        var overlay = service.buildPromptOverlay("any-room");
        assertThat(overlay).contains("Zone Style");
        assertThat(overlay).contains("arcane scholar");
    }

    @Test
    void restricted_actions_from_service() {
        var service = new ZoneAestheticService();
        service.setZoneAesthetic(ZoneAesthetic.sanctuary());

        var restricted = service.restrictedActions("room-1");
        assertThat(restricted).contains("cast_vote");
    }

    @Test
    void cost_modifier_from_service() {
        var service = new ZoneAestheticService();
        service.setZoneAesthetic(ZoneAesthetic.cyberpunk());

        // Web search is cheap in cyberpunk (0.7x)
        assertThat(service.costModifier("room-1", "web_search")).isEqualTo(0.7);
        // Unmodified action
        assertThat(service.costModifier("room-1", "go_to_room")).isEqualTo(1.0);
    }

    @Test
    void none_aesthetic_has_no_effects() {
        var none = ZoneAesthetic.none();
        assertThat(none.stylePrompt()).isEmpty();
        assertThat(none.costModifier("anything")).isEqualTo(1.0);
        assertThat(none.isRestricted("anything")).isFalse();
        assertThat(none.translate("search")).isEqualTo("search");
    }

    @Test
    void lexicon_translates_terms() {
        var arcane = ZoneAesthetic.arcane();
        assertThat(arcane.translate("search")).isEqualTo("scry");
        assertThat(arcane.translate("create")).isEqualTo("conjure");
        assertThat(arcane.translate("unknown")).isEqualTo("unknown");
    }

    @Test
    void unknown_preset_returns_none() {
        var unknown = ZoneAesthetic.preset("nonexistent");
        assertThat(unknown.name()).isEqualTo("default");
    }

    @Test
    void describe_produces_readable_output() {
        var arcane = ZoneAesthetic.arcane();
        var desc = arcane.describe();
        assertThat(desc).contains("arcane");
        assertThat(desc).contains("Cost modifiers");
    }
}
