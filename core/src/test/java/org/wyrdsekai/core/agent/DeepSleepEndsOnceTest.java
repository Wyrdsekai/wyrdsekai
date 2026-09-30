package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deep sleep, 2026-09-28. The watchdog woke her at the deadline while voice training went on with
 * the voice model stopped for up to an hour, and a training or forge answer that arrived after
 * that ran the whole wake (recovery, marks, the night's bookkeeping) a second time. Now: the
 * deadline first stops her training (the trainer's cleanup restarts the voice), every sleep
 * result carries the number of its sleep, a result for a sleep that is over only keeps what it
 * made of her, and a sleep cannot be finished while she is awake. The process side is in
 * {@code TrainingProcessesTest}.
 */
class DeepSleepEndsOnceTest {

    private static final Path SRC = Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private static String method(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as(signature).isGreaterThan(0);
        int next = src.indexOf("\n    private ", start + signature.length());
        return src.substring(start, next > 0 ? next : src.length());
    }

    @Test
    void aSleepCannotBeFinishedWhileSheIsAwake() throws Exception {
        var body = method(Files.readString(SRC), "private void completeSleep(");
        assertThat(body.indexOf("if (!isSleeping)")).isGreaterThan(0)
            .isLessThan(body.indexOf("holdSleepForTheNightWrite("));
    }

    @Test
    void theDeadlineStopsHerTrainingBeforeForcingHerAwake() throws Exception {
        var body = method(Files.readString(SRC), "private Behavior<Command> onDeepSleepWatchdog(");
        int cancel = body.indexOf("DeepSleepTrainer.cancel(");
        int force = body.indexOf("completeSleep(null, null, null)");
        assertThat(cancel).isGreaterThan(0);
        assertThat(force).isGreaterThan(cancel);
        assertThat(body).contains("DEEP_SLEEP_STOP_GRACE").contains("deepSleepStartedAt)");
    }

    @Test
    void everySleepResultIsCheckedAgainstItsSleep() throws Exception {
        var src = Files.readString(SRC);
        for (var handler : new String[] {
                "private Behavior<Command> onForgeResult(",
                "private Behavior<Command> onSleepCycleComplete(",
                "private Behavior<Command> onDeepSleepTrainingComplete("}) {
            var body = method(src, handler);
            assertThat(body).as(handler).contains("msg.sleepEpoch() != sleepEpoch");
        }
        assertThat(method(src, "private void initiateSleep(SleepTier tier)")).contains("sleepEpoch++");
    }

    @Test
    void theForgeIsAskedOncePerSleep() throws Exception {
        var body = method(Files.readString(SRC), "private void routeToForgeActor(");
        assertThat(body).contains("getContext().ask(ForgeCommand.ForgeResult.class")
            .doesNotContain("messageAdapter(");
    }

    @Test
    void theDeadlineIsASetting() throws Exception {
        var src = Files.readString(SRC);
        assertThat(src).doesNotContain("DEEP_SLEEP_DEADLINE =").contains("deepSleepDeadlineMinutes()");
    }
}
