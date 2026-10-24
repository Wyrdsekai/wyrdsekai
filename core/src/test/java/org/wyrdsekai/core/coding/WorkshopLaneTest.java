package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.BackendWindows;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two workshop jobs on the household brain held both of its slots for a quarter of an hour
 * (2026-10-23) while the companions were refused. The workshop now has as many lanes as the
 * brain has spare slots, and never fewer than one; a job on a drive elsewhere takes no lane.
 */
class WorkshopLaneTest {

    private static final String BRAIN_PROPS = "{\"default_generation_settings\":{\"n_ctx\":32768},\"total_slots\":2}";

    @BeforeEach
    @AfterEach
    void clean() {
        WorkshopLane.resetForTests();
        resetWindows();
    }

    private static void resetWindows() {
        // package-private in the inference package: reach it the way the window tests do
        try {
            var m = BackendWindows.class.getDeclaredMethod("resetForTests");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void a_drive_on_this_machine_or_on_a_brain_the_node_knows_shares_the_brain() {
        assertThat(WorkshopLane.sharesTheBrain("http://localhost:8200")).isTrue();
        assertThat(WorkshopLane.sharesTheBrain("http://127.0.0.1:8200")).isTrue();
        assertThat(WorkshopLane.sharesTheBrain("http://198.51.100.65:8200")).as("another machine, unknown to the node").isFalse();
        BackendWindows.learnedFromLlamaProps("brain", "http://198.51.100.65:8200", BRAIN_PROPS, "/props");
        assertThat(WorkshopLane.sharesTheBrain("http://198.51.100.65:8200")).as("the node speaks through it too").isTrue();
        assertThat(WorkshopLane.sharesTheBrain(null)).isFalse();
    }

    @Test
    void lanes_are_the_brains_spare_slots_and_never_fewer_than_one() {
        assertThat(WorkshopLane.lanes("http://localhost:8200")).as("slot count unknown").isEqualTo(1);
        BackendWindows.learnedFromLlamaProps("brain", "http://localhost:8200", BRAIN_PROPS, "/props");
        assertThat(WorkshopLane.lanes("http://localhost:8200")).as("two slots, one kept for the house").isEqualTo(1);
        BackendWindows.learnedFromLlamaProps("big", "http://localhost:8300",
            "{\"default_generation_settings\":{\"n_ctx\":65536},\"total_slots\":4}", "/props");
        assertThat(WorkshopLane.lanes("http://localhost:8300")).isEqualTo(3);
        BackendWindows.learnedFromLlamaProps("one", "http://localhost:8400",
            "{\"default_generation_settings\":{\"n_ctx\":8192},\"total_slots\":1}", "/props");
        assertThat(WorkshopLane.lanes("http://localhost:8400")).as("a one-slot brain still builds").isEqualTo(1);
    }

    @Test
    void a_second_job_waits_until_the_first_leaves_and_a_remote_drive_never_waits() throws Exception {
        BackendWindows.learnedFromLlamaProps("brain", "http://localhost:8200", BRAIN_PROPS, "/props");
        var drive = "http://localhost:8200";
        assertThat(WorkshopLane.enter(drive, "job-rose", "did:key:rose")).isEqualTo(Duration.ZERO);
        assertThat(WorkshopLane.isFull(drive)).isTrue();
        assertThat(WorkshopLane.whoHoldsALane()).isPresent();
        assertThat(WorkshopLane.whoHoldsALane().get().companionDid()).isEqualTo("did:key:rose");

        var entered = new CountDownLatch(1);
        var waited = new AtomicReference<Duration>();
        var second = Thread.ofVirtual().start(() -> {
            try {
                waited.set(WorkshopLane.enter(drive, "job-mia", "did:key:mia"));
                entered.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(300, TimeUnit.MILLISECONDS)).as("the lane is taken; mia's job waits").isFalse();

        // Elsewhere: a drive on another machine takes nothing from this brain.
        assertThat(WorkshopLane.enter("http://192.0.2.9:8200", "job-remote", null)).isEqualTo(Duration.ZERO);
        WorkshopLane.leave("job-remote");

        WorkshopLane.leave("job-rose");
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("rose's job left; mia's goes in").isTrue();
        second.join(1000);
        assertThat(waited.get()).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(WorkshopLane.whoHoldsALane().get().taskId()).isEqualTo("job-mia");
        WorkshopLane.leave("job-mia");
        assertThat(WorkshopLane.isFull(drive)).isFalse();
    }
}
