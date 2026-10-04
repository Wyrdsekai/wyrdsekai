package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two beings share one brain fairly: a person's turn first, then the being served longest ago,
 * then the order of arrival; a companion's own time below a person's; and nothing waits for good.
 *
 * <p>The router's line was a priority queue on one number: no order within a band, a companion's
 * own-time turn level with a person's, nothing between beings, no aging (audit, 2026-10-03).
 */
class TwoBeingsShareTheBrainFairlyTest {

    @Test
    void within_a_band_the_being_served_longest_ago_goes_first_and_then_arrival_order() {
        var q = new FairQueue<String>();
        long t = 1_000;
        q.add(InferenceRouter.PRIORITY_HUMAN, "companion-mia", t, "mia-1");
        q.add(InferenceRouter.PRIORITY_HUMAN, "companion-mia", t, "mia-2");
        q.add(InferenceRouter.PRIORITY_HUMAN, "companion-rose", t, "rose-1");
        q.add(InferenceRouter.PRIORITY_HUMAN, "companion-mia", t, "mia-3");
        q.add(InferenceRouter.PRIORITY_HUMAN, "companion-rose", t, "rose-2");
        var order = new ArrayList<String>();
        while (!q.isEmpty()) order.add(q.poll(t));
        assertThat(order).containsExactly("mia-1", "rose-1", "mia-2", "rose-2", "mia-3");
    }

    @Test
    void a_persons_turn_goes_before_a_tool_before_a_companions_own_time() {
        var q = new FairQueue<String>();
        long t = 1_000;
        q.add(InferenceRouter.PRIORITY_AMBIENT, "companion-mia", t, "mia-own-time");
        q.add(InferenceRouter.PRIORITY_TOOL, "companion-rose", t, "rose-tool");
        q.add(InferenceRouter.PRIORITY_HUMAN, "companion-rose", t, "rose-answers-a-person");
        assertThat(q.poll(t)).isEqualTo("rose-answers-a-person");
        assertThat(q.poll(t)).isEqualTo("rose-tool");
        assertThat(q.poll(t)).isEqualTo("mia-own-time");
        assertThat(InferenceRouter.classifyPriority("3f2a-9c" + InferenceRouter.OWN_TIME_TAG)).isEqualTo(InferenceRouter.PRIORITY_AMBIENT);
        assertThat(InferenceRouter.classifyPriority("3f2a-9c")).isEqualTo(InferenceRouter.PRIORITY_HUMAN);
    }

    @Test
    void a_long_wait_lifts_a_request_one_band_at_a_time_so_nothing_waits_for_good() {
        var q = new FairQueue<String>();
        q.add(InferenceRouter.PRIORITY_AMBIENT, "companion-mia", 0, "mia-own-time");
        q.add(InferenceRouter.PRIORITY_HUMAN, "companion-rose", 10_000, "rose-1");
        assertThat(q.peek(10_000).item()).isEqualTo("rose-1");
        // Four waits later her own time stands level with a person's turn, and she was served longer ago.
        long later = 4 * FairQueue.AGING_MS + 1;
        q.add(InferenceRouter.PRIORITY_HUMAN, "companion-rose", later, "rose-2");
        assertThat(q.poll(later)).isEqualTo("mia-own-time");
        assertThat(q.poll(later)).isEqualTo("rose-1");
        assertThat(q.poll(later)).isEqualTo("rose-2");
        assertThat(q.poll(later)).isNull();
    }

    @Test
    void the_being_is_read_from_who_asked_and_her_own_time_is_marked() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca.split("UUID.randomUUID\\(\\).toString\\(\\) \\+ laneTag\\(\\);", -1).length - 1).as("her three turn builders").isEqualTo(3);
        assertThat(ca).contains("return !reactiveInference && !aPersonAskedForThisTurn() ? InferenceRouter.OWN_TIME_TAG : \"\";");
        var router = Files.readString(Files.exists(Path.of("..", "core")) ? Path.of("..", "core/src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java")
            : Path.of("core/src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java"));
        assertThat(router).contains("private final FairQueue<Command> inferenceQueue = new FairQueue<>();");
        assertThat(router).doesNotContain("PriorityQueue");
        assertThat(router.split("beingOf\\(req.replyTo\\(\\)\\)", -1).length - 1).as("every way into the line").isEqualTo(3);
    }
}
