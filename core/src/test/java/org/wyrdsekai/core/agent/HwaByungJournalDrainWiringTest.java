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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sleep-cycle journal-write phase consumes
 * the queued Level-2 Hwa-byung prompt.
 *
 * <p>The dispatch path stores a {@code JournalPrompt} on the actor's
 * pending slot when a Level-2+ chronic-frustration detection fires. The
 * sleep-cycle journal-write phase inside {@code completeSleep} must drain
 * that prompt and prepend it as a leading prompt to the HearthJournal entry
 * that the companion writes for the sleep cycle.</p>
 *
 * <p>Reflective contract test (no full actor bootstrap), same shape as
 * {@link CompanionActorHwaByungWiringTest}. Verifies:
 *
 * <ul>
 *   <li>{@code drainHwaByungJournalPrompt()} returns the queued prompt then
 *       clears the slot — subsequent drains return null.</li>
 *   <li>When no prompt is queued, the drain returns null without throwing
 *       (so journal-write proceeds normally on uneventful sleep cycles).</li>
 *   <li>The {@code completeSleep} body wires {@code drainHwaByungJournalPrompt}
 *       and writes through {@code getHearthJournal()} — the canonical
 *       sleep-cycle journal-write surface.</li>
 *   <li>The drain call sits inside the body of {@code completeSleep}
 *       (not somewhere unrelated).</li>
 * </ul>
 */
class HwaByungJournalDrainWiringTest {

    @Test void drain_after_level2_dispatch_returns_prompt_and_clears_slot() throws Exception {
        var actor = newActorInstanceForReflection();
        var detection = new HwaByungDetector.ChronicFrustrationDetected(
            HwaByungDetector.Severity.LEVEL_2, 0.55, 0,
            Duration.ofDays(7), Instant.now());

        invokeApplyIntervention(actor, detection);
        // Slot is now populated.
        assertThat(getField(actor, "pendingHwaByungJournalPrompt")).isNotNull();

        var first = invokeDrain(actor);
        assertThat(first)
            .as("first drain returns the queued prompt")
            .isNotNull();
        assertThat(((HwaByungIntervention.JournalPrompt) first).severity())
            .isEqualTo(HwaByungDetector.Severity.LEVEL_2);
        assertThat(((HwaByungIntervention.JournalPrompt) first).promptText())
            .isNotBlank();

        // Slot cleared.
        assertThat(getField(actor, "pendingHwaByungJournalPrompt"))
            .as("drain clears the slot")
            .isNull();

        // Subsequent drain returns null — single-slot semantics.
        var second = invokeDrain(actor);
        assertThat(second)
            .as("second drain returns null — slot was cleared")
            .isNull();
    }

    @Test void drain_with_nothing_queued_returns_null_without_throwing() throws Exception {
        var actor = newActorInstanceForReflection();
        // Nothing queued — drain is a no-op.
        var drained = invokeDrain(actor);
        assertThat(drained).isNull();
        assertThat(getField(actor, "pendingHwaByungJournalPrompt")).isNull();
    }

    @Test void level3_dispatch_also_queues_journal_prompt() throws Exception {
        var actor = newActorInstanceForReflection();
        var detection = new HwaByungDetector.ChronicFrustrationDetected(
            HwaByungDetector.Severity.LEVEL_3, 0.75, 0,
            Duration.ofDays(7), Instant.now());

        invokeApplyIntervention(actor, detection);
        var drained = (HwaByungIntervention.JournalPrompt) invokeDrain(actor);
        assertThat(drained).isNotNull();
        assertThat(drained.severity()).isEqualTo(HwaByungDetector.Severity.LEVEL_3);
    }

    @Test void level1_dispatch_does_not_queue_a_journal_prompt() throws Exception {
        var actor = newActorInstanceForReflection();
        var detection = new HwaByungDetector.ChronicFrustrationDetected(
            HwaByungDetector.Severity.LEVEL_1, 0.45, 0,
            Duration.ofDays(7), Instant.now());

        invokeApplyIntervention(actor, detection);
        // Level-1 only raises the Drives-Mirror emphasis — no journal prompt.
        assertThat(getField(actor, "pendingHwaByungJournalPrompt")).isNull();
        assertThat(invokeDrain(actor)).isNull();
    }

    // ── String-source guards: completeSleep wires the drain + journal write ──

    @Test void complete_sleep_calls_drain_in_journal_write_phase() throws Exception {
        String src = sourceText();
        int sleepStart = src.indexOf("private void completeSleep(");
        assertThat(sleepStart)
            .as("CompanionActor.completeSleep must exist")
            .isGreaterThan(0);
        // Find the next private method header — bound the body.
        int sleepEnd = src.indexOf("\n    private ", sleepStart + 1);
        if (sleepEnd < 0) sleepEnd = src.length();
        var body = src.substring(sleepStart, sleepEnd);

        assertThat(body)
            .as("completeSleep must drain the Hwa-byung journal prompt")
            .contains("drainHwaByungJournalPrompt()");
        assertThat(body)
            .as("completeSleep must write through HearthJournal — the canonical "
                + "sleep-cycle journal-write surface")
            .contains("getHearthJournal()");
        assertThat(body)
            .as("the queued prompt must be surfaced as a leading prompt in the entry")
            .contains("Hwa-byung surfacing");
    }

    @Test void drain_method_javadoc_mentions_completeSleep_wiring() throws Exception {
        // Documentation guard: the drain method's javadoc should reference the
        // sleep-cycle journal-write phase that calls it (so future readers
        // know the consumer exists).
        String src = sourceText();
        int drainSig = src.indexOf("HwaByungIntervention.JournalPrompt drainHwaByungJournalPrompt()");
        assertThat(drainSig).isGreaterThan(0);
        // Look at the ~700 chars before the signature for the javadoc.
        var preamble = src.substring(Math.max(0, drainSig - 700), drainSig);
        assertThat(preamble)
            .as("drain method javadoc should reference completeSleep")
            .contains("completeSleep");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static CompanionActor newActorInstanceForReflection() throws Exception {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        var unsafe = theUnsafe.get(null);
        var allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        var actor = (CompanionActor) allocateInstance.invoke(unsafe, CompanionActor.class);

        setFinalField(actor, "hwaByungDetector", new HwaByungDetector());
        setFinalField(actor, "hwaByungIntervention", new HwaByungIntervention());
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

    private static Object invokeDrain(CompanionActor actor) throws Exception {
        Method drain = CompanionActor.class.getDeclaredMethod("drainHwaByungJournalPrompt");
        drain.setAccessible(true);
        return drain.invoke(actor);
    }

    private static Object getField(Object o, String name) throws Exception {
        Field f = CompanionActor.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void setFinalField(Object o, String name, Object value) throws Exception {
        Field f = CompanionActor.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    /** Read CompanionActor.java for source-level guards. */
    private static String sourceText() throws Exception {
        var path = Path.of(
            "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        if (!Files.exists(path)) {
            path = Path.of(
                "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        }
        return Files.readString(path);
    }
}
