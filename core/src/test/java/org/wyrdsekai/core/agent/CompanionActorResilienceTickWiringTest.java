package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-final: verify the per-tick TankSnapshot capture wiring lives
 * inside CompanionActor.onVitalityTick and that the cycle-metadata
 * flags (pendingOverwhelmInput, pendingIntegrationEvent) are written
 * by the right event handlers. Source-text checking pattern (matches
 * {@code CompanionActorHwaByungWiringTest}) — avoids the cost of
 * spinning up the full actor for a wiring assertion.
 */
class CompanionActorResilienceTickWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    @Test
    void resilience_session_field_exists() throws Exception {
        var src = sourceText();
        assertThat(src)
            .as("CompanionActor must hold a ResilienceSession instance")
            .contains("ResilienceSession resilienceSession =")
            .contains("new ResilienceSession()");
    }

    @Test
    void tick_appends_snapshot_to_session() throws Exception {
        var src = sourceText();
        int tickStart = src.indexOf("private Behavior<Command> onVitalityTick(");
        assertThat(tickStart).isGreaterThan(0);
        int tickEnd = src.indexOf("private", tickStart + 100);
        var body = src.substring(tickStart, tickEnd > 0 ? tickEnd : src.length());

        assertThat(body)
            .as("onVitalityTick must build a TankSnapshot")
            .contains("ResilienceTruthMonitor.TankSnapshot")
            .contains("resilienceSession.append(");
    }

    @Test
    void snapshot_carries_substrate_truth_triad() throws Exception {
        var src = sourceText();
        int tickStart = src.indexOf("private Behavior<Command> onVitalityTick(");
        int tickEnd = src.indexOf("private", tickStart + 100);
        var body = src.substring(tickStart, tickEnd > 0 ? tickEnd : src.length());

        assertThat(body)
            .as("snapshot must feed the substrate-truth triad signals")
            .contains("vitality.soothing()")
            .contains("vitality.allostaticLoad()")
            .contains("vitality.equanimity()");
    }

    @Test
    void snapshot_carries_affect_tanks() throws Exception {
        var src = sourceText();
        int tickStart = src.indexOf("private Behavior<Command> onVitalityTick(");
        int tickEnd = src.indexOf("private", tickStart + 100);
        var body = src.substring(tickStart, tickEnd > 0 ? tickEnd : src.length());

        assertThat(body)
            .as("snapshot must feed all four affect tanks "
                + "(saudade + errorPressure + loneliness + integrityWounded)")
            .contains("vitality.saudade()")
            .contains("vitality.errorPressure()")
            .contains("vitality.loneliness()")
            // integrityWounded = max(0, 0.7 - integrity) per spec
            .contains("0.7 - vitality.integrity()");
    }

    @Test
    void tick_classifies_on_window_boundary() throws Exception {
        var src = sourceText();
        int tickStart = src.indexOf("private Behavior<Command> onVitalityTick(");
        int tickEnd = src.indexOf("private", tickStart + 100);
        var body = src.substring(tickStart, tickEnd > 0 ? tickEnd : src.length());

        assertThat(body)
            .as("tick must classify at window boundary")
            .contains("resilienceSession.classify()")
            .contains("ResilienceSession.DEFAULT_WINDOW");
    }

    @Test
    void tick_resets_pending_flags() throws Exception {
        var src = sourceText();
        int tickStart = src.indexOf("private Behavior<Command> onVitalityTick(");
        int tickEnd = src.indexOf("private", tickStart + 100);
        var body = src.substring(tickStart, tickEnd > 0 ? tickEnd : src.length());

        assertThat(body)
            .as("pending flags must reset each tick so they attribute "
                + "to the cycle they actually drove")
            .contains("pendingOverwhelmInput = false")
            .contains("pendingIntegrationEvent = false");
    }

    @Test
    void onChargeScored_flags_overwhelm_on_significant_charge() throws Exception {
        var src = sourceText();
        int handlerStart = src.indexOf(
            "private Behavior<Command> onChargeScored(ChargeScored msg)");
        assertThat(handlerStart).isGreaterThan(0);
        int handlerEnd = src.indexOf("\n    private", handlerStart + 100);
        var body = src.substring(handlerStart, handlerEnd > 0 ? handlerEnd : src.length());

        assertThat(body)
            .as("onChargeScored must flag overwhelm when isSignificant()")
            .contains("msg.charge().isSignificant()")
            .contains("pendingOverwhelmInput = true");
    }

    @Test
    void handleRecordIntegrationEvent_flags_integration() throws Exception {
        var src = sourceText();
        int handlerStart = src.indexOf("private void handleRecordIntegrationEvent(");
        assertThat(handlerStart).isGreaterThan(0);
        int handlerEnd = src.indexOf("\n    private", handlerStart + 100);
        var body = src.substring(handlerStart, handlerEnd > 0 ? handlerEnd : src.length());

        assertThat(body)
            .as("handleRecordIntegrationEvent must flag the next snapshot")
            .contains("pendingIntegrationEvent = true");
    }

    @Test
    void resilience_tick_counter_is_a_field() throws Exception {
        var src = sourceText();
        assertThat(src)
            .as("CompanionActor must track tick count for window-boundary classify")
            .contains("private int resilienceTickCounter");
    }
}
