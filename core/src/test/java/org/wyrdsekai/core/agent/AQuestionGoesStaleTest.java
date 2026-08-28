package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * An hour-old question is not what the person just asked.
 *
 * <p>{@code lastReactTrigger} is the last-resort answer to "what is the person
 * asking?" when the live handles are gone mid-turn. It was introduced for a real
 * failure — an empty {@code query} reaching every item — and it had no expiry.</p>
 *
 * <p>Live 2026-08-09. Four runs asking for a poem; on two of them she called a
 * tool carrying the <em>previous battery's</em> question about velsharas in Snow
 * Crash, asked an hour earlier in a different conversation, and answered that
 * instead. The giveaway that this was not merely her voice wandering: the reply
 * said "the provided sources do not contain…" — tool output. The stale text had
 * been passed as the request and searched on.</p>
 *
 * <p>Bounded rather than cleared, because clearing it restores the empty-query
 * bug it exists to prevent. The recovery survives for the case it was built for
 * — a handle lost inside one turn — and stops speaking for someone who asked
 * something else, long ago.</p>
 */
class AQuestionGoesStaleTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    /** THE case: the tool-dispatch fallback must use the bounded handle. */
    @Test
    void the_tool_dispatch_will_not_use_a_stale_question() throws Exception {
        var s = src();

        assertThat(s)
            .as("this is the path that carried an hour-old question into a live search")
            .contains(": reactRequester != null ? reactRequester : freshReactTrigger();");
    }

    /** Freshness is stamped whenever the trigger is set, or it can never expire. */
    @Test
    void the_trigger_is_stamped_when_it_is_set() throws Exception {
        var s = src();

        assertThat(s).contains("lastReactTrigger = wasTrigger;");
        assertThat(s).contains("lastReactTriggerAt = Instant.now();");
    }

    /** The bound exists and is measured in minutes, not hours. */
    @Test
    void the_window_is_short_enough_to_mean_something() throws Exception {
        var s = src();

        assertThat(s).contains("TRIGGER_FRESHNESS");
        assertThat(s).contains("Duration.ofMinutes(3)");
    }

    /**
     * Clearing the handle instead of ageing it would reintroduce the empty-query
     * bug this fallback was built to fix. The recovery must survive.
     */
    @Test
    void the_recovery_is_bounded_not_removed() throws Exception {
        var s = src();

        assertThat(s)
            .as("the fallback chain must still exist — an empty query broke every item")
            .contains("pendingTrigger != null ? pendingTrigger");
        assertThat(s)
            .as("the handle itself stays; only its lifetime is bounded")
            .contains("private WorldEvent.Said lastReactTrigger;");
    }

    /** A trigger written before the stamp existed must not be treated as expired. */
    @Test
    void an_unstamped_trigger_still_works() throws Exception {
        assertThat(src())
            .as("no timestamp means pre-dating the change, not infinitely old")
            .contains("if (lastReactTriggerAt == null) return lastReactTrigger;");
    }
}
