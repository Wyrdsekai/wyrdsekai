package org.wyrdsekai.core.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The updater's quiet moment is made, not waited for: her own time is held while the turns in
 * flight finish. On the household node the longest stretch between 03:00 and 05:00 with no model
 * call was 8 to 12 minutes, so a moment of ten minutes with nothing in flight was all but never met.
 */
class QuietMomentTest {

    private static final Duration TEN_MIN = Duration.ofMinutes(10);
    private static final Duration SETTLE = Duration.ofMillis(30);
    private static final Duration MOST = Duration.ofMillis(400);
    private static final QuietMoment.Sleeper NAP = d -> Thread.sleep(10);

    @BeforeEach
    @AfterEach
    void clean() {
        ActivityGauge.reset();
        InferenceRouter.releaseOwnTime();
    }

    @Test
    void with_the_house_quiet_her_own_time_is_held_and_the_moment_is_reached() throws Exception {
        Thread.sleep(40);   // nothing has run since the reset

        assertThat(QuietMoment.reach(TEN_MIN, SETTLE, MOST, NAP)).isTrue();
        assertThat(InferenceRouter.isOwnTimeHeld()).as("the hold stays: the update restarts the node").isTrue();
    }

    @Test
    void a_turn_in_flight_is_waited_for() throws Exception {
        ActivityGauge.inferenceStarted();
        var finisher = new Thread(() -> {
            try { Thread.sleep(80); } catch (InterruptedException ignored) { }
            ActivityGauge.inferenceFinished();
        });
        finisher.start();

        assertThat(QuietMoment.reach(TEN_MIN, SETTLE, MOST, NAP)).as("reached once the turn finished and settled").isTrue();
        finisher.join();
    }

    @Test
    void a_person_who_spoke_lately_means_no_moment_and_no_hold() {
        ActivityGauge.personSpoke();

        assertThat(QuietMoment.reach(TEN_MIN, SETTLE, MOST, NAP)).isFalse();
        assertThat(InferenceRouter.isOwnTimeHeld()).isFalse();
    }

    @Test
    void a_sleep_or_a_coding_task_is_not_broken() {
        ActivityGauge.sleepStarted();
        assertThat(QuietMoment.reach(TEN_MIN, SETTLE, MOST, NAP)).isFalse();
        ActivityGauge.sleepFinished();
        ActivityGauge.codingTaskStarted();
        assertThat(QuietMoment.reach(TEN_MIN, SETTLE, MOST, NAP)).isFalse();
        assertThat(InferenceRouter.isOwnTimeHeld()).isFalse();
    }

    @Test
    void a_person_speaking_during_the_wait_lets_the_hold_go() {
        ActivityGauge.inferenceStarted();   // a turn is out
        QuietMoment.Sleeper personArrives = d -> { ActivityGauge.personSpoke(); Thread.sleep(10); };

        assertThat(QuietMoment.reach(TEN_MIN, SETTLE, MOST, personArrives)).isFalse();
        assertThat(InferenceRouter.isOwnTimeHeld()).isFalse();
    }

    @Test
    void work_that_goes_on_past_the_limit_lets_the_hold_go() {
        ActivityGauge.inferenceStarted();   // never finishes

        assertThat(QuietMoment.reach(TEN_MIN, SETTLE, MOST, NAP)).isFalse();
        assertThat(InferenceRouter.isOwnTimeHeld()).isFalse();
    }

    @Test
    void another_hold_is_not_let_go() {
        InferenceRouter.holdOwnTime(Duration.ofMinutes(5), "reflex: heap");
        ActivityGauge.personSpoke();

        assertThat(QuietMoment.reach(TEN_MIN, SETTLE, MOST, NAP)).isFalse();
        assertThat(InferenceRouter.isOwnTimeHeld()).as("a reflex's hold stands").isTrue();
        InferenceRouter.releaseOwnTimeHeldFor(QuietMoment.HOLD_REASON);
        assertThat(InferenceRouter.isOwnTimeHeld()).as("only the updater's own hold is released by it").isTrue();
    }
}
