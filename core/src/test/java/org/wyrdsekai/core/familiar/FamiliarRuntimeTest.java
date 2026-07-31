package org.wyrdsekai.core.familiar;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — familiar runtime foundations.
 *
 * Two layers of assertions:
 *   1. Pure-data invariants (Tanks, Provenance, ThoughtForm, Familiar records).
 *   2. Actor-level loop behavior (done marker, tank exhaustion, cancel, nudge,
 *      repeated-stuck detection, status query).
 *
 * The actor tests use a scripted fake InferenceRouter — no real LLM calls.
 */
class FamiliarRuntimeTest {

    private static ActorTestKit kit;

    @BeforeAll
    static void setUpKit() {
        kit = ActorTestKit.create("FamiliarRuntimeTest");
    }

    @AfterAll
    static void tearDownKit() {
        if (kit != null) kit.shutdownTestKit();
    }

    // ── Tanks ───────────────────────────────────────────────────────────────

    @Test
    void tanks_defaults_are_within_max_ceiling() {
        assertTrue(Tanks.defaults().withinCeiling(Tanks.maxCeiling()));
        assertTrue(Tanks.strict().withinCeiling(Tanks.defaults()));
    }

    @Test
    void tanks_exhaustion_detection() {
        var t = new Tanks(100, 10, 30, 1, 5);
        assertFalse(t.exhausted());
        assertEquals("active", t.exhaustedReason());

        var drained = t.burnStep(100, 0, 0);
        assertTrue(drained.exhausted());
        assertEquals("out of tokens", drained.exhaustedReason());
    }

    @Test
    void tanks_rejects_negative() {
        assertThrows(IllegalArgumentException.class, () -> new Tanks(-1, 0, 0, 0, 0));
    }

    // ── Provenance ──────────────────────────────────────────────────────────

    @Test
    void provenance_appends_immutably() {
        var p = Provenance.authoredBy("did:wyrd:z1", "first");
        assertEquals("did:wyrd:z1", p.currentOwner());
        var p2 = p.append(new Provenance.Edit("did:wyrd:z2", Provenance.Action.REVISED,
            null, "edit 1"));
        assertEquals(1, p.lineage().size());        // original unchanged
        assertEquals(2, p2.lineage().size());
        assertEquals("did:wyrd:z2", p2.currentOwner());
        assertEquals("did:wyrd:z1", p2.originalAuthor());   // root preserved
    }

    // ── ThoughtForm ─────────────────────────────────────────────────────────

    @Test
    void thought_form_defaults_and_mutation() {
        var form = ThoughtForm.author("did:wyrd:zA", "researcher",
            "You research topics.", Set.of("web_search"), "Return 3 sources.");
        assertEquals("researcher", form.name());
        assertEquals("1.0.0", form.version());
        assertEquals(0, form.summonCount());
        assertEquals(0.0, form.successRatio());

        var bumped = form.incrementSummon().recordSuccess().recordSuccess().recordFailure();
        assertEquals(1, bumped.summonCount());
        assertEquals(2, bumped.successCount());
        assertEquals(1, bumped.failureCount());
        assertEquals(2.0 / 3.0, bumped.successRatio(), 1e-9);
    }

    @Test
    void thought_form_rejects_default_above_max_tanks() {
        assertThrows(IllegalArgumentException.class, () -> new ThoughtForm(
            "id", "bad", "1.0.0",
            Provenance.authoredBy("did:wyrd:z1", null),
            "prompt", Set.of(),
            Tanks.maxCeiling(),              // default
            Tanks.strict(),                  // ceiling — smaller than default
            3, 0, "", null, null, 0, 0, 0, 0f));
    }

    // ── Familiar (data) ─────────────────────────────────────────────────────

    @Test
    void familiar_summon_initializes_correctly() {
        var form = ThoughtForm.author("did:wyrd:zA", "echo",
            "Echo the task back.", Set.of(), "Output contains task.");
        var fam = Familiar.summon(form, "did:wyrd:zA", "say hello", Tanks.strict());
        assertEquals(Familiar.Status.RUNNING, fam.status());
        assertEquals(0, fam.trialsUsed());
        assertEquals("did:wyrd:zA", fam.parentAgentDid());
        assertEquals("say hello", fam.task());
        assertTrue(fam.log().isEmpty());
        assertTrue(fam.isAlive());
    }

    @Test
    void familiar_terminate_captures_result() {
        var form = ThoughtForm.author("did:wyrd:zA", "echo", "x", Set.of(), "");
        var fam = Familiar.summon(form, "did:wyrd:zA", "task", Tanks.defaults());
        var done = fam.terminate(Familiar.Status.DONE, "all good", "the answer");
        assertEquals(Familiar.Status.DONE, done.status());
        assertEquals("the answer", done.result().orElseThrow());
        assertEquals("all good", done.summary().orElseThrow());
        assertTrue(done.endedAt().isPresent());
        assertFalse(done.isAlive());
    }

