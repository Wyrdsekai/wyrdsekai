package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link EnvironmentalMood} — emotional vitality modulation from
 * external events (zone broadcasts, system events, adjacent activity, agent messages).
 */
class EnvironmentalMoodTest {

    private static final double DELTA = 0.001;

    private VitalityState baseline() {
        return new VitalityState(0.5, 0.5, 0.8, 0.5, 0.1, 0.3, 0.4, 0.5);
    }

    // --- Zone broadcast tests ---

    @Test
    void zone_broadcast_completed_boosts_momentum_and_confidence() {
        var base = baseline();
        var zb = zoneBroadcast("Training pipeline completed successfully");

        var result = EnvironmentalMood.applyZoneBroadcast(base, zb);

        assertThat(result.momentum()).isCloseTo(base.momentum() + 0.05, within(DELTA));
        assertThat(result.confidence()).isCloseTo(base.confidence() + 0.03, within(DELTA));
        // Other tanks unchanged
        assertThat(result.energy()).isCloseTo(base.energy(), within(DELTA));
        assertThat(result.errorPressure()).isCloseTo(base.errorPressure(), within(DELTA));
    }

    @Test
    void zone_broadcast_success_boosts_momentum_and_confidence() {
        var base = baseline();
        var zb = zoneBroadcast("Deployment success — model is live");

        var result = EnvironmentalMood.applyZoneBroadcast(base, zb);

        assertThat(result.momentum()).isCloseTo(base.momentum() + 0.05, within(DELTA));
        assertThat(result.confidence()).isCloseTo(base.confidence() + 0.03, within(DELTA));
    }

    @Test
    void zone_broadcast_failed_raises_error_pressure_and_focus() {
        var base = baseline();
        var zb = zoneBroadcast("Pipeline failed at stage 3");

        var result = EnvironmentalMood.applyZoneBroadcast(base, zb);

        assertThat(result.errorPressure()).isCloseTo(base.errorPressure() + 0.05, within(DELTA));
        assertThat(result.focus()).isCloseTo(base.focus() + 0.03, within(DELTA));
        assertThat(result.momentum()).isCloseTo(base.momentum(), within(DELTA));
    }

    @Test
    void zone_broadcast_error_raises_error_pressure_and_focus() {
        var base = baseline();
        var zb = zoneBroadcast("Critical error in inference backend");

        var result = EnvironmentalMood.applyZoneBroadcast(base, zb);

        assertThat(result.errorPressure()).isCloseTo(base.errorPressure() + 0.05, within(DELTA));
        assertThat(result.focus()).isCloseTo(base.focus() + 0.03, within(DELTA));
    }

    @Test
    void zone_broadcast_approval_needed_boosts_focus_drains_energy() {
        var base = baseline();
        var zb = zoneBroadcast("Approval needed for deployment");

        var result = EnvironmentalMood.applyZoneBroadcast(base, zb);

        assertThat(result.focus()).isCloseTo(base.focus() + 0.08, within(DELTA));
        assertThat(result.energy()).isCloseTo(base.energy() - 0.001, within(DELTA)); // day-scale recal 2026-07-18
    }

    @Test
    void zone_broadcast_critical_boosts_focus_drains_energy() {
        var base = baseline();
        var zb = zoneBroadcast("Critical alert: disk usage 95%");

        var result = EnvironmentalMood.applyZoneBroadcast(base, zb);

        assertThat(result.focus()).isCloseTo(base.focus() + 0.08, within(DELTA));
        assertThat(result.energy()).isCloseTo(base.energy() - 0.001, within(DELTA)); // day-scale recal 2026-07-18
    }

    @Test
    void zone_broadcast_routine_gives_slight_momentum() {
        var base = baseline();
        var zb = zoneBroadcast("Heartbeat from monitoring service");

        var result = EnvironmentalMood.applyZoneBroadcast(base, zb);

        assertThat(result.momentum()).isCloseTo(base.momentum() + 0.01, within(DELTA));
        // Everything else unchanged
        assertThat(result.confidence()).isCloseTo(base.confidence(), within(DELTA));
        assertThat(result.errorPressure()).isCloseTo(base.errorPressure(), within(DELTA));
        assertThat(result.focus()).isCloseTo(base.focus(), within(DELTA));
        assertThat(result.energy()).isCloseTo(base.energy(), within(DELTA));
    }

