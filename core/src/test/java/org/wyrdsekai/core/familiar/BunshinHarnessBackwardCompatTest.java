package org.wyrdsekai.core.familiar;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track A Phase 3 — backward compatibility for the
 * bunshin {@code Dispatch} message. The 5-arg constructor and the
 * 7-arg constructor with {@code harnessKind=null} both default to
 * {@code "react"}; existing call sites must keep working.
 */
class BunshinHarnessBackwardCompatTest {

    private static ActorTestKit kit;

    @BeforeAll
    static void setUpKit() {
        kit = ActorTestKit.create("BunshinHarnessBackwardCompatTest");
    }

    @AfterAll
    static void tearDown() {
        if (kit != null) kit.shutdownTestKit();
    }

    private static final String PRIMARY = "did:wyrd:zA:wyrd";
    private static final String SYS = "You are Wyrd.";

    @Test
    void five_arg_constructor_defaults_to_react_and_uses_done_marker() {
        var router = kit.spawn(scriptedRouter(List.of(
            "Reasoning... answer 42\n" + BunshinActor.DONE_MARKER
        )));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(), probe.ref()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        assertTrue(report.summary().contains("42"));
        // ReAct path: note is empty (only code-mode populates it).
        assertTrue(report.note().isEmpty(),
            "ReAct path should not populate code-mode structured note");
    }

    @Test
    void null_harnessKind_via_7arg_form_defaults_to_react() {
        var router = kit.spawn(scriptedRouter(List.of(
            "answer\n" + BunshinActor.DONE_MARKER
        )));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(), probe.ref(),
            null, null));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        assertTrue(report.note().isEmpty());
    }

    @Test
    void blank_harnessKind_normalises_to_react() {
        var d = new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(), null, "", null);
        assertEquals("react", d.harnessKind());
        assertFalse(d.isCodeMode());
    }

    @Test
    void code_mode_flag_round_trips() {
        var d = new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(), null, "code-mode", null);
        assertEquals("code-mode", d.harnessKind());
        assertTrue(d.isCodeMode());
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