    // ── FamiliarActor — loop terminates on DONE marker ──────────────────────

    @Test
    void familiar_loop_terminates_on_done_marker() {
        var form = ThoughtForm.author("did:wyrd:zA", "oneshot", "Respond with DONE", Set.of(), "");
        var router = kit.spawn(scriptedRouter(List.of(
            "Analyzing the task...\n" + FamiliarActor.DONE_MARKER + "\nAnswer: 42"
        )));
        var famActor = kit.spawn(FamiliarActor.create(router));
        var probe = kit.<FamiliarActor.Report>createTestProbe();

        famActor.tell(new FamiliarActor.Summon(form, "did:wyrd:zA", "compute the answer",
            Tanks.defaults(), probe.ref()));

        var report = probe.expectMessageClass(FamiliarActor.Report.class, Duration.ofSeconds(5));
        assertTrue(report.terminated());
        assertEquals(Familiar.Status.DONE, report.state().status());
        assertTrue(report.state().log().size() >= 1);
        // Result should contain the post-DONE text
        assertTrue(report.state().result().orElse("").toString().contains("42"));
    }

    // ── FamiliarActor — exhausts tanks when no DONE is ever emitted ─────────

    @Test
    void familiar_loop_exhausts_when_no_done_emitted() {
        var form = ThoughtForm.author("did:wyrd:zA", "wanderer", "Keep thinking", Set.of(), "");
        // Router varies output each turn so the stuck-detector doesn't trip;
        // loop should burn through `steps` and terminate with TIMEOUT status.
        var router = kit.spawn(varyingRouter());
        var famActor = kit.spawn(FamiliarActor.create(router));
        var probe = kit.<FamiliarActor.Report>createTestProbe();

        // Very tight envelope so test finishes fast
        var tinyTanks = new Tanks(10000, 3, 10, 0, 100);
        famActor.tell(new FamiliarActor.Summon(form, "did:wyrd:zA", "never complete",
            tinyTanks, probe.ref()));

        var report = probe.expectMessageClass(FamiliarActor.Report.class, Duration.ofSeconds(10));
        assertTrue(report.terminated());
        assertEquals(Familiar.Status.TIMEOUT, report.state().status());
    }

    // ── FamiliarActor — repeated identical output is caught as STUCK ────────

    @Test
    void familiar_detects_repeated_output_as_stuck() {
        var form = ThoughtForm.author("did:wyrd:zA", "stuck", "x", Set.of(), "");
        // Same exact text every turn — should trip the stuck detector on turn 2.
        var router = kit.spawn(alwaysRouter("I am stuck in a rut."));
        var famActor = kit.spawn(FamiliarActor.create(router));
        var probe = kit.<FamiliarActor.Report>createTestProbe();

        famActor.tell(new FamiliarActor.Summon(form, "did:wyrd:zA", "help",
            Tanks.defaults(), probe.ref()));

        var report = probe.expectMessageClass(FamiliarActor.Report.class, Duration.ofSeconds(5));
        assertEquals(Familiar.Status.STUCK, report.state().status());
    }

    // ── FamiliarActor — tool dispatch loop ──────────────────────────────────