    // --- System event tests ---

    @Test
    void system_inference_backend_down_raises_error_pressure_lowers_confidence() {
        var base = baseline();
        var se = systemEvent(AgentEvent.SystemEventType.INFERENCE_BACKEND_DOWN);

        var result = EnvironmentalMood.applySystemEvent(base, se);

        assertThat(result.errorPressure()).isCloseTo(base.errorPressure() + 0.05, within(DELTA));
        assertThat(result.confidence()).isCloseTo(base.confidence() - 0.03, within(DELTA));
    }

    @Test
    void system_inference_backend_up_lowers_error_pressure_raises_confidence() {
        var base = baseline();
        var se = systemEvent(AgentEvent.SystemEventType.INFERENCE_BACKEND_UP);

        var result = EnvironmentalMood.applySystemEvent(base, se);

        assertThat(result.errorPressure()).isCloseTo(Math.max(0, base.errorPressure() - 0.03), within(DELTA));
        assertThat(result.confidence()).isCloseTo(base.confidence() + 0.02, within(DELTA));
    }

    @Test
    void system_node_left_raises_error_pressure() {
        var base = baseline();
        var se = systemEvent(AgentEvent.SystemEventType.NODE_LEFT);

        var result = EnvironmentalMood.applySystemEvent(base, se);

        assertThat(result.errorPressure()).isCloseTo(base.errorPressure() + 0.03, within(DELTA));
        // Only errorPressure changes for NODE_LEFT
        assertThat(result.confidence()).isCloseTo(base.confidence(), within(DELTA));
        assertThat(result.momentum()).isCloseTo(base.momentum(), within(DELTA));
    }

    @Test
    void system_node_joined_raises_momentum() {
        var base = baseline();
        var se = systemEvent(AgentEvent.SystemEventType.NODE_JOINED);

        var result = EnvironmentalMood.applySystemEvent(base, se);

        assertThat(result.momentum()).isCloseTo(base.momentum() + 0.02, within(DELTA));
        assertThat(result.errorPressure()).isCloseTo(base.errorPressure(), within(DELTA));
    }

    @Test
    void system_health_alert_raises_error_pressure_and_focus() {
        var base = baseline();
        var se = systemEvent(AgentEvent.SystemEventType.HEALTH_ALERT);

        var result = EnvironmentalMood.applySystemEvent(base, se);

        assertThat(result.errorPressure()).isCloseTo(base.errorPressure() + 0.08, within(DELTA));
        assertThat(result.focus()).isCloseTo(base.focus() + 0.05, within(DELTA));
    }

    @Test
    void system_zone_service_registered_raises_momentum() {
        var base = baseline();
        var se = systemEvent(AgentEvent.SystemEventType.ZONE_SERVICE_REGISTERED);

        var result = EnvironmentalMood.applySystemEvent(base, se);

        assertThat(result.momentum()).isCloseTo(base.momentum() + 0.02, within(DELTA));
    }

    @Test
    void system_zone_service_disconnected_raises_error_pressure() {
        var base = baseline();
        var se = systemEvent(AgentEvent.SystemEventType.ZONE_SERVICE_DISCONNECTED);

        var result = EnvironmentalMood.applySystemEvent(base, se);

        assertThat(result.errorPressure()).isCloseTo(base.errorPressure() + 0.02, within(DELTA));
    }

    // --- Adjacent activity tests ---

    @Test
    void adjacent_speech_gives_slight_momentum() {
        var base = baseline();
        var aa = adjacentActivity(AgentEvent.ActivityType.SPEECH);

        var result = EnvironmentalMood.applyAdjacentActivity(base, aa);

        assertThat(result.momentum()).isCloseTo(base.momentum() + 0.01, within(DELTA));
    }

    @Test
    void adjacent_entity_entered_gives_slight_momentum() {
        var base = baseline();
        var aa = adjacentActivity(AgentEvent.ActivityType.ENTITY_ENTERED);

        var result = EnvironmentalMood.applyAdjacentActivity(base, aa);

        assertThat(result.momentum()).isCloseTo(base.momentum() + 0.01, within(DELTA));
    }

