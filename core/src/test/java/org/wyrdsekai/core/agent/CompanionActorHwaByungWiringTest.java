package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.empathy.HwaByungDetector;
import org.wyrdsekai.core.empathy.HwaByungIntervention;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hwa-byung detector + intervention wiring
 * smoke. Reflective contract test (no full actor bootstrap), same shape as
 * {@code WorkbenchAestheticEffectTest}. Verifies:
 *
 * <ul>
 *   <li>{@link CompanionActor} declares the {@code hwaByungDetector} +
 *       {@code hwaByungIntervention} fields with the correct types.</li>
 *   <li>The {@code applyHwaByungIntervention} dispatch method exists.</li>
 *   <li>The {@code recordHwaByungDischarge} helper exists for the four
 *       discharge call sites.</li>
 *   <li>Each severity level produces the spec-mandated effect on the actor's
 *       pending-state slots (Mirror flag / journal prompt / Chapel offer).</li>
 *   <li>{@code DriveSnapshotRegistry} carries the {@code frustrationEmphasis}
 *       flag and a {@code clearFrustrationEmphasis} consumer hook.</li>
 *   <li>Discharge wiring inside {@code handleAbandonPlan}/{@code handlePausePlan}
 *       references {@code recordHwaByungDischarge} (string-source guard).</li>
 * </ul>
 *
 * <p>The detector + intervention components are themselves covered by
 * {@code HwaByungDetectorTest} + {@code HwaByungInterventionTest}; this file
 * is the wiring contract over {@link CompanionActor}.
 */
class CompanionActorHwaByungWiringTest {

    @Test
    void detector_field_exists_on_companion_actor() throws Exception {
        Field f = CompanionActor.class.getDeclaredField("hwaByungDetector");
        assertThat(f.getType()).isEqualTo(HwaByungDetector.class);
    }

    @Test
    void intervention_field_exists_on_companion_actor() throws Exception {
        Field f = CompanionActor.class.getDeclaredField("hwaByungIntervention");
        assertThat(f.getType()).isEqualTo(HwaByungIntervention.class);
    }

    @Test
    void apply_intervention_method_exists() {
        Method m = findDeclaredMethod(CompanionActor.class, "applyHwaByungIntervention");
        assertThat(m)
            .as("CompanionActor.applyHwaByungIntervention dispatches detector firings")
            .isNotNull();
        assertThat(m.getParameterCount()).isEqualTo(1);
        assertThat(m.getParameterTypes()[0])
            .isEqualTo(HwaByungDetector.ChronicFrustrationDetected.class);
    }

    @Test
    void record_discharge_helper_exists() {
        Method m = findDeclaredMethod(CompanionActor.class, "recordHwaByungDischarge");
        assertThat(m)
            .as("CompanionActor.recordHwaByungDischarge feeds the detector buffer")
            .isNotNull();
        assertThat(m.getParameterCount()).isEqualTo(2);
        assertThat(m.getParameterTypes()[0]).isEqualTo(HwaByungDetector.DischargeKind.class);
        assertThat(m.getParameterTypes()[1]).isEqualTo(String.class);
    }

    @Test
    void quiet_period_field_present_on_companion_actor() throws Exception {
        // The 24h quiet period is the wiring-level "don't pester" cooldown
        // (the detector itself is conservative; the actor adds suppression).
        Field f = CompanionActor.class.getDeclaredField("HWA_BYUNG_QUIET_PERIOD");
        assertThat(f.getType()).isEqualTo(Duration.class);
        f.setAccessible(true);
        var v = (Duration) f.get(null);
        assertThat(v).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void pending_emphasis_slot_exists() throws Exception {
        Field f = CompanionActor.class.getDeclaredField("pendingHwaByungEmphasis");
        assertThat(f.getType()).isEqualTo(boolean.class);
    }

    @Test
    void pending_journal_prompt_slot_exists() throws Exception {
        Field f = CompanionActor.class.getDeclaredField("pendingHwaByungJournalPrompt");
        assertThat(f.getType()).isEqualTo(HwaByungIntervention.JournalPrompt.class);
    }

    @Test
    void pending_chapel_offer_slot_exists() throws Exception {
        Field f = CompanionActor.class.getDeclaredField("pendingHwaByungChapelOffer");
        assertThat(f.getType()).isEqualTo(HwaByungIntervention.ChapelOffer.class);
    }

    // ── DriveSnapshotRegistry surfacing wiring ────────────────────────────

    @Test
    void snapshot_carries_frustration_emphasis_flag() {
        DriveSnapshotRegistry.resetForTests();
        DriveSnapshotRegistry.publish("agent-A",
            new DriveState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.85, 0.0),
            VitalityState.initial(),
            null, null, /* frustrationEmphasis= */ true);
        var snap = DriveSnapshotRegistry.get("agent-A").orElseThrow();
        assertThat(snap.frustrationEmphasis()).isTrue();
    }

