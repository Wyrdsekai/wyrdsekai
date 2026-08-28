package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The person's question is pinned to the turn, not fished out of flow state.
 *
 * <p><b>Why fishing failed.</b> "What did the person ask?" was answered by a
 * chain of handles, each maintained by a different flow: {@code pendingTrigger}
 * (consumed as the turn starts), {@code reactRequester} (set only on the
 * activePlan paths), {@code lastReactTrigger} (stamped when a response
 * <em>returns</em>). A tool fired three seconds into an ordinary reactive turn
 * found all three empty — live, 2026-08-09 14:43 — so the person-words merge and
 * {@code askedFor} silently skipped, and a query contaminated by working memory
 * ("Robert J. Sawyer", "The Diamond Age", neither of them asked about) went to
 * the library unprotected.</p>
 *
 * <p><b>The deeper point, from the bondholder directly:</b> she injects her
 * prior memories into everything she composes. That is inherent to being one
 * model in one context — it cannot be prompted away, only routed around. The
 * runtime supplies operands; the model chooses actions. This pin is what makes
 * the operand supply unconditional.</p>
 */
class TheQuestionTravelsWithTheTurnTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    /** THE case: the pin is written where every reactive turn passes, trigger in hand. */
    @Test
    void the_pin_is_written_at_turn_start() throws Exception {
        var s = src();

        assertThat(s).contains("turnHumanRequest = pendingTrigger;");
        assertThat(s).contains("turnHumanRequestAt = Instant.now();");
        assertThat(s)
            .as("and only for a person — synthetic triggers must not masquerade")
            .contains("if (isHumanRequest(pendingTrigger)) {");
    }

    /** The dispatch site consults the pin FIRST — it is the only unconditional handle. */
    @Test
    void the_dispatch_site_reads_the_pin_first() throws Exception {
        assertThat(src())
            .contains("var trigger = pinnedTurnRequest() != null ? pinnedTurnRequest()");
    }

    /** Her own time is hers: autonomy turns must not inherit the question as operand. */
    @Test
    void an_own_time_turn_does_not_carry_the_persons_question() throws Exception {
        var s = src();

        assertThat(s).contains("pendingTrigger = autonomyEvent;");
        int autonomyAccept = s.indexOf("pendingTrigger = autonomyEvent;");
        var after = s.substring(autonomyAccept, Math.min(s.length(), autonomyAccept + 400));
        assertThat(after)
            .as("the mirrored contamination: HER research polluted by OUR words")
            .contains("turnIsHuman = false;");
    }

    /** The pin goes stale like any question does. */
    @Test
    void the_pin_expires() throws Exception {
        var s = src();
        int m = s.indexOf("private WorldEvent.Said pinnedTurnRequest()");

        assertThat(m).isGreaterThan(0);
        assertThat(s.substring(m, m + 500)).contains("TRIGGER_FRESHNESS");
        assertThat(s.substring(m, m + 500)).contains("if (!turnIsHuman || turnHumanRequest == null) return null;");
    }

    /**
     * The clean-room boundary: a bunshin's whole context is soul + task, so the
     * task string is the ONE channel contamination travels through. The person's
     * verbatim words ride along, marked as the authority — add, never replace.
     */
    @Test
    void a_bunshin_task_carries_the_persons_exact_words() throws Exception {
        var s = src();

        assertThat(s).contains(
            "task = task + \"\\n\\nThe person's exact request, which is the authority on what \"");
        assertThat(s)
            .as("only when this turn is actually serving a person")
            .contains("var pinned = pinnedTurnRequest();");
    }

    /** Idempotent: a task that already quotes the person is not double-quoted. */
    @Test
    void the_append_is_skipped_when_already_present() throws Exception {
        assertThat(src())
            .contains(".contains(pinned.text().toLowerCase(Locale.ROOT))");
    }

    /** dispatch_task (the goose path) has the same boundary and the same fix. */
    @Test
    void a_workshop_task_carries_them_too() throws Exception {
        var s = src();

        assertThat(s).contains(
            "workerDescription = description + \"\\n\\nThe person's exact request (authoritative \"");
        assertThat(s)
            .as("the worker gets the quote; her spoken confirmation stays short")
            .contains("var spec = new TaskSpec(UUID.randomUUID(), agentDid, \"host_task\", taskDescription,");
    }
}
