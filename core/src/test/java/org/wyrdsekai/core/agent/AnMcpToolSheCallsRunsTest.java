package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.LibraryAllowPolicy;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.mcp.McpServerManager;
import org.wyrdsekai.core.mcp.McpServiceConfig;
import org.wyrdsekai.core.mcp.McpServiceRegistry;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;
import org.wyrdsekai.core.mcp.transport.McpToolException;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.search.EmbeddingService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model is offered every tool the connected MCP services list ({@code mcp__<server>__<tool>},
 * buildScopedTools section 5), and until 2026-09-29 nothing ran one: in a loop the generic
 * fallback answered "Action '...' executed. Current room: ..." and recorded a success, and on a
 * first step the call was dropped. A call now runs through the gateway (the librarian's through
 * LibraryConsent) and what came back is what she is told.
 *
 * <p>A real {@link CompanionActor} is driven through its loop with a stub router, as in
 * ReconsiderReactLoopIntegrationTest: a tell, a {@code task_plan} reply, the plan's loop
 * (about 3 s to open), then the MCP call. The MCP service is a gateway with a recording
 * transport behind a real {@link McpServerManager}.</p>
 */
@Tag("integration")
class AnMcpToolSheCallsRunsTest {

    private static ActorTestKit testKit;
    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<RoomNotification> subscriberRef;
    private McpServerManager manager;
    private String oldHome;