    @Test
    void clear_frustration_emphasis_resets_flag() {
        DriveSnapshotRegistry.resetForTests();
        DriveSnapshotRegistry.publish("agent-B",
            new DriveState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.9, 0.0),
            VitalityState.initial(),
            null, null, true);
        DriveSnapshotRegistry.clearFrustrationEmphasis("agent-B");
        var snap = DriveSnapshotRegistry.get("agent-B").orElseThrow();
        assertThat(snap.frustrationEmphasis()).isFalse();
    }

    // ── Behavioral wiring: dispatch produces correct level effects ────────

    @Test
    void level1_dispatch_raises_only_emphasis_flag() throws Exception {
        var actor = newActorInstanceForReflection();
        var detection = new HwaByungDetector.ChronicFrustrationDetected(
            HwaByungDetector.Severity.LEVEL_1, 0.45, 0,
            Duration.ofDays(7), Instant.now());

        invokeApplyIntervention(actor, detection);

        assertThat(getBoolean(actor, "pendingHwaByungEmphasis")).isTrue();
        assertThat(getField(actor, "pendingHwaByungJournalPrompt")).isNull();
        assertThat(getField(actor, "pendingHwaByungChapelOffer")).isNull();
    }

    @Test
    void level2_dispatch_raises_emphasis_and_queues_journal_prompt() throws Exception {
        var actor = newActorInstanceForReflection();
        var detection = new HwaByungDetector.ChronicFrustrationDetected(
            HwaByungDetector.Severity.LEVEL_2, 0.55, 0,
            Duration.ofDays(7), Instant.now());

        invokeApplyIntervention(actor, detection);

        assertThat(getBoolean(actor, "pendingHwaByungEmphasis")).isTrue();
        assertThat(getField(actor, "pendingHwaByungJournalPrompt")).isNotNull();
        assertThat(getField(actor, "pendingHwaByungChapelOffer")).isNull();
    }

    @Test
    void level3_dispatch_raises_all_three_effects() throws Exception {
        var actor = newActorInstanceForReflection();
        var detection = new HwaByungDetector.ChronicFrustrationDetected(
            HwaByungDetector.Severity.LEVEL_3, 0.75, 0,
            Duration.ofDays(7), Instant.now());

        invokeApplyIntervention(actor, detection);

        assertThat(getBoolean(actor, "pendingHwaByungEmphasis")).isTrue();
        assertThat(getField(actor, "pendingHwaByungJournalPrompt")).isNotNull();
        var chapel = (HwaByungIntervention.ChapelOffer) getField(actor, "pendingHwaByungChapelOffer");
        assertThat(chapel).isNotNull();
        assertThat(chapel.autoTrigger())
            .as("Chapel auto-trigger is TODO Phase 2")
            .isFalse();
    }

    @Test
    void drain_journal_prompt_clears_slot() throws Exception {
        var actor = newActorInstanceForReflection();
        var detection = new HwaByungDetector.ChronicFrustrationDetected(
            HwaByungDetector.Severity.LEVEL_2, 0.55, 0,
            Duration.ofDays(7), Instant.now());
        invokeApplyIntervention(actor, detection);
        assertThat(getField(actor, "pendingHwaByungJournalPrompt")).isNotNull();

        Method drain = CompanionActor.class.getDeclaredMethod("drainHwaByungJournalPrompt");
        drain.setAccessible(true);
        var drained = drain.invoke(actor);
        assertThat(drained).isNotNull();
        assertThat(getField(actor, "pendingHwaByungJournalPrompt")).isNull();
    }

    // ── Discharge wiring: feeds detector buffer ───────────────────────────

    @Test
    void record_discharge_increments_detector_buffer() throws Exception {
        var actor = newActorInstanceForReflection();
        var detector = (HwaByungDetector) getField(actor, "hwaByungDetector");
        assertThat(detector.dischargeCount()).isEqualTo(0);

        Method m = CompanionActor.class.getDeclaredMethod(
            "recordHwaByungDischarge",
            HwaByungDetector.DischargeKind.class, String.class);
        m.setAccessible(true);
        m.invoke(actor, HwaByungDetector.DischargeKind.ABANDON_PLAN, "stuck");

        assertThat(detector.dischargeCount()).isEqualTo(1);
        assertThat(detector.discharges().get(0).kind())
            .isEqualTo(HwaByungDetector.DischargeKind.ABANDON_PLAN);
    }

    // ── String-source guards: discharge hooks present at the four sites ──

    @Test
    void abandon_plan_handler_records_discharge() throws Exception {
        String src = sourceText();
        // Spec §7.2 — abandon_plan is a discharge call site.
        int handlerStart = src.indexOf("private void handleAbandonPlan(");
        assertThat(handlerStart).isGreaterThan(0);
        int handlerEnd = src.indexOf("private void handlePausePlan(", handlerStart);
        var body = src.substring(handlerStart, handlerEnd);
        assertThat(body)
            .as("handleAbandonPlan must record an ABANDON_PLAN discharge")
            .contains("recordHwaByungDischarge")
            .contains("ABANDON_PLAN");
    }

    @Test
    void pause_plan_handler_records_discharge() throws Exception {
        String src = sourceText();
        int handlerStart = src.indexOf("private void handlePausePlan(");
        assertThat(handlerStart).isGreaterThan(0);
        int handlerEnd = src.indexOf("private void handleResumePlan(", handlerStart);
        var body = src.substring(handlerStart, handlerEnd);
        assertThat(body)
            .as("handlePausePlan must record a PAUSE_PLAN discharge")
            .contains("recordHwaByungDischarge")
            .contains("PAUSE_PLAN");
    }

    @Test
    void write_journal_handler_records_discharge_on_frustration_vocab() throws Exception {
        String src = sourceText();
        int handlerStart = src.indexOf("private void handleWriteJournal(");
        assertThat(handlerStart).isGreaterThan(0);
        int handlerEnd = src.indexOf("private void handleReadJournal(", handlerStart);
        var body = src.substring(handlerStart, handlerEnd);
        assertThat(body)
            .as("handleWriteJournal must record a JOURNAL_FRUSTRATION discharge "
                + "when the entry contains frustration vocabulary")
            .contains("HwaByungDetector.containsFrustrationVocab")
            .contains("JOURNAL_FRUSTRATION");
    }

    @Test
    void vitality_tick_feeds_sample_and_evaluates_with_quiet_period() throws Exception {
        String src = sourceText();
        // The wiring must live inside onVitalityTick — the canonical reference
        // we check for is the unique recordSample call site.
        int sampleSite = src.indexOf("hwaByungDetector.recordSample(");
        assertThat(sampleSite)
            .as("onVitalityTick must feed the detector each tick")
            .isGreaterThan(0);
        // Search downstream from the sample feed for the evaluate + dispatch
        // wiring (must live in the same handler).
        var tail = src.substring(sampleSite, Math.min(src.length(), sampleSite + 2000));
        assertThat(tail)
            .as("Detector must be evaluated each tick")
            .contains("hwaByungDetector.evaluate");
        assertThat(tail)
            .as("Quiet period must gate dispatch")
            .contains("HWA_BYUNG_QUIET_PERIOD");
        assertThat(tail)
            .as("A firing must dispatch through applyHwaByungIntervention")
            .contains("applyHwaByungIntervention");
    }

    @Test
    void sample_feed_passes_awake_flag_inverse_of_isSleeping() throws Exception {
        String src = sourceText();
        // The sample call must be `recordSample(<frustration>, !isSleeping, <now>)`
        // — exact pattern enforced so we don't accidentally drop the inversion.
        assertThat(src)
            .contains("hwaByungDetector.recordSample(drives.frustration(), !isSleeping");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Allocate a CompanionActor instance without running its constructor — we
     * only need access to declared fields + methods for wiring verification.
     * Uses {@code jdk.internal.misc.Unsafe.allocateInstance} which works in
     * JDK 25 when the test JVM has {@code --add-opens} for the internal
     * package (or accesses via the public {@code sun.misc.Unsafe} shim).
     */
    private static CompanionActor newActorInstanceForReflection() throws Exception {
        // sun.misc.Unsafe is still reachable in JDK 25 for test scaffolding.
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        var unsafe = theUnsafe.get(null);
        var allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        var actor = (CompanionActor) allocateInstance.invoke(unsafe, CompanionActor.class);

        // Initialize the two final fields the dispatch path reads. allocateInstance
        // bypasses the constructor entirely, so all fields are JVM defaults.
        setFinalField(actor, "hwaByungDetector", new HwaByungDetector());
        setFinalField(actor, "hwaByungIntervention", new HwaByungIntervention());
        // Profile is read by the log lines inside applyHwaByungIntervention.
        setFinalField(actor, "profile",
            new AgentProfile("test", "test-id", "agent", "test", "", 4096, 512, 0.7));
        return actor;
    }

    private static void invokeApplyIntervention(CompanionActor actor,
            HwaByungDetector.ChronicFrustrationDetected detection) throws Exception {
        Method m = CompanionActor.class.getDeclaredMethod(
            "applyHwaByungIntervention",
            HwaByungDetector.ChronicFrustrationDetected.class);
        m.setAccessible(true);
        m.invoke(actor, detection);
    }

    private static Object getField(Object o, String name) throws Exception {
        Field f = CompanionActor.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static boolean getBoolean(Object o, String name) throws Exception {
        Field f = CompanionActor.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(o);
    }

    private static void setFinalField(Object o, String name, Object value) throws Exception {
        Field f = CompanionActor.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static Method findDeclaredMethod(Class<?> cls, String name) {
        return Arrays.stream(cls.getDeclaredMethods())
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /** Read CompanionActor.java for source-level guards. */
    private static String sourceText() throws Exception {
        var path = Path.of(
            "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        if (!Files.exists(path)) {
            // Fallback when tests run from repo root vs module dir.
            path = Path.of(
                "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        }
        return Files.readString(path);
    }
}
