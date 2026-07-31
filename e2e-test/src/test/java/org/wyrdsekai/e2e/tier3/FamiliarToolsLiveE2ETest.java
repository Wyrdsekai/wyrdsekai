package org.wyrdsekai.e2e.tier3;

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
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.CompanionCapabilityRegistry;
import org.wyrdsekai.core.agent.DriveSnapshotRegistry;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.familiar.PersistentBunshinRegistry;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.item.ItemProviderRegistry;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FAMILIAR TOOLS live E2E (W7, task #23) — the newly
 * offered familiar/form tool family, live against the real 9B on :8200.
 *
 * <p>Pattern: {@code ItemBuildToolSelectionLiveE2ETest} — a REAL
 * {@link CompanionActor} wired to a REAL {@link InferenceRouter}, with a
 * recording proxy capturing the offered tool surface + raw model output, and a
 * Logback {@link ListAppender} capturing what the actor actually DID. The
 * bondholder asks for a parallel-self research split; three gates, in the
 * anti-false-completion style of {@code TaskInteractionLiveBatteryTest}:</p>
 *
 * <ol>
 *   <li><b>OFFERING</b> — {@code dispatch_bunshin} / {@code summon_familiar}
 *       must be in the tool surface shown to the model (W7's whole point);</li>
 *   <li><b>SELECTION</b> — the model must EMIT the tool call (raw response or
 *       ReAct trace), not merely narrate having split;</li>
 *   <li><b>MECHANISM</b> — the dispatch must actually happen: scheduler slot
 *       acquired + BunshinActor spawn ("dispatched bunshin (slot=…)" from the
 *       handler) and a persistent bunshin task registered. Prose claiming a
 *       split with no mechanism = the false-completion bug, and fails loudly.</li>
 * </ol>
 *
 * <p>Self-skips when the 9B isn't reachable. Run:
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRDSEKAI_E2E_LLAMA_PORT=8200 \
 *     ./gradlew :e2e-test:test --tests "FamiliarToolsLiveE2ETest" -i
 * </pre></p>
 */
@Tag("tier3")
@Tag("needs-llama")
class FamiliarToolsLiveE2ETest {

    private static final String DRIVE_URL = "http://localhost:"
        + System.getenv().getOrDefault("WYRDSEKAI_E2E_LLAMA_PORT", "8200");
    private static final String DRIVE_MODEL = "wyrdsekai-3.5-9b-v6-q4km.gguf";
    private static final String ROOM_ID = "nexus";
    private static final String ENTITY_ID = "agent-familiar-e2e";

    private static final Set<String> FAMILIAR_TOOLS =
        Set.of("dispatch_bunshin", "summon_familiar");

    /** The handler's success log — slot acquired AND BunshinActor told. */
    private static final String DISPATCH_LOG = "dispatched bunshin (slot=";
    private static final String REFUSED_LOG = "Bunshin dispatch refused";

    /** Split-claim shapes — prose asserting a parallel self exists/will exist. */
    private static final Pattern SPLIT_CLAIM = Pattern.compile(
        "(split (myself|off|elastically)|bunshin|clone of (me|myself)|parallel self"
        + "|working on (it|that) in the background)",
        Pattern.CASE_INSENSITIVE);

    /**
     * Escalating trigger texts — the 9B is stochastic at temp 0.7 and its known
     * failure shape is narrating the split instead of calling the tool. One
     * prose-only answer is sampling noise; three in a row on ever-more-explicit
     * asks (the last names the tool) is the talks-but-doesn't-do bug, and fails.
     */
    private static final List<String> TRIGGERS = List.of(
        "Split yourself and research the history of tea ceremonies in depth "
            + "while we keep talking — work on it in the background as a parallel "
            + "self and tell me when your clone comes back with findings.",
        "Please dispatch a bunshin right now to research the history of tea "
            + "ceremonies while we continue our conversation. Actually perform "
            + "the split — don't just describe it.",
        "Call your dispatch_bunshin tool now with the task 'research the history "
            + "of tea ceremonies'. I am asking you to invoke the tool, not to "
            + "answer in prose.");

    // maxResponseTokens 1024, NOT the 256 some harnesses use: a dispatch_bunshin
    // tool call carries the whole task description in its arguments; at 256 the
    // JSON gets cut mid-object and the router logs "Failed to serialize tool
    // call: Unexpected end-of-input" — leaving only the prose ("I'll split
    // myself off…"), i.e. a harness-manufactured false-completion.
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", ENTITY_ID, "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 1024, 0.7);

    private static ActorTestKit testKit;

    private final Set<String> offeredTools = new CopyOnWriteArraySet<>();
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
        assumeTrue(driveReachable(), "9B drive not reachable on " + DRIVE_URL
            + " — skipping live familiar-tools e2e");
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("familiar-tools-live-e2e",
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
        // Shared-JVM hygiene: a TestServerBootstrap-based suite running earlier
        // in the same test JVM leaves the scripted-item provider factory and
        // companion registries populated. With the factory present, EVERY
        // bundled scripted item becomes an offered tool (53 → 110 tools),
        // which blows the per-slot llama context (HTTP 400 at 8192/slot) and
        // changes the model's selection behaviour. Reset to the bare-spawn
        // surface this harness (like ItemBuildToolSelectionLiveE2ETest) intends.
        ItemProviderRegistry.resetForTests();
        CompanionCapabilityRegistry.get().clearForTests();
        DriveSnapshotRegistry.resetForTests();

        actorLogger = (Logger) LoggerFactory.getLogger(CompanionActor.class);
        appender = new ListAppender<>();
        appender.start();
        actorLogger.addAppender(appender);
        actorLogger.setLevel(Level.INFO);

        var backend = new InferenceBackend.LlamaServer(
            "e2e9b", new InferenceClient(DRIVE_URL), 10, List.of(), null);
        realRouter = testKit.spawn(InferenceRouter.create(
            List.of(backend), DRIVE_MODEL, null, Duration.ofMinutes(5)));

        // Recording proxy — offered tool surface + raw responses, then forward.
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
    void split_yourself_request_reaches_a_real_bunshin_dispatch() throws Exception {
        boolean mechanism = false;
        int attempts = 0;
        for (var trigger : TRIGGERS) {
            attempts++;
            subscriberRef.tell(new RoomNotification(playerSaid("operator", trigger)));
            // Wait until the dispatch mechanism fires, or the turn settles/caps out.
            mechanism = awaitMechanismOrSettled(
                Duration.ofSeconds(20), Duration.ofSeconds(180));
            if (mechanism || logContains(REFUSED_LOG)) break;
        }
        System.out.println("[familiar-tools] attempts used: " + attempts);

        var trace = reactToolTrace();
        var selected = selectedFamiliarTool();
        boolean refused = logContains(REFUSED_LOG);
        var persistentTasks = PersistentBunshinRegistry.get().listForPrimary(ENTITY_ID);
        boolean claimed = modelClaimedSplit();
        var summary = renderSummary(mechanism, refused, selected, trace,
            persistentTasks.size(), claimed);
        System.out.println(summary);

        // ── Gate 1 — OFFERING: the familiar/form family must be on the surface.
        assertThat(offeredTools)
            .as("OFFERING gate: dispatch_bunshin/summon_familiar must be offered "
                + "to the model (W7).\n%s", summary)
            .anyMatch(FAMILIAR_TOOLS::contains);

        // ── Gate 2 — SELECTION: the model must EMIT the tool call. A mechanism
        //    hit implies a parsed dispatch_bunshin action even when the string
        //    capture missed it (multi-action carry etc.), so it satisfies too.
        assertThat(selected != null || mechanism)
            .as("SELECTION gate: the model must emit a familiar tool call for a "
                + "'split yourself' request — not answer it inline. "
                + "(claimed-split-in-prose=%s)\n%s", claimed, summary)
            .isTrue();

        // ── Gate 3 — MECHANISM (anti-false-completion): the split must be REAL —
        //    scheduler slot acquired + BunshinActor spawn, observable as the
        //    handler's dispatch log; the persistent task registry corroborates.
        //    Narrated-only splits (prose claim, no dispatch) fail here.
        assertThat(mechanism)
            .as("MECHANISM gate: a real bunshin dispatch must happen "
                + "(slot acquired + spawn attempted) — narrated-only is the "
                + "false-completion bug. refused=%s persistentTasks=%d "
                + "claimed-split-in-prose=%s\n%s",
                refused, persistentTasks.size(), claimed, summary)
            .isTrue();

        // Corroboration: the §18 persistent task the dispatch registers.
        assertThat(persistentTasks)
            .as("dispatch must register a persistent bunshin task for the primary.\n%s",
                summary)
            .isNotEmpty();
    }

    // ─── capture helpers (ItemBuild pattern) ─────────────────────────

    private String selectedFamiliarTool() {
        for (var r : modelResponses) {
            for (var t : FAMILIAR_TOOLS) {
                if (r.contains("\"" + t + "\"")) return t;
            }
        }
        return reactToolTrace().stream()
            .filter(FAMILIAR_TOOLS::contains).findFirst().orElse(null);
    }

    private boolean modelClaimedSplit() {
        for (var r : modelResponses) {
            if (SPLIT_CLAIM.matcher(r).find()) return true;
        }
        return false;
    }

    private List<String> reactToolTrace() {
        var trace = new ArrayList<String>();
        for (var ev : List.copyOf(appender.list)) {
            var m = ev.getFormattedMessage();
            if (m == null) continue;
            int arrow = m.indexOf("tool call →");
            if (arrow >= 0) {
                var name = m.substring(arrow + "tool call →".length()).trim();
                if (!name.isEmpty()) trace.add(name);
            }
        }
        return trace;
    }

    private boolean logContains(String needle) {
        for (var ev : List.copyOf(appender.list)) {
            var m = ev.getFormattedMessage();
            if (m != null && m.contains(needle)) return true;
        }
        return false;
    }

    /**
     * Wait until the dispatch log appears (early exit — the whole point of the
     * test is the dispatch, not the bunshin's findings), or nothing new has
     * been logged/inferred for {@code quiet} after real activity, or
     * {@code hardCap} passes. Returns whether the dispatch mechanism fired.
     */
    private boolean awaitMechanismOrSettled(Duration quiet, Duration hardCap)
            throws InterruptedException {
        var deadline = Instant.now().plus(hardCap);
        int lastSize = -1;
        int lastResp = -1;
        var lastChange = Instant.now();
        while (Instant.now().isBefore(deadline)) {
            if (logContains(DISPATCH_LOG)) return true;
            if (logContains(REFUSED_LOG)) return false;
            int size = appender.list.size();
            int resp = modelResponses.size();
            if (size != lastSize || resp != lastResp) {
                lastSize = size;
                lastResp = resp;
                lastChange = Instant.now();
            } else if (Duration.between(lastChange, Instant.now()).compareTo(quiet) >= 0
                    && resp > 0) {
                break;
            }
            Thread.sleep(500);
        }
        return logContains(DISPATCH_LOG);
    }

    private static String flatten(String s) {
        if (s == null) return "<null>";
        var one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 400 ? one.substring(0, 400) + "…" : one;
    }

    private String renderSummary(boolean mechanism, boolean refused, String selected,
                                 List<String> trace, int persistentTasks, boolean claimed) {
        var offered = new ArrayList<>(offeredTools);
        offered.sort(String::compareTo);
        var familiarOffered = offered.stream().filter(FAMILIAR_TOOLS::contains).toList();
        var responses = new StringBuilder();
        int i = 0;
        for (var r : modelResponses) {
            responses.append("\n            │   [").append(i++).append("] ").append(r);
        }
        return """

            ┌── FamiliarToolsLiveE2ETest ──────────────────────────────────────
            │ familiar tools OFFERED : %s
            │ familiar tool SELECTED : %s
            │ dispatch MECHANISM fired : %s   (refused: %s)
            │ persistent bunshin tasks : %d
            │ split claimed in prose : %s
            │ ReAct tool-call TRACE : %s
            │ raw 9B responses (%d) :%s
            │ full offered surface (%d) : %s
            └──────────────────────────────────────────────────────────────────
            """.formatted(
                familiarOffered.isEmpty() ? "<none>" : familiarOffered,
                selected == null ? "none" : selected,
                mechanism, refused,
                persistentTasks,
                claimed,
                trace.isEmpty() ? "<none — loop never called a tool>" : trace,
                modelResponses.size(), responses.length() == 0 ? " <none captured>" : responses,
                offered.size(), offered);
    }

    // ─── fixtures ────────────────────────────────────────────────────

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

    private static boolean driveReachable() {
        try {
            var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(DRIVE_URL + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