    @Test
    void adjacent_entity_left_is_neutral() {
        var base = baseline();
        var aa = adjacentActivity(AgentEvent.ActivityType.ENTITY_LEFT);

        var result = EnvironmentalMood.applyAdjacentActivity(base, aa);

        // Exactly unchanged — neutral event
        assertThat(result).isEqualTo(base);
    }

    @Test
    void adjacent_object_interaction_gives_tiny_momentum() {
        var base = baseline();
        var aa = adjacentActivity(AgentEvent.ActivityType.OBJECT_INTERACTION);

        var result = EnvironmentalMood.applyAdjacentActivity(base, aa);

        assertThat(result.momentum()).isCloseTo(base.momentum() + 0.005, within(DELTA));
    }

    @Test
    void adjacent_script_triggered_gives_focus() {
        var base = baseline();
        var aa = adjacentActivity(AgentEvent.ActivityType.SCRIPT_TRIGGERED);

        var result = EnvironmentalMood.applyAdjacentActivity(base, aa);

        assertThat(result.focus()).isCloseTo(base.focus() + 0.01, within(DELTA));
    }

    // --- Agent message tests ---

    @Test
    void agent_message_boosts_rapport_and_focus() {
        var base = baseline();
        var msg = new AgentEvent.AgentMessage(
            "agent-other", "Luna", "agent-self", "How are you?", Instant.now());

        var result = EnvironmentalMood.applyAgentMessage(base, msg);

        assertThat(result.rapport()).isCloseTo(base.rapport() + 0.04, within(DELTA));
        assertThat(result.focus()).isCloseTo(base.focus() + 0.05, within(DELTA));
        // Other tanks unchanged
        assertThat(result.energy()).isCloseTo(base.energy(), within(DELTA));
        assertThat(result.momentum()).isCloseTo(base.momentum(), within(DELTA));
    }

    // --- Clamping tests ---

    @Test
    void values_clamp_to_one_on_overflow() {
        // Start with high momentum and confidence near cap
        var high = new VitalityState(0.5, 0.99, 0.8, 0.5, 0.1, 0.98, 0.4, 0.5);
        var zb = zoneBroadcast("Training completed");

        var result = EnvironmentalMood.applyZoneBroadcast(high, zb);

        assertThat(result.momentum()).isEqualTo(1.0);
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test
    void error_pressure_clamps_to_zero_on_underflow() {
        // Start with very low error pressure
        var low = new VitalityState(0.5, 0.5, 0.8, 0.5, 0.01, 0.3, 0.4, 0.5);
        var se = systemEvent(AgentEvent.SystemEventType.INFERENCE_BACKEND_UP);

        var result = EnvironmentalMood.applySystemEvent(low, se);

        assertThat(result.errorPressure()).isGreaterThanOrEqualTo(0.0);
    }

    // --- extractText tests ---

    @Test
    void extractText_from_prose() {
        var prose = new S2CMessage.Prose(0, "sys", "hello world",
            List.of(), null, "normal");
        assertThat(EnvironmentalMood.extractText(prose)).isEqualTo("hello world");
    }

    @Test
    void extractText_from_notification() {
        var notif = new S2CMessage.Notification(0, "info", "title", "check this out");
        assertThat(EnvironmentalMood.extractText(notif)).isEqualTo("check this out");
    }

    @Test
    void extractText_from_unknown_returns_empty() {
        var transit = new S2CMessage.Transit(0, "zone-1", "ws://foo", "token", "bye");
        assertThat(EnvironmentalMood.extractText(transit)).isEmpty();
    }

    // --- Helpers ---

    private AgentEvent.ZoneBroadcast zoneBroadcast(String text) {
        return new AgentEvent.ZoneBroadcast(
            "codezaiku", "workshop",
            new S2CMessage.Prose(0, "system", text, List.of(), null, "normal"),
            Instant.now());
    }

    private AgentEvent.SystemEvent systemEvent(AgentEvent.SystemEventType type) {
        return new AgentEvent.SystemEvent(type, "test-source", "test detail", Instant.now());
    }

    private AgentEvent.AdjacentActivity adjacentActivity(AgentEvent.ActivityType type) {
        return new AgentEvent.AdjacentActivity(
            "room-east", "The Garden", type, 3, Instant.now());
    }
}
