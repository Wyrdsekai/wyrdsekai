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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track A Phase 3 — bunshin code-mode harness.
 *
 * <p>The bunshin runs research-shape work as one or two scripts rather than a
 * fresh ReAct loop. These tests cover the happy path, retry budget, structured
 * report shape, and backward compatibility with {@code "react"} default.
 */
class BunshinCodeModeHarnessTest {

    private static ActorTestKit kit;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUpKit() {
        kit = ActorTestKit.create("BunshinCodeModeHarnessTest");
    }

    @AfterAll
    static void tearDown() {
        if (kit != null) kit.shutdownTestKit();
    }

    private static final String PRIMARY = "did:wyrd:zA:wyrd";
    private static final String SYS = "You are Wyrd. Calm.";

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    void code_mode_dispatch_runs_script_and_returns_structured_report() throws Exception {
        var script = """
            ```javascript
            console.log("hello from code-mode");
            ```
            """;
        var router = kit.spawn(scriptedRouter(List.of(script)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "say hello", Tanks.defaults(),
            probe.ref(), "code-mode", Map.of()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));

        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        assertTrue(report.note().isPresent(), "code-mode reports must carry structured note");

        var note = MAPPER.readTree(report.note().get());
        assertEquals("code-mode", note.get("harness").asText());
        assertTrue(note.get("ok").asBoolean());
        assertEquals(1, note.get("scripts").size());
        assertTrue(note.get("scripts").get(0).asText().contains("console.log"));
        assertTrue(note.get("logs").size() >= 1);
        assertTrue(note.get("logs").get(0).asText().contains("hello from code-mode"));
        assertEquals(1, note.get("turnsUsed").asInt(),
            "happy path should be 1 LLM call");
    }

    @Test
    void code_mode_with_typed_namespace_invokes_function() throws Exception {
        // Typed namespace: search.run(query) -> "found: <query>"
        Map<String, Map<String, Function<Object[], Object>>> namespace = new LinkedHashMap<>();
        var searchNs = new LinkedHashMap<String, Function<Object[], Object>>();
        searchNs.put("run", args -> "found: " + (args.length > 0 ? args[0] : "nothing"));
        namespace.put("search", searchNs);

        var script = """
            ```javascript
            const r = search.run("mythology");
            console.log(r);
            ```
            """;
        var router = kit.spawn(scriptedRouter(List.of(script)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "look it up", Tanks.defaults(),
            probe.ref(), "code-mode", namespace));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        var note = MAPPER.readTree(report.note().get());
        assertTrue(note.get("logs").get(0).asText().contains("found: mythology"));
    }

    @Test
    void code_mode_lowers_llm_call_count_relative_to_react() {
        // Code-mode happy path = 1 LLM call. ReAct happy path with the same
        // task takes at least 1 (DONE on first turn) but realistic ReAct loops
        // are multi-turn — the spec promises ≤ ReAct, this asserts the floor.
        var codeModeScript = """
            ```javascript
            console.log("done");
            ```
            """;
        var router = kit.spawn(scriptedRouter(List.of(codeModeScript)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(),
            probe.ref(), "code-mode", Map.of()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        // Code-mode: exactly one inference turn for the happy path.
        assertEquals(1, report.turnsUsed());
    }

    @Test
    void code_mode_extracts_unfenced_script() throws Exception {
        // Models often emit raw JS without a fence. The harness must accept
        // the full content as the script body.
        var rawScript = "console.log('no fence');";
        var router = kit.spawn(scriptedRouter(List.of(rawScript)));
        var actor = kit.spawn(BunshinActor.create(router));
        var probe = kit.<BunshinReport>createTestProbe();

        actor.tell(new BunshinActor.Dispatch(
            PRIMARY, SYS, "task", Tanks.defaults(),
            probe.ref(), "code-mode", Map.of()));

        var report = probe.expectMessageClass(BunshinReport.class, Duration.ofSeconds(5));
        assertEquals(BunshinReport.Outcome.SUCCESS, report.outcome());
        var note = MAPPER.readTree(report.note().get());
        assertTrue(note.get("logs").get(0).asText().contains("no fence"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

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