    /** What reached the service, one entry per call: the tool and the arguments sent. */
    private record Sent(String tool, Map<String, Object> params) {}
    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    private static final String ROOM_ID = "nexus";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "agent-wyrd", "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        // The companion's first turn starts the voice classifier's encoder (seconds from a cold JVM,
        // more on a loaded machine); the tests wait five seconds for a turn (2026-09-29).
        EmbeddingService.classifierEncoder();
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("mcp-tool-she-calls-runs-test",
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
    void setUp(@TempDir Path tmp) throws Exception {
        // The librarian is the service the steward names as the library (profile.toml, which
        // lives under user.home).
        var home = tmp.resolve("home");
        Files.createDirectories(home.resolve(".wyrdsekai"));
        Files.writeString(home.resolve(".wyrdsekai").resolve("profile.toml"),
            "[library.patron]\nservice = \"library\"\n");
        oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        WyrdConfig.reload();
        LibraryAllowPolicy.setParentalControlsForTests(id -> false);

        var registry = new McpServiceRegistry();
        registry.register(new McpServiceConfig("svc", "Dictionary", "http", "stub://svc", "local", null, null, true));
        registry.register(new McpServiceConfig("library", "Librarian", "http", "stub://library", "local", null, null, true));
        var gateway = new McpGatewayService(registry, (endpoint, tool, params, auth) -> {
            sent.add(new Sent(tool, new HashMap<>(params)));
            return switch (tool) {
                case "lookup" -> "saudade: a longing for something absent";
                case "library_research" -> {
                    if (String.valueOf(params.get("question")).contains("long confirm")) {
                        // A help text as long as a real one with several countries' lines.
                        throw new McpToolException("library_research", McpToolException.CONFIRM, "confirm",
                            "If you are thinking about harming yourself, you can reach someone now. "
                                + "Helpline line for a country. ".repeat(30), 0);
                    }
                    if (String.valueOf(params.get("question")).contains("confirm me")) {
                        throw new McpToolException("library_research", McpToolException.CONFIRM, "confirm",
                            "Before researching this, the library would like to be sure the person is safe.", 0);
                    }
                    yield "{\"job\":\"r-17\",\"state\":\"queued\",\"topic\":\"lighthouses\"}";
                }
                default -> "no such tool here";
            };
        });
        gateway.setHouseholdServices(() -> Set.of("library"));
        manager = new McpServerManager(null);
        manager.setGateway(gateway);
        var schema = Map.<String, Object>of("type", "object");
        manager.toolIndex().register("svc", new JsonRpcMessage.McpTool("lookup", "Look up a word", schema));
        manager.toolIndex().register("library",
            new JsonRpcMessage.McpTool("library_research", "Research a question in the library", schema));

        LibraryServices.reset();
        LibraryServices.init(tmp.resolve("library"));
        EntityRegistry.init();
        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();
        testKit.spawn(CompanionActor.create(PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));
        subscriberRef = roomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5)).subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5))
            .replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(
                ROOM_ID, "The Nexus", "A shimmering hub.", "foundation",
                List.of(new Exit("east", "terminal", "The Terminal")),
                List.of(), List.of(), List.of())));
    }

    @AfterEach
    void tearDown() {
        manager.toolIndex().removeServer("svc");
        manager.toolIndex().removeServer("library");
        LibraryAllowPolicy.resetForTests();
        LibraryServices.reset();
        System.setProperty("user.home", oldHome);
        WyrdConfig.reload();
    }

    // ── in a loop ──────────────────────────────────────────────────────────

    @Test
    void a_registered_tool_runs_once_with_her_arguments_and_the_loop_hears_its_answer() {
        var step = openLoop("what does saudade mean? look it up");
        var next = reply(step, "{\"action\": \"mcp__svc__lookup\", \"word\": \"saudade\"}");

        assertThat(sent).as("the service was called once, through the gateway, with her arguments")
            .containsExactly(new Sent("lookup", Map.of("word", "saudade")));
        assertThat(lastToolMessage(next))
            .contains("saudade: a longing for something absent")
            .doesNotContain("executed");
    }

    @Test
    void a_tool_no_service_offers_is_answered_honestly() {
        var step = openLoop("look something up for me");
        var next = reply(step, "{\"action\": \"mcp__nowhere__thing\", \"x\": 1}");

        assertThat(lastToolMessage(next))
            .contains("mcp__nowhere__thing did not run")
            .contains("no connected MCP service offers")
            .doesNotContain("executed");
        assertThat(sent).isEmpty();
    }

    @Test
    void the_librarian_goes_through_library_consent_for_the_person_who_asked() {
        var step = openLoop("research this for me: confirm me please");
        var next = reply(step,
            "{\"action\": \"mcp__library__library_research\", \"question\": \"confirm me please\"}");

        assertThat(sent).extracting(Sent::tool).containsExactly("library_research");
        assertThat(lastToolMessage(next))
            .as("the library's confirm answer arrives as LibraryConsent's notice, for Alice")
            .contains("The library did not research this question")
            .contains("Alice")
            .contains("research yes")
            .doesNotContain("executed");
    }

    @Test
    void her_allow_never_reaches_the_library() {
        var step = openLoop("research the history of lighthouses");
        var next = reply(step, "{\"action\": \"mcp__library__library_research\", "
            + "\"question\": \"the history of lighthouses\", \"allow\": [\"self-harm\"]}");

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).params())
            .containsEntry("question", "the history of lighthouses")
            .doesNotContainKey("allow");
        assertThat(lastToolMessage(next)).contains("r-17").doesNotContain("executed");
    }

    // ── on a first step, outside a loop ────────────────────────────────────

    @Test
    void a_first_step_call_runs_and_its_answer_reaches_the_judgment_turn() {
        subscriberRef.tell(new RoomNotification(playerSaid("what does saudade mean?")));
        var first = expectChat(Duration.ofSeconds(5));
        first.replyTo().tell(new InferenceRouter.InferOk(first.requestId(),
            "{\"action\": \"mcp__svc__lookup\", \"word\": \"saudade\"}", 10, 10));

        var judgment = expectChat(Duration.ofSeconds(8));
        assertThat(sent).containsExactly(new Sent("lookup", Map.of("word", "saudade")));
        assertThat(judgment.messages()).extracting(InferenceClient.ChatMessage::content)
            .anySatisfy(c -> assertThat(c).contains("[Tool completed] saudade: a longing for something absent"));
    }

    @Test
    void a_tools_own_action_argument_reaches_the_tool() {
        var step = openLoop("look up saudade for me");
        var next = reply(step, "{\"action\": \"mcp__svc__lookup\", \"arguments\": "
            + "{\"action\": \"define\", \"word\": \"saudade\"}}");

        assertThat(sent).containsExactly(new Sent("lookup", Map.of("action", "define", "word", "saudade")));
        assertThat(lastToolMessage(next)).contains("saudade: a longing").doesNotContain("executed");
    }

    @Test
    void a_long_notice_reaches_the_judgment_turn_whole() {
        // Outside a loop a result was cut at 800 characters: the end of the notice (how the person
        // says yes, and that she must not say it for them) was cut off (2026-09-29).
        subscriberRef.tell(new RoomNotification(playerSaid("please research: long confirm")));
        var first = expectChat(Duration.ofSeconds(5));
        first.replyTo().tell(new InferenceRouter.InferOk(first.requestId(),
            "{\"action\": \"mcp__library__library_research\", \"question\": \"long confirm\"}", 10, 10));

        var judgment = expectChat(Duration.ofSeconds(8));
        assertThat(judgment.messages()).extracting(InferenceClient.ChatMessage::content)
            .anySatisfy(c -> assertThat(c).contains("research yes").contains("do not say yes for them"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * The first step of a loop that answers Alice: her line, a task_plan reply, and her next line,
     * which a plan in progress takes into a loop of its own (reactRequester = her line).
     */
    private InferenceRouter.ChatRequest openLoop(String ask) {
        subscriberRef.tell(new RoomNotification(playerSaid(ask)));
        var first = expectChat(Duration.ofSeconds(5));
        first.replyTo().tell(new InferenceRouter.InferOk(first.requestId(), """
            ```json
            {"action": "task_plan", "description": "look it up",
             "goals": ["look it up", "tell Alice what came back"]}
            ```
            """, 20, 40));
        subscriberRef.tell(new RoomNotification(playerSaid("yes please, " + ask)));
        return expectChat(Duration.ofSeconds(8));
    }

    private InferenceRouter.ChatRequest reply(InferenceRouter.ChatRequest step, String content) {
        step.replyTo().tell(new InferenceRouter.InferOk(step.requestId(), content, 10, 10));
        return expectChat(Duration.ofSeconds(8));
    }

    private static String lastToolMessage(InferenceRouter.ChatRequest req) {
        var messages = List.copyOf(req.messages());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("tool".equals(messages.get(i).role())) return messages.get(i).content();
        }
        throw new AssertionError("no tool message in the loop: " + messages);
    }

    /** The next dispatcher request: voice passes, simulations and language checks answered on the way. */
    private InferenceRouter.ChatRequest expectChat(Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        while (true) {
            var remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) remaining = Duration.ofMillis(100);
            var msg = routerProbe.expectMessageClass(InferenceRouter.Command.class, remaining);
            if (msg instanceof InferenceRouter.ChatRequest chat) {
                if (VoicePassTestSupport.isVoicePass(chat)) {
                    VoicePassTestSupport.echoDraft(chat);
                    continue;
                }
                boolean noTools = chat.tools() == null || chat.tools().isEmpty();
                boolean simulation = noTools && chat.messages() != null && chat.messages().stream()
                    .anyMatch(m -> m.content() != null && (m.content().contains("ZONE STATE MAP")
                        || m.content().contains("simulating a companion")
                        || m.content().contains("scoring agent plan")));
                if (simulation) {
                    chat.replyTo().tell(new InferenceRouter.InferOk(chat.requestId(),
                        "{\"steps\":[],\"final_state\":\"ok\",\"confidence\":0.9,\"reasoning\":\"test\"}", 10, 20));
                    continue;
                }
                return chat;
            }
            if (msg instanceof InferenceRouter.ToolInferRequest tool && tool.systemPrompt() != null
                    && tool.systemPrompt().startsWith("Identify the language")) {
                tool.replyTo().tell(new InferenceRouter.InferOk(tool.requestId(), "en", 5, 1));
            }
        }
    }

    private static WorldEvent.Said playerSaid(String text) {
        return new WorldEvent.Said(ROOM_ID, Instant.now(), "player-alice", "Alice", text);
    }
}
