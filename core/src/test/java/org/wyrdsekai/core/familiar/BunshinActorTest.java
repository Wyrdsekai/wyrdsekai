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
 * Covers — bunshin runtime, termination outcomes
 * yield/resume behavior, BunshinReport shape.
 */
class BunshinActorTest {

    private static ActorTestKit kit;

    @BeforeAll
    static void setUpKit() {
        kit = ActorTestKit.create("BunshinActorTest");
    }

    @AfterAll
    static void tearDown() {
        if (kit != null) kit.shutdownTestKit();
    }

    private static final String PRIMARY = "did:wyrd:zA:wyrd";
    private static final String SYSTEM_PROMPT = "You are Wyrd. Calm, thoughtful, present.";

    // ── successful completion ───────────────────────────────────────────────

    @Test
    void emits_success_report_on_done_marker() {
        var router = kit.spawn(scriptedRouter(List.of(
            "Thinking...\nAnswer: 42\n" + BunshinActor.DONE_MARKER
        )));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, SYSTEM_PROMPT,
            "compute the answer", Tanks.defaults(), probe.ref()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        assertTrue(report.summary().contains("42"));
        assertEquals(PRIMARY, report.primaryAgentDid());
        assertTrue(report.turnsUsed() >= 1);
        assertTrue(report.succeeded());
    }

    // ── timeout on no DONE ──────────────────────────────────────────────────

    @Test
    void emits_timeout_when_no_done() {
        var router = kit.spawn(varyingRouter());  // never emits DONE
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        var tight = new Tanks(5000, 3, 10, 0, 100);
        actor.tell(new BunshinActor.Dispatch(PRIMARY, SYSTEM_PROMPT,
            "keep going", tight, probe.ref()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(10));
        assertEquals(BunshinReport.Outcome.TIMEOUT, report.outcome());
        assertFalse(report.succeeded());
    }

    // ── cancel path ─────────────────────────────────────────────────────────

    @Test
    void emits_cancelled_on_cancel() throws InterruptedException {
        var router = kit.spawn(delayedRouter("Still thinking...", Duration.ofMillis(300)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, SYSTEM_PROMPT,
            "long task", Tanks.defaults(), probe.ref()));
        Thread.sleep(100);
        actor.tell(new BunshinActor.Cancel());

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.CANCELLED, report.outcome());
    }

    // ── yield / resume ──────────────────────────────────────────────────────

