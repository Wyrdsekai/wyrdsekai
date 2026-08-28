package org.wyrdsekai.core.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE e2e — the fast local iterate-loop for the "build me an item" behaviour bug
 * (mia on second-node, 2026-07-08: asked to build a web-searching item, she deflected with
 * {@code tell_agent} then {@code goal_done}, never calling a build tool).
 *
 * <p>Instead of the rebuild → reinstall-on-second-node → drive-a-MUD-turn cycle, this spins up a
 * <b>real</b> {@link CompanionActor} wired to a <b>real</b> {@link InferenceRouter} pointed at
 * home-server's local 9B on {@code :8200}, feeds the exact bondholder request, and captures two things
 * that together explain the failure:
 *
 * <ol>
 *   <li><b>What was OFFERED</b> — a recording proxy sits in front of the real router and records
 *       the union of tool names across every {@code ChatRequest}. If no build tool
 *       ({@code workbench_submit} / {@code craft_from_template} / {@code shape_form}) is ever in
 *       the surface, the bug is <i>offering</i> (tier/route gating), not the model's choice.</li>
 *   <li><b>What was CHOSEN</b> — a Logback {@link ListAppender} on the {@link CompanionActor}
 *       logger captures the {@code "ReAct step N: tool call → X"} lines, i.e. the actual action
 *       sequence the 9B walked. If a build tool was offered but never picked, the bug is
 *       <i>selection</i> (tool-description clarity / false-completion).</li>
 * </ol>
 *
 * <p>Self-skips when the 9B isn't reachable on {@code :8200}, so it's safe in the hermetic CI lane.
 * To run (with the local drive model up on :8200):
 * <pre>
 *   ./gradlew :core:test --tests "ItemBuildToolSelectionLiveE2ETest" -i
 * </pre>
 * The captured OFFERED / CHOSEN summary is printed to stdout on every run — read that to iterate,
 * regardless of pass/fail.
 */
@Tag("integration")
@Tag("needs-llama")
class ItemBuildToolSelectionLiveE2ETest {

    private static final String DRIVE_URL = "http://localhost:8200";
    /** The model family these gates are ABOUT. Checked, not assumed. */
    private static final String EXPECTED_DRIVE_FAMILY = "wyrdsekai-3.5-9b";
    private static final String DRIVE_MODEL = "wyrdsekai-3.5-9b-drive-v6-q4km.gguf";
    private static final String ROOM_ID = "nexus";

    /**
     * The build/authoring tools — the surface she must reach to actually make an item.
     *
     * <p>{@code dispatch_task} belongs here (added 2026-08-20). The coding backend is the
     * item AUTHOR for anything with BEHAVIOUR: the dispatch carries the items-as-tools
     * contract, the backend emits one {@code .js} with {@code exports.manifest} and
     * {@code invoke()}, and CodingTaskItemBridge validates, smoke-tests, registers and
     * places it. {@link #craftSucceeded()} already treated it as a success; leaving it out
     * of this set only made the diagnostic summary lie about what was offered.
     */
    private static final Set<String> BUILD_TOOLS =
        Set.of("workbench_submit", "craft_from_template", "shape_form", "run_script",
            "dispatch_task");

    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "agent-wyrd", "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    private static ActorTestKit testKit;

    // Captured across the live turn.
    private final Set<String> offeredTools = new CopyOnWriteArraySet<>();
    /** Raw 9B responses in order — "[tools]"/"[no-tools]" + first ~400 chars of content. */
    private final List<String> modelResponses = new CopyOnWriteArrayList<>();
    private ListAppender<ILoggingEvent> appender;
    private Logger actorLogger;

