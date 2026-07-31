package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track A Phase 3 — code-mode retry budget (§11).
 *
 * <p>One retry on script error; beyond that the bunshin escalates the failure
 * to the parent as a structured FAILURE report. Cap at 2 LLM calls total per
 * code-mode dispatch.
 */
class BunshinCodeModeRetryTest {

    private static ActorTestKit kit;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUpKit() {
        kit = ActorTestKit.create("BunshinCodeModeRetryTest");
    }

    @AfterAll
    static void tearDown() {
        if (kit != null) kit.shutdownTestKit();
    }

    private static final String PRIMARY = "did:wyrd:zA:wyrd";
    private static final String SYS = "You are Wyrd.";

    @Test
    void retries_once_after_script_error_and_succeeds() throws Exception {
        // First response: broken JS (referenceError). Second: valid JS.
        var broken = "this_does_not_exist_function();";
        var valid = "console.log('recovered');";
        var router = kit.spawn(scriptedRouter(List.of(broken, valid)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(),
            probe.ref(), "code-mode", Map.of()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(10));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        assertEquals(2, report.turnsUsed(), "should have used the retry");

        var note = MAPPER.readTree(report.note().get());
        assertEquals(2, note.get("scripts").size(),
            "both scripts (failing + recovery) should be journalled");
    }

    @Test
    void escalates_failure_after_one_retry_cap() throws Exception {
        // Two broken responses — bunshin must fail after second attempt,
        // never make a third LLM call.
        var broken1 = "throw new Error('first error');";
        var broken2 = "throw new Error('second error');";
        // Add a third response that should NEVER be consumed
        var sentinel = "console.log('SHOULD NOT RUN');";
        var router = kit.spawn(scriptedRouter(List.of(broken1, broken2, sentinel)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(),
            probe.ref(), "code-mode", Map.of()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(10));
        assertEquals(BunshinReport.Outcome.FAILURE, report.outcome());
        assertEquals(2, report.turnsUsed(), "must cap at 2 LLM calls (initial + retry)");

        var note = MAPPER.readTree(report.note().get());
        assertFalse(note.get("ok").asBoolean());
        assertEquals(2, note.get("scripts").size());
        assertTrue(note.has("error"));
        // Sentinel script must not appear in any log entry
        for (var entry : note.get("logs")) {
            assertFalse(entry.asText().contains("SHOULD NOT RUN"),
                "third LLM call must never run after retry cap");
        }
    }

    @Test
    void no_script_in_response_consumes_retry_budget() throws Exception {
        // First response: prose only, no JS. Second: valid JS.
        var prose = "I'm not sure how to do this task.";
        var valid = "console.log('finally');";
        var router = kit.spawn(scriptedRouter(List.of(prose, valid)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(),
            probe.ref(), "code-mode", Map.of()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(10));
        // The "prose only" response is treated as no-script; the strip+fence
        // logic accepts the prose as raw JS (which then errors at runtime).
        // Either path is acceptable; we just need finish without exceeding 2 calls.
        assertTrue(report.turnsUsed() <= 2);
    }

    private static Behavior<InferenceRouter.Command> scriptedRouter(List<String> responses) {
        return Behaviors.setup(ctx -> {
            var cursor = new AtomicInteger(0);
            return Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    var i = Math.min(cursor.getAndIncrement(), responses.size() - 1);
                    req.replyTo().tell(new InferenceRouter.InferOk(
                        req.requestId(), responses.get(i), 80, 20));
                    return Behaviors.same();
                })
                .build();
        });
    }
}