    @Test
    void yield_pauses_between_turns_and_resume_completes_work() {
        // Router delays each reply; each reply is a non-DONE step until the
        // 3rd one. We dispatch, immediately yield (before the first reply
        // even lands), then confirm yielded, resume, and await completion.
        var delayedSequencer = kit.spawn(delayedScriptedRouter(List.of(
            "First step.",
            "Second step.",
            "All done.\n" + BunshinActor.DONE_MARKER
        ), Duration.ofMillis(200)));
        var actor = kit.spawn(BunshinActor.create(delayedSequencer));
        var probe = kit.<BunshinReport>createTestProbe();
        var statusProbe = kit.<BunshinActor.Status>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, SYSTEM_PROMPT,
            "three-step task", Tanks.defaults(), probe.ref()));
        // Yield before any reply has landed — the reply will see yielded=true
        // and set resumePending, then wait for Resume.
        actor.tell(new BunshinActor.Yield());

        // First reply lands ~200ms in; by 400ms the actor should be in the
        // yielded-but-waiting state with resumePending=true, not terminated.
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        actor.tell(new BunshinActor.StatusQuery(statusProbe.ref()));
        var s = statusProbe.expectMessageClass(BunshinActor.Status.class, Duration.ofSeconds(3));
        assertTrue(s.yielded());
        assertFalse(s.terminated());

        actor.tell(new BunshinActor.Resume());
        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(10));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
    }

    // ── status query non-destructive ────────────────────────────────────────

    @Test
    void status_query_does_not_terminate() {
        var router = kit.spawn(scriptedRouter(List.of(
            "Working...",
            "Done.\n" + BunshinActor.DONE_MARKER
        )));
        var actor = kit.spawn(BunshinActor.create(router));
        var reportProbe = kit.<BunshinReport>createTestProbe();
        var statusProbe = kit.<BunshinActor.Status>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, SYSTEM_PROMPT,
            "task", Tanks.defaults(), reportProbe.ref()));

        actor.tell(new BunshinActor.StatusQuery(statusProbe.ref()));
        var status = statusProbe.expectMessageClass(BunshinActor.Status.class,
            Duration.ofSeconds(3));
        assertNotNull(status.bunshinId());

        // Original work still completes
        var report = reportProbe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
    }

    // ── anti-false-completion gate (second-node 2026-07-11 #27) ───────────────────

    @Test
    void false_completion_claim_forces_a_corrective_turn() {
        // Turn 1: "garden room done" — a world-mutation claim the prose-only
        // harness cannot have performed. The gate must block the DONE, inject
        // a corrective turn, and only then accept the honest second response.
        var router = kit.spawn(scriptedRouter(List.of(
            "Done — I built the garden room and set up the fountain.\n"
                + BunshinActor.DONE_MARKER,
            "I cannot build rooms from this focused harness. Here is the plan "
                + "for the garden room instead: a fountain at the center, ivy walls.\n"
                + BunshinActor.DONE_MARKER
        )));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, SYSTEM_PROMPT,
            "make me a garden room", Tanks.defaults(), probe.ref()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        assertTrue(report.turnsUsed() >= 2,
            "gate must force at least one extra turn, got " + report.turnsUsed());
        assertTrue(report.summary().contains("plan"),
            "final summary must be the honest second response");
        assertFalse(report.summary().contains("I built the garden room"));
    }

    @Test
    void repeated_false_completion_reports_partial_not_success() {
        // The model doubles down on the claim after the corrective turn —
        // the report must NOT be relayed as a clean SUCCESS.
        var router = kit.spawn(scriptedRouter(List.of(
            "Done — I built the garden room.\n" + BunshinActor.DONE_MARKER,
            "As I said, I built the garden room and stored the key.\n"
                + BunshinActor.DONE_MARKER
        )));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, SYSTEM_PROMPT,
            "make me a garden room", Tanks.defaults(), probe.ref()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.PARTIAL, report.outcome());
        assertTrue(report.summary().startsWith("[unverified claim"),
            "summary must carry the honest wrapper, got: " + report.summary());
        // NOTE: succeeded() deliberately counts PARTIAL as a positive outcome
        // ("made progress") — the honest signal here is the PARTIAL outcome +
        // the wrapper prefix the primary relays, not a hard failure.
    }

    @Test
    void long_writing_deliverable_with_finished_verb_passes_the_gate() {
        // A writing task legitimately ends "I've finished the letter" WITH the
        // artifact attached — the deliverable-length escape hatch applies.
        var letter = "Dear friend, " + "the seasons turn and I think of you. ".repeat(8);
        var router = kit.spawn(scriptedRouter(List.of(
            "I've finished the letter.\n\n" + letter + "\n" + BunshinActor.DONE_MARKER
        )));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, SYSTEM_PROMPT,
            "write a letter", Tanks.defaults(), probe.ref()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        assertEquals(1, report.turnsUsed());
    }

    @Test
    void claim_predicate_contract() {
        // World-mutation claims gate regardless of length
        assertTrue(BunshinActor.claimedCompletionWithoutDoing(
            "I built the garden room. " + "Lots of detail follows here. ".repeat(20)));
        assertTrue(BunshinActor.claimedCompletionWithoutDoing("Sent the report to alice."));
        // Bare soft claim gates; long deliverable with soft verb passes
        assertTrue(BunshinActor.claimedCompletionWithoutDoing("Task finished."));
        assertFalse(BunshinActor.claimedCompletionWithoutDoing(
            "I've finished. Here is the essay: " + "words and more words. ".repeat(20)));
        // Plain prose with no claim never gates
        assertFalse(BunshinActor.claimedCompletionWithoutDoing(
            "Here is my analysis of the question."));
        assertFalse(BunshinActor.claimedCompletionWithoutDoing(null));
        assertFalse(BunshinActor.claimedCompletionWithoutDoing("  "));
    }

    @Test
    void claim_predicate_possession_shapes_gate() {
        // #29: this harness has no inventory — possession claims are always
        // false, regardless of how much text accompanies them.
        assertTrue(BunshinActor.claimedCompletionWithoutDoing(
            "The behavior script is now in hand. " + "Detail. ".repeat(40)));
        assertTrue(BunshinActor.claimedCompletionWithoutDoing("Picked it up as requested."));
        assertTrue(BunshinActor.claimedCompletionWithoutDoing("Grabbed the lantern for you."));
        // Temporal "took" stays legitimate — deliberately not gated.
        assertFalse(BunshinActor.claimedCompletionWithoutDoing(
            "The comparison took several angles into account; here they are: "
            + "first, second, third. " + "More analysis. ".repeat(20)));
    }

    // ── Router helpers ──────────────────────────────────────────────────────

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

    private static Behavior<InferenceRouter.Command> varyingRouter() {
        return Behaviors.setup(ctx -> {
            var n = new AtomicInteger(0);
            return Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    req.replyTo().tell(new InferenceRouter.InferOk(
                        req.requestId(), "turn-" + n.incrementAndGet(), 80, 20));
                    return Behaviors.same();
                })
                .build();
        });
    }

    private static Behavior<InferenceRouter.Command> delayedScriptedRouter(
            List<String> responses, Duration delay) {
        return Behaviors.setup(ctx -> {
            var cursor = new AtomicInteger(0);
            return Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    var replyTo = req.replyTo();
                    var id = req.requestId();
                    var i = Math.min(cursor.getAndIncrement(), responses.size() - 1);
                    var text = responses.get(i);
                    ctx.getSystem().scheduler().scheduleOnce(delay, () ->
                        replyTo.tell(new InferenceRouter.InferOk(id, text, 50, 10)),
                        ctx.getSystem().executionContext());
                    return Behaviors.same();
                })
                .build();
        });
    }

    private static Behavior<InferenceRouter.Command> delayedRouter(String text, Duration delay) {
        return Behaviors.setup(ctx ->
            Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    var replyTo = req.replyTo();
                    var id = req.requestId();
                    ctx.getSystem().scheduler().scheduleOnce(delay, () ->
                        replyTo.tell(new InferenceRouter.InferOk(id, text, 50, 10)),
                        ctx.getSystem().executionContext());
                    return Behaviors.same();
                })
                .build());
    }
}
