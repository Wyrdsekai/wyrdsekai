package org.wyrdsekai.core.familiar;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tools path — which no test covered, which is why a prose-only bunshin
 * shipped while said it held tool calls.
 *
 * <p>Every other {@code Bunshin*Test} uses the back-compat {@code Dispatch}
 * constructors that leave {@code tools} null, so they all pass against an actor
 * that can do nothing. These drive the 7-arg form with a real executor.</p>
 */
class BunshinToolExecutionTest {

    private static ActorTestKit kit;

    @BeforeAll static void up() { kit = ActorTestKit.create("BunshinToolExecutionTest"); }
    @AfterAll  static void down() { if (kit != null) kit.shutdownTestKit(); }

    private static final String PRIMARY = "did:wyrd:zA:wyrd";
    private static final String PROMPT = "You are Wyrd.";

    private static final List<InferenceClient.ToolDefinition> TOOLS =
        org.wyrdsekai.core.agent.ActionToolBuilder.buildFromNames(List.of("create_room"));

    private static Behavior<InferenceRouter.Command> scripted(List<String> turns) {
        return Behaviors.setup(ctx -> {
            var cursor = new AtomicInteger(0);
            return Behaviors.receive(InferenceRouter.Command.class)
                .onMessage(InferenceRouter.ChatRequest.class, req -> {
                    var i = Math.min(cursor.getAndIncrement(), turns.size() - 1);
                    req.replyTo().tell(new InferenceRouter.InferOk(
                        req.requestId(), turns.get(i), 80, 20));
                    return Behaviors.same();
                })
                .build();
        });
    }

    private static final String CREATE_ROOM =
        "{\"action\":\"create_room\",\"name\":\"greenhouse\","
        + "\"description\":\"A room full of plants\"}";

    @Test
    @DisplayName("a tool call reaches the executor instead of becoming prose")
    void toolCallIsForwarded() {
        var seen = new CopyOnWriteArrayList<String>();
        var router = kit.spawn(scripted(List.of(
            CREATE_ROOM,
            "Built it. " + BunshinActor.DONE_MARKER)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, PROMPT, "make a greenhouse",
            Tanks.defaults(), probe.ref(), "react", null, TOOLS,
            raw -> {
                seen.add(raw);
                // Stand in for CompanionActor.onBunshinToolRequest succeeding.
                actor.tell(new BunshinActor.ToolResultCame(
                    true, "create_room done.", List.of("item-1")));
            }));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(10));
        assertEquals(1, seen.size(), "the tool call must be forwarded exactly once");
        assertTrue(seen.get(0).contains("create_room"), "raw content forwarded: " + seen.get(0));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        assertFalse(report.summary().startsWith("[unverified claim"),
            "real work was done, so the prose-only excuse must NOT fire — got: "
                + report.summary());
        assertTrue(report.newItemIds().contains("item-1"),
            "§302 provenance: ids the bunshin authored must reach the report, "
            + "not List.of()");
    }

    @Test
    @DisplayName("a FAILED tool does not become a success claim")
    void failedToolIsNotNarratedAsSuccess() {
        // This is the live home-server defect in miniature: dispatch did nothing, but
        // the primary said "done", so she announced a greenhouse that did not
        // exist. With an honest failure the completion gate must still bite.
        var router = kit.spawn(scripted(List.of(
            CREATE_ROOM,
            "The greenhouse is built. " + BunshinActor.DONE_MARKER)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(PRIMARY, PROMPT, "make a greenhouse",
            Tanks.defaults(), probe.ref(), "react", null, TOOLS,
            raw -> actor.tell(new BunshinActor.ToolResultCame(
                false, "create_room is not something I can carry out from here.",
                List.of()))));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(10));
        assertTrue(report.summary().startsWith("[unverified claim"),
            "nothing was built, so claiming it must be gated — got: " + report.summary());
    }

    @Test
    @DisplayName("with no tools the actor still works — back-compat is intact")
    void proseOnlyStillWorks() {
        var router = kit.spawn(scripted(List.of("Thought about it. " + BunshinActor.DONE_MARKER)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();
        actor.tell(new BunshinActor.Dispatch(PRIMARY, PROMPT, "reflect",
            Tanks.defaults(), probe.ref()));
        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(10));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
    }
}