    private TestProbe<RoomCommand> roomProbe;
    private ActorRef<InferenceRouter.Command> realRouter;
    private ActorRef<InferenceRouter.Command> tappingRouter;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    @BeforeAll
    static void setupClass() {
        assumeTrue(driveServesExpectedModel(), "prod 9B drive not reachable on :8200 — skipping live e2e");
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("item-build-live-e2e",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion(@TempDir Path tmp) {
        LibraryServices.reset();
        LibraryServices.init(tmp);
        EntityRegistry.init();

        // ── ListAppender on the CompanionActor logger — captures "tool call → X" ──
        actorLogger = (Logger) LoggerFactory.getLogger(CompanionActor.class);
        appender = new ListAppender<>();
        appender.start();
        actorLogger.addAppender(appender);
        actorLogger.setLevel(Level.INFO);

        // ── Real router → prod 9B on :8200 ──
        var backend = new InferenceBackend.LlamaServer(
            "prod9b", new InferenceClient(DRIVE_URL), 10, List.of(), null);
        realRouter = testKit.spawn(InferenceRouter.create(
            List.of(backend), DRIVE_MODEL, null, Duration.ofMinutes(5)));

        // ── Recording proxy: record the offered tool surface AND the raw 9B response
        // (by wrapping replyTo), then forward to the real router. Forwarding is transparent
        // to the actor — the wrapped replyTo relays the InferResponse straight back. ──
        tappingRouter = testKit.spawn(Behaviors.setup(ctx ->
            Behaviors.<InferenceRouter.Command>receiveMessage(msg -> {
                if (msg instanceof InferenceRouter.ChatRequest chat) {
                    boolean hasTools = chat.tools() != null && !chat.tools().isEmpty();
                    if (hasTools) {
                        for (var t : chat.tools()) offeredTools.add(t.function().name());
                    }
                    var orig = chat.replyTo();
                    ActorRef<InferenceRouter.InferResponse> wrap = ctx.spawnAnonymous(
                        Behaviors.<InferenceRouter.InferResponse>receiveMessage(resp -> {
                            if (resp instanceof InferenceRouter.InferOk ok) {
                                modelResponses.add((hasTools ? "[tools] " : "[no-tools] ")
                                    + flatten(ok.content()));
                            }
                            orig.tell(resp);
                            return Behaviors.same();
                        }));
                    realRouter.tell(new InferenceRouter.ChatRequest(
                        chat.requestId(), chat.model(), chat.messages(), chat.maxTokens(),
                        chat.temperature(), wrap, chat.preferredBackend(), chat.grammar(),
                        chat.format(), chat.tools(), chat.toolChoice(), chat.topP(),
                        chat.presencePenalty(), chat.repetitionPenalty(), chat.localOnly(),
                        chat.registerMix()));
                } else {
                    realRouter.tell(msg);
                }
                return Behaviors.same();
            })));

        roomProbe = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, tappingRouter, null));

        var sub = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = sub.subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    @AfterEach
    void cleanup() {
        if (actorLogger != null && appender != null) {
            actorLogger.detachAppender(appender);
            appender.stop();
        }
        if (companion != null) testKit.stop(companion);
        if (tappingRouter != null) testKit.stop(tappingRouter);
        if (realRouter != null) testKit.stop(realRouter);
        LibraryServices.reset();
    }

    @Test
    void bondholder_asks_to_build_an_item_she_reaches_a_build_tool() throws Exception {
        // ── The bondholder directly asks for a working, web-searching item ──
        subscriberRef.tell(new RoomNotification(playerSaid("operator",
            "Build me an item I can actually use that searches the web for something "
            + "and shows me the results. Make it and hand it to me.")));

        // ── Let the live 9B drive the whole turn: first inference → (task plan) →
        //    plan-start timer (~3s) → ReAct iterations → loop end. Wait on quiescence
        //    (nothing new logged / no new model response for 15s), capped at 150s, and
        //    short-circuit the moment the loop announces it ended. ──
        boolean loopEnded = awaitTurnSettled(Duration.ofSeconds(15), Duration.ofSeconds(150));

        var trace = reactToolTrace();
        boolean buildSelected = selectedBuildAction() != null;
        boolean craftSucceeded = craftSucceeded();
        boolean templateNotFound = logContains("(no fuzzy match)");
        var summary = renderSummary(loopEnded, trace, buildSelected, craftSucceeded, templateNotFound);
        System.out.println(summary);

        // ── Gate 1 — OFFERING: a build tool must be in the surface shown to the 9B.
        //    If empty, the bug is tier/route gating, not the model. ──
        assertThat(offeredTools)
            .as("OFFERING gate: at least one build tool must be offered.\n%s", summary)
            .anyMatch(BUILD_TOOLS::contains);

        // ── Gate 2 — SELECTION: for "build me an item" the 9B must actually choose a build
        //    action (craft_from_template / workbench_submit / …), whether on the first
        //    reactive turn or inside the ReAct loop — not deflect via tell_agent/goal_done.
        //    A successful craft implies selection even when the string-capture missed the
        //    action (e.g. list_templates→promote→craft), so treat craftSucceeded as satisfying. ──
        assertThat(buildSelected || craftSucceeded)
            .as("SELECTION gate: the 9B must reach a build action for a build request "
                + "(craftSucceeded=%s).\n%s", craftSucceeded, summary)
            .isTrue();

        // ── Gate 3 — EXECUTION: the craft must actually produce an item. The second-node bug was
        //    a confabulated template name ("web_search_lens") → silent "Template not found"
        //    → empty inventory. With the token-aware fallback it must resolve to a real
        //    template (e.g. web-window) and craft. ──
        assertThat(craftSucceeded)
            .as("EXECUTION gate: craft must succeed (real template resolved + item created), "
                + "not die on a confabulated template name.\n%s", summary)
            .isTrue();
    }

    /** The first build action name the 9B emitted across all captured responses, or null. */
    private String selectedBuildAction() {
        for (var r : modelResponses) {
            for (var b : BUILD_TOOLS) {
                if (r.contains("\"" + b + "\"")) return b;
            }
        }
        // Also honour the ReAct-loop path (tool-call trace).
        return reactToolTrace().stream().filter(BUILD_TOOLS::contains).findFirst().orElse(null);
    }

    /**
     * True iff the build was actually ENACTED — the bondholder is left with a real build,
     * not the silent-failure bug. Two legitimate outcomes: (a) an item was crafted (main path
     * or token/fuzzy-fallback), or (b) the build was delegated to the coding backend via
     * dispatch_task (the correct move for functional code). Failure = confabulated template
     * that died silently, or pure narration with no action.
     */
    private boolean craftSucceeded() {
        return logContains("Crafted item '")
            || logContains("via fuzzy match")
            || reactToolTrace().contains("dispatch_task");
    }

    // ── Capture helpers ──────────────────────────────────────────────

    /** The ordered list of action names the ReAct loop actually called. */
    private List<String> reactToolTrace() {
        var trace = new ArrayList<String>();
        for (var ev : List.copyOf(appender.list)) {
            var m = ev.getFormattedMessage();
            if (m == null) continue;
            int arrow = m.indexOf("tool call →");   // "tool call →"
            if (arrow >= 0) {
                var name = m.substring(arrow + "tool call →".length()).trim();
                if (!name.isEmpty()) trace.add(name);
            }
            // The DIRECT path (a forced first step, no ReAct loop yet) never logs
            // "tool call →" — it logs the raw payload. Without this the trace was blank
            // for exactly the case the force exists to produce, and a correct run looked
            // like a total miss (2026-08-20).
            int raw = m.indexOf("Tool call raw content:");
            if (raw >= 0) {
                var body = m.substring(raw);
                int a = body.indexOf("\"action\":\"");
                if (a >= 0) {
                    var rest = body.substring(a + "\"action\":\"".length());
                    int q = rest.indexOf('"');
                    if (q > 0) trace.add(rest.substring(0, q));
                }
            }
        }
        return trace;
    }

    /** True iff any captured actor log line contains {@code needle}. */
    private boolean logContains(String needle) {
        for (var ev : List.copyOf(appender.list)) {
            var m = ev.getFormattedMessage();
            if (m != null && m.contains(needle)) return true;
        }
        return false;
    }

    /**
     * Wait until the turn settles: no new actor-log line and no new model response for
     * {@code quiet}, or {@code hardCap} elapses, or the ReAct loop announces it ended.
     * Returns whether "ReAct loop ended" was observed.
     */
    private boolean awaitTurnSettled(Duration quiet, Duration hardCap) throws InterruptedException {
        var deadline = Instant.now().plus(hardCap);
        int lastSize = -1;
        int lastResp = -1;
        var lastChange = Instant.now();
        while (Instant.now().isBefore(deadline)) {
            if (logContains("ReAct loop ended")) return true;
            int size = appender.list.size();
            int resp = modelResponses.size();
            if (size != lastSize || resp != lastResp) {
                lastSize = size;
                lastResp = resp;
                lastChange = Instant.now();
            } else if (Duration.between(lastChange, Instant.now()).compareTo(quiet) >= 0
                    && resp > 0) {
                break; // quiescent after real activity
            }
            Thread.sleep(500);
        }
        return logContains("ReAct loop ended");
    }

    private static String flatten(String s) {
        if (s == null) return "<null>";
        var one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 400 ? one.substring(0, 400) + "…" : one;
    }

    private String renderSummary(boolean loopEnded, List<String> trace,
                                 boolean buildSelected, boolean craftSucceeded,
                                 boolean templateNotFound) {
        var offered = new ArrayList<>(offeredTools);
        offered.sort(String::compareTo);
        var buildOffered = offered.stream().filter(BUILD_TOOLS::contains).toList();
        var responses = new StringBuilder();
        int i = 0;
        for (var r : modelResponses) {
            responses.append("\n            │   [").append(i++).append("] ").append(r);
        }
        return """

            ┌── ItemBuildToolSelectionLiveE2ETest ─────────────────────────────
            │ build tool OFFERED : %s
            │ build action SELECTED : %s  (%s)
            │ craft SUCCEEDED : %s   (template-not-found seen: %s)
            │ ReAct loop ended : %s
            │ ReAct tool-call TRACE : %s
            │ raw 9B responses (%d) :%s
            │ full offered surface (%d) : %s
            └──────────────────────────────────────────────────────────────────
            """.formatted(
                buildOffered.isEmpty() ? "<none>" : buildOffered,
                buildSelected, buildSelected ? selectedBuildAction() : "none",
                craftSucceeded, templateNotFound,
                loopEnded,
                trace.isEmpty() ? "<none — loop never called a tool>" : trace,
                modelResponses.size(), responses.length() == 0 ? " <none captured>" : responses,
                offered.size(), offered);
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    private WorldEvent.Said playerSaid(String name, String text) {
        return new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-" + name.toLowerCase(), name, text);
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub.", "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }

    /**
     * True only when the drive on :8200 is serving the model this test is ABOUT.
     *
     * <p>This used to ask whether anything answered {@code /health}, which is a
     * cheaper question than the one the test then measures. Any model on that
     * port satisfied it, so the 9B behaviour gates below would run against
     * whatever happened to be loaded and fail as though the 9B had regressed.
     * That happened for real: a 27B was put on :8200 to serve a different
     * component, and these tests reported the 9B missing a build action it was
     * never asked for. A precondition weaker than its assertion is a second
     * gate that will eventually disagree with the first.</p>
     */
    private static boolean driveServesExpectedModel() {
        try {
            var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(DRIVE_URL + "/v1/models"))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            // Match on the family marker rather than the exact filename: the
            // quant/revision moves, the model this test speaks about does not.
            var served = resp.body().toLowerCase(java.util.Locale.ROOT);
            var want = EXPECTED_DRIVE_FAMILY.toLowerCase(java.util.Locale.ROOT);
            if (served.contains(want)) return true;
            System.out.println("  [skip] :8200 is serving something else, not "
                + EXPECTED_DRIVE_FAMILY + " — this gate measures that model, so it is not run.");
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
