package org.wyrdsekai.server.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.body.Quiesce;
import org.wyrdsekai.core.inference.InferenceRouter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Held still (brain move)" did not hold: the launcher's quiesce persisted state and left a mark,
 * and the node went on taking its companions' own-time turns on the processor model for twenty
 * minutes beside the large model being measured (a household node, 2026-10-19). A hold now pauses
 * every new turn for as long as the launcher's next step needs, and the launcher lifts it when it
 * is the one that goes on running.
 */
class TheHoldHoldsTest {

    @AfterEach
    void letGo() {
        InferenceRouter.resume();
        Quiesce.resetForTests();
    }

    @Test
    @DisplayName("a hold pauses new turns and names its reason; resume lifts it")
    void aHoldPauses() {
        Quiesce.resetForTests();
        assertThat(InferenceRouter.isPaused()).isFalse();
        var report = HealthRoutes.holdStill("an update", "the steward", 10);
        assertThat(InferenceRouter.isPaused()).isTrue();
        assertThat(InferenceRouter.snapshot().pauseReason()).isEqualTo("held still for an update");
        assertThat(report.reason()).isEqualTo("an update");
        assertThat(report.who()).isEqualTo("the steward");
        InferenceRouter.resume();
        assertThat(InferenceRouter.isPaused()).isFalse();
    }

    @Test
    @DisplayName("no reason given: still a hold, called a pause, never shorter than a minute")
    void aBareHold() {
        HealthRoutes.holdStill(" ", null, 0);
        assertThat(InferenceRouter.isPaused()).isTrue();
        assertThat(InferenceRouter.snapshot().pauseReason()).isEqualTo("held still for a pause");
    }
}