    @Test
    void familiar_tool_call_is_dispatched_and_result_feeds_next_turn() {
        var form = ThoughtForm.author("did:wyrd:zA", "searcher",
            "Use web_search to find things.", Set.of("web_search"), "cite one source");

        // Router alternates: turn 1 emits a tool call; turn 2 emits DONE.
        var calls = new AtomicInteger(0);
        var router = kit.spawn(Behaviors.<InferenceRouter.Command>setup(ctx ->
            Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    String text;
                    if (calls.getAndIncrement() == 0) {
                        text = "Let me search.\n"
                            + "{\"tool\":\"web_search\",\"args\":{\"query\":\"wyrdsekai\"}}";
                    } else {
                        text = "Found: https://example.org — cites the project.\n"
                            + FamiliarActor.DONE_MARKER;
                    }
                    req.replyTo().tell(new InferenceRouter.InferOk(
                        req.requestId(), text, 60, 20));
                    return Behaviors.same();
                })
                .build()));

        var invoked = new AtomicReference<String>();
        var actor = kit.spawn(FamiliarActor.create(router));
        var probe = kit.<FamiliarActor.Report>createTestProbe();

        actor.tell(new FamiliarActor.SummonWithTools(
            form, "did:wyrd:zA", "find sources", Tanks.defaults(),
            (tool, args) -> {
                invoked.set(tool + ":" + args.get("query"));
                return "Result: https://example.org";
            },
            probe.ref()));

        var report = probe.expectMessageClass(FamiliarActor.Report.class, Duration.ofSeconds(5));
        assertEquals(Familiar.Status.DONE, report.state().status());
        assertEquals("web_search:wyrdsekai", invoked.get(),
            "dispatcher should have been called with the parsed tool args");
    }

    @Test
    void familiar_tool_not_in_surface_is_denied() {
        var form = ThoughtForm.author("did:wyrd:zA", "strict",
            "Only use allowed tools.", Set.of("library_search"), "");
        var calls = new AtomicInteger(0);
        var router = kit.spawn(Behaviors.<InferenceRouter.Command>setup(ctx ->
            Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    String text;
                    if (calls.getAndIncrement() == 0) {
                        // Tool not in surface
                        text = "{\"tool\":\"web_search\",\"args\":{\"query\":\"x\"}}";
                    } else {
                        text = "ok\n" + FamiliarActor.DONE_MARKER;
                    }
                    req.replyTo().tell(new InferenceRouter.InferOk(
                        req.requestId(), text, 40, 10));
                    return Behaviors.same();
                })
                .build()));

        var invoked = new AtomicBoolean(false);
        var actor = kit.spawn(FamiliarActor.create(router));
        var probe = kit.<FamiliarActor.Report>createTestProbe();

        actor.tell(new FamiliarActor.SummonWithTools(form, "did:wyrd:zA", "test",
            Tanks.defaults(),
            (tool, args) -> { invoked.set(true); return "should not run"; },
            probe.ref()));

        probe.expectMessageClass(FamiliarActor.Report.class, Duration.ofSeconds(5));
        assertFalse(invoked.get(),
            "dispatcher must not be called for tools outside declared surface");
    }

    // ── FamiliarActor — parent can cancel gracefully ────────────────────────

    @Test
    void familiar_cancel_terminates_with_dead_status() throws InterruptedException {
        var form = ThoughtForm.author("did:wyrd:zA", "slow", "Take your time.", Set.of(), "");
        // Router takes a while per response — gives us time to cancel
        var router = kit.spawn(delayedRouter("Still processing...", Duration.ofMillis(300)));
        var famActor = kit.spawn(FamiliarActor.create(router));
        var probe = kit.<FamiliarActor.Report>createTestProbe();

        famActor.tell(new FamiliarActor.Summon(form, "did:wyrd:zA", "lengthy analysis",
            Tanks.defaults(), probe.ref()));
        Thread.sleep(100);   // let one turn start
        famActor.tell(new FamiliarActor.Cancel());

        var report = probe.expectMessageClass(FamiliarActor.Report.class, Duration.ofSeconds(5));
        assertEquals(Familiar.Status.DEAD, report.state().status());
        assertTrue(report.narrativeSummary().contains("Cancelled"));
    }

    // ── Scripted router helpers ─────────────────────────────────────────────

    /**
     * Router that returns each response in order. After the list is exhausted,
     * it echoes the last response forever.
     */
    private static Behavior<InferenceRouter.Command> scriptedRouter(List<String> responses) {
        return Behaviors.setup(ctx -> {
            var cursor = new AtomicInteger(0);
            return Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    var i = Math.min(cursor.getAndIncrement(), responses.size() - 1);
                    var text = responses.get(i);
                    req.replyTo().tell(new InferenceRouter.InferOk(
                        req.requestId(), text, 50, 10));
                    return Behaviors.same();
                })
                .build();
        });
    }

    /** Router that varies output per turn so stuck-detection doesn't trigger. */
    private static Behavior<InferenceRouter.Command> varyingRouter() {
        return Behaviors.setup(ctx -> {
            var counter = new AtomicInteger(0);
            return Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    req.replyTo().tell(new InferenceRouter.InferOk(
                        req.requestId(), "Thinking about step " + counter.incrementAndGet(),
                        50, 10));
                    return Behaviors.same();
                })
                .build();
        });
    }

    /** Router that always returns the same response. */
    private static Behavior<InferenceRouter.Command> alwaysRouter(String response) {
        return Behaviors.receive(InferenceRouter.Command.class)
            .onMessage(InferenceRouter.ChatRequest.class, req -> {
                req.replyTo().tell(new InferenceRouter.InferOk(
                    req.requestId(), response, 50, 10));
                return Behaviors.same();
            })
            .build();
    }

    /** Router that always returns the same response, after a small delay. */
    private static Behavior<InferenceRouter.Command> delayedRouter(String response, Duration delay) {
        return Behaviors.setup(ctx ->
            Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    var replyTo = req.replyTo();
                    var requestId = req.requestId();
                    ctx.getSystem().scheduler().scheduleOnce(delay, () ->
                        replyTo.tell(new InferenceRouter.InferOk(requestId, response, 50, 10)),
                        ctx.getSystem().executionContext());
                    return Behaviors.same();
                })
                .build());
    }
}
