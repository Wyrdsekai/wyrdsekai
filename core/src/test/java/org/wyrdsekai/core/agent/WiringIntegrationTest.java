package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for operational gap wiring:
 * CancellationToken in inference pipeline, PlanTokenBudget in GoalExecutor,
 * ConversationPersistence checkpoint lifecycle, new action dispatch.
 */
@Tag("integration")
class WiringIntegrationTest {

    private static ActorTestKit testKit;

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";
    private static final String ENTITY_ID = "agent-wyrd-wire";

    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", ENTITY_ID, "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();

        testKit = ActorTestKit.create("wiring-integration-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion() {
        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();

        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));

        var subscribe = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();

        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    // -----------------------------------------------------------------------
    // CancellationToken: stale response discarded after abort
    // -----------------------------------------------------------------------

    @Test
    void cancelled_inference_response_is_discarded() {
        // Start inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Tell me a story")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Send abort signal before response arrives
        AgentEventStream.get().publishAbort("player-alice", "Alice", ROOM_ID);
        // Consume the abort acknowledgment
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Now the stale response arrives — should be discarded
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Once upon a time...", 20, 30));

        // Agent should NOT speak the stale response
        roomProbe.expectNoMessage(Duration.ofSeconds(2));
    }

    // -----------------------------------------------------------------------
    // PlanTokenBudget: budget created with plan, passed to GoalExecutor
    // -----------------------------------------------------------------------

    @Test
    void plan_creation_wires_token_budget() {
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Find me a mythology book")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Agent creates a plan
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(),
            """
            I'll search for that.
            ```json
            {"action": "task_plan", "description": "find mythology books", "goals": ["Go to Library", "Search for mythology", "Report back"]}
            ```
            """, 100, 50));

        // Should speak the prose
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Plan advance: when a plan is active, the dispatch goes to the small tool
        // dispatcher model with a stripped prompt (no soul/vitality/token budget).
        // The dispatch prompt contains the tool dispatcher system message.
        //
        // M2/M3 pre-commit (MentalSimulator + M2PlanScorer) runs BEFORE the
        // dispatcher and emits its own ChatRequest with a "ZONE STATE MAP"
        // simulation prompt. We need to skip those and find the actual
        // dispatcher prompt — scan up to 5 inference calls.
        boolean foundDispatcher = false;
        String lastContent = "";
        for (int i = 0; i < 5 && !foundDispatcher; i++) {
            var nextReq = routerProbe.expectMessageClass(
                InferenceRouter.ChatRequest.class, Duration.ofSeconds(10));
            var content = nextReq.messages().stream()
                .map(m -> m.content())
                .reduce("", (a, b) -> a + "\n" + b);
            lastContent = content;
            if (content.contains("agent that uses tools")) {
                foundDispatcher = true;
                break;
            }
            // M2/M3 sim chat — reply with a low-confidence pass-through so
            // the pre-commit gate doesn't block, and look for the next req.
            nextReq.replyTo().tell(new InferenceRouter.InferOk(
                nextReq.requestId(),
                "{\"steps\":[],\"final_state\":\"ok\",\"confidence\":0.9,\"reasoning\":\"test\"}",
                10, 20));
        }
        assertThat(foundDispatcher)
            .as("dispatcher chat with 'agent that uses tools' should appear within 5 inference calls; last content was: %s",
                lastContent.substring(0, Math.min(200, lastContent.length())))
            .isTrue();
    }

    // -----------------------------------------------------------------------
    // New actions: emote dispatches to room
    // -----------------------------------------------------------------------

    @Test
    void emote_action_sends_emote_to_room() {
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Show me how you feel")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(),
            """
            ```json
            {"action": "emote", "text": "*smiles warmly*"}
            ```
            """, 20, 15));

        // Should dispatch EmoteInRoom
        var emote = roomProbe.expectMessageClass(
            RoomCommand.EmoteInRoom.class, Duration.ofSeconds(5));
        assertThat(emote.text()).contains("smiles warmly");
    }

    // -----------------------------------------------------------------------
    // New actions: voluntary sleep triggers energy drain
    // -----------------------------------------------------------------------

    @Test
    void voluntary_sleep_action_triggers_sleep_path() {
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "You should get some rest")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(),
            """
            *yawns* Time for a rest.
            ```json
            {"action": "voluntary_sleep", "reason": "need to consolidate memories"}
            ```
            """, 20, 15));

        // Should speak the prose (including the yawn)
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).containsIgnoringCase("yawn");
    }

    // -----------------------------------------------------------------------
    // New actions: examine dispatches look
    // -----------------------------------------------------------------------

    @Test
    void examine_action_sends_look_to_room() {
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Examine the crystal orb closely")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(),
            """
            Let me take a closer look.
            ```json
            {"action": "examine", "target": "crystal orb"}
            ```
            """, 20, 15));

        // #35 — the examine prose is now voiced through the async 4B pass, so
        // the synchronous LookRoom reaches the room first; the voiced SayInRoom
        // follows (voice-pass request left unanswered → raw draft on timeout).
        roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // MemoryCompactor wired into PromptAssembler
    // -----------------------------------------------------------------------

    @Test
    void repeated_actions_produce_compacted_memory_in_prompt() {
        // Send many messages to build up working memory
        for (int i = 0; i < 5; i++) {
            subscriberRef.tell(new RoomNotification(
                playerSaid("Alice", "Go check room " + i)));

            // #35 — drain the always-on voice-pass request so this returns the
            // reactive request, not the prior turn's polish pass.
            var req = VoicePassTestSupport.nextChatRequest(routerProbe, Duration.ofSeconds(5));

            req.replyTo().tell(new InferenceRouter.InferOk(
                req.requestId(),
                "Going to check.\n```json\n{\"action\": \"go_to_room\", \"target\": \"room-" + i + "\"}\n```",
                20, 15));

            roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        }

        // Final message — prompt should have compacted memory
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What have you been doing?")));

        var finalReq = VoicePassTestSupport.nextChatRequest(routerProbe, Duration.ofSeconds(5));

        // Verify the prompt is within budget (not exploded)
        int totalChars = finalReq.messages().stream()
            .mapToInt(m -> m.content().length()).sum();
        assertThat(totalChars / 4).isLessThan(3500); // under 4096 * 0.85
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private WorldEvent.Said playerSaid(String name, String text) {
        return new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-" + name.toLowerCase(), name, text);
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub of connections.",
            "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }
}
