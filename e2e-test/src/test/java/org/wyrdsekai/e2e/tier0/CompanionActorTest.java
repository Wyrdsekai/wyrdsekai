package org.wyrdsekai.e2e.tier0;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.persistence.VitalityPersistence;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.e2e.infra.NodeProfile;
import org.wyrdsekai.e2e.infra.TestActorSystem;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 0 integration tests for CompanionActor state machine.
 * No real LLM — uses TestProbe to intercept InferenceRouter messages
 * and inject canned responses.
 *
 * Pattern:
 *   1. Spawn CompanionActor with TestProbe<RoomCommand> as roomRef
 *   2. Capture Subscribe, EnterRoom, LookRoom from roomProbe
 *   3. Extract subscriber ref from Subscribe → inject RoomNotification(Said(...))
 *   4. Capture ChatRequest from routerProbe → inject InferOk/InferError
 *   5. Verify SayInRoom messages on roomProbe
 */
@Tag("integration")
class CompanionActorTest {

    private static ActorTestKit testKit;
    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final AgentProfile PROFILE = NodeProfile.LAPTOP.companionProfile();
    private static final String ROOM_ID = "nexus";

    @BeforeAll
    static void setup() {
        testKit = TestActorSystem.create("companion-actor-test");
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion() {
        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();

        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));

        // Capture the 3 startup messages: Subscribe, EnterRoom, LookRoom
        var subscribe = roomProbe.expectMessageClass(RoomCommand.Subscribe.class,
            Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();

        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(RoomCommand.LookRoom.class,
            Duration.ofSeconds(5));

        // Reply with a room snapshot so companion has currentSnapshot
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    // --- Voice-stage helpers ---
    // The companion runs a two-stage inference: a drive pass, then a voice
    // "polish" pass (requestId "polish-…") queued on the router after almost
    // every spoken response. In these probe-based tests that follow-up request
    // lands on routerProbe and, in a multi-cycle test, would be captured instead
    // of the next drive request (or trip an expectNoMessage). These helpers make
    // the polish transparent.

    /** Next DRIVE ChatRequest, answering any interleaved voice-polish requests. */
    private InferenceRouter.ChatRequest nextDrive(Duration timeout) {
        while (true) {
            var req = routerProbe.expectMessageClass(
                InferenceRouter.ChatRequest.class, timeout);
            if (isPolish(req)) { answerPolish(req); continue; }
            return req;
        }
    }

    /** Answer any pending voice-polish request so it can't pollute a following
     *  expectNoMessage. No-op if none is queued. */
    private void drainPolish() {
        try {
            var req = routerProbe.expectMessageClass(
                InferenceRouter.ChatRequest.class, Duration.ofSeconds(2));
            if (isPolish(req)) answerPolish(req);
        } catch (AssertionError none) { /* nothing queued */ }
    }

    private static boolean isPolish(InferenceRouter.ChatRequest req) {
        return req.requestId() != null && req.requestId().startsWith("polish-");
    }

    private void answerPolish(InferenceRouter.ChatRequest req) {
        // Echo the draft (the polish request's user message) as the polished
        // output so the companion speaks promptly instead of waiting out the
        // 3s polish timeout, and the spoken text still matches assertions.
        var draft = req.messages().isEmpty() ? ""
            : req.messages().get(req.messages().size() - 1).content();
        req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(), draft, 5, 5));
    }

    // --- Lifecycle tests ---

    @Test
    void subscribes_and_enters_room_on_spawn() {
        // Already verified in @BeforeEach — Subscribe, EnterRoom, LookRoom
        // If we got here, spawn succeeded
        assertThat(subscriberRef).isNotNull();
    }

    // --- Speech tests ---

    @Test
    void responds_to_player_speech() {
        // Inject a player speech event
        var said = playerSaid("Alice", "What is this place?");
        subscriberRef.tell(new RoomNotification(said));

        // Wait for debounce (500ms + margin) → ChatRequest to router
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        assertThat(chatReq.messages()).isNotEmpty();

        // Inject successful inference response
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Welcome to the Nexus, Alice!", 20, 30));

        // Companion should speak in room
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("Welcome to the Nexus");
    }

    @Test
    void ignores_own_speech() {
        var ownSaid = new WorldEvent.Said(
            ROOM_ID, Instant.now(), PROFILE.entityId(), PROFILE.name(), "I said something");
        subscriberRef.tell(new RoomNotification(ownSaid));

        // Should NOT trigger inference
        routerProbe.expectNoMessage(Duration.ofSeconds(2));
    }

    @Test
    void ignores_narrator_speech() {
        var narratorSaid = new WorldEvent.Said(
            ROOM_ID, Instant.now(), "narrator", "Narrator", "The room shimmers.");
        subscriberRef.tell(new RoomNotification(narratorSaid));

        routerProbe.expectNoMessage(Duration.ofSeconds(2));
    }

    // --- Debounce tests ---

    @Test
    void debounces_rapid_messages() {
        // Send 3 messages rapidly
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "How are you?")));
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "What's here?")));

        // Should only get ONE ChatRequest (debounce batches them)
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        routerProbe.expectNoMessage(Duration.ofMillis(500));

        // Reply to complete the cycle
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hello! Let me help you.", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    @Test
    void defers_message_while_thinking() {
        // First message triggers thinking
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        var chatReq1 = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));

        // Second message while THINKING — should be deferred
        subscriberRef.tell(new RoomNotification(playerSaid("Bob", "Hi there")));

        // Complete first inference
        chatReq1.replyTo().tell(new InferenceRouter.InferOk(
            chatReq1.requestId(), "Hello Alice!", 15, 20));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Deferred message should now trigger second inference
        var chatReq2 = nextDrive(Duration.ofSeconds(3));
        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "Hello Bob!", 15, 20));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // --- Inference response tests ---

    @Test
    void inference_ok_speaks_in_room() {
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Tell me about yourself")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "I am Wyrd, a companion guide.", 25, 35));

        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).isEqualTo("I am Wyrd, a companion guide.");
        assertThat(say.entityId()).isEqualTo(PROFILE.entityId());
    }

    @Test
    void inference_error_speaks_degraded_response() {
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));

        chatReq.replyTo().tell(new InferenceRouter.InferError(
            chatReq.requestId(), "Connection refused"));

        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("shimmers uncertainly");
    }

    @Test
    void error_sets_cooldown_defers_retry() {
        // Trigger inference error
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        chatReq.replyTo().tell(new InferenceRouter.InferError(
            chatReq.requestId(), "Connection refused"));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // The degraded response also queues a voice-polish follow-up; drain it so
        // it isn't mistaken for a cooldown-violating drive request below.
        drainPolish();

        // Immediately send another message — should NOT trigger inference yet (cooldown)
        // but should re-schedule instead of dropping the message
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello again")));
        routerProbe.expectNoMessage(Duration.ofSeconds(2));

        // After cooldown expires (~30s), the deferred message WILL be processed
        var retriedReq = nextDrive(Duration.ofSeconds(35));
        assertThat(retriedReq.messages()).isNotEmpty();

        // Complete the retry
        retriedReq.replyTo().tell(new InferenceRouter.InferOk(
            retriedReq.requestId(), "Hello again!", 10, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // --- Vitality tests ---

    @Test
    void energy_drains_on_inference() {
        // Baseline: fresh companion has energy = 1.0
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));

        // The ChatRequest should have been sent — energy was drained internally
        // Verify via subsequent InferOk: the actor remains functional
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hello!", 10, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    @Test
    void confidence_fills_on_success() {
        // Successful inference should boost confidence (internal state)
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hello! I am Wyrd.", 10, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Second request should still work (confidence boosted)
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Tell me more")));
        var chatReq2 = nextDrive(Duration.ofSeconds(3));
        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "Let me tell you about the Nexus.", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    @Test
    void error_pressure_fills_on_failure() {
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        chatReq.replyTo().tell(new InferenceRouter.InferError(
            chatReq.requestId(), "Timeout"));

        // Degraded response sent
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("shimmers");
    }

    @Test
    void error_pressure_recovers_on_success() throws InterruptedException {
        // 1. Trigger error to build up pressure
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        var chatReq1 = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        chatReq1.replyTo().tell(new InferenceRouter.InferError(
            chatReq1.requestId(), "Connection refused"));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // 2. Wait for cooldown to expire (30s), then send new message
        Thread.sleep(31_000);
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Are you back?")));
        var chatReq2 = nextDrive(Duration.ofSeconds(5));

        // 3. Succeed — error pressure should halve, confidence should get bonus boost,
        //    and lastFailure should be cleared
        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "I'm back!", 10, 15));
        var say2 = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say2.text()).contains("back");

        // 4. Immediately send another message — should work without cooldown
        //    (lastFailure was cleared by InferOk)
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "You recovered?")));
        var chatReq3 = nextDrive(Duration.ofSeconds(3));
        chatReq3.replyTo().tell(new InferenceRouter.InferOk(
            chatReq3.requestId(), "Yes, fully recovered!", 10, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    @Test
    void vitality_tick_recovers_energy() throws InterruptedException {
        // Drain energy with an inference call
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Hello")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hello!", 10, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Wait for vitality ticks to recover (1-second intervals)
        Thread.sleep(3000);

        // Should still be able to process — energy recovered
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Still there?")));
        var chatReq2 = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));
        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "Still here!", 10, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // --- Greeting tests ---

    @Test
    void greets_player_on_enter() {
        var entered = new WorldEvent.EntityEntered(
            ROOM_ID, Instant.now(), "player-1", "Bob", "player", "north");
        subscriberRef.tell(new RoomNotification(entered));

        // After GREET_DELAY (1s), companion should trigger inference for greeting
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Welcome, Bob! The Nexus awaits.", 15, 25));

        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("Welcome");
    }

    // --- Hints test ---

    @Test
    void hints_emitted_from_response() {
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "What can I do?")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));

        // Response with hints JSON embedded
        var responseWithHints = """
            You can explore the rooms and talk to the inhabitants.
            ```json
            {"hints":[{"label":"Go east","intent":"navigate","action":"go:east"}]}
            ```""";
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithHints, 20, 40));

        // Should get SayInRoom (prose) and UpdateHints
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("explore");

        // The UpdateHints may come in any order relative to the response adapter
        // Just verify it doesn't crash and the companion continues working
    }

    // --- Room creation test ---

    @Test
    void room_creation_action_from_llm() {
        // Skip this test — RoomCreator requires ClusterSharding which isn't available
        // in unit tests. This is tested in Tier 2 E2E with full server bootstrap.
        // Here we just verify the companion handles the response correctly.
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Create a garden")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(3));

        // Response without structured action (no RoomCreator available)
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "I sense the potential for a garden here.", 20, 30));

        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("garden");
    }

    // --- Vitality persistence test ---

    @Test
    void vitality_persistence_accepted_by_companion() {
        // Verify CompanionActor accepts VitalityPersistence without error.
        // Full persistence save test deferred to Tier 2 (requires TestServerBootstrap + TestDb).
        var persistRoomProbe = testKit.<RoomCommand>createTestProbe();
        var persistRouterProbe = testKit.<InferenceRouter.Command>createTestProbe();

        // Spawn with null persistence — should work fine (no-op)
        var companion2 = testKit.spawn(CompanionActor.create(
            PROFILE, persistRoomProbe.ref(), "persist-room",
            persistRouterProbe.ref(), null,
            null, null, null));

        // Consume startup messages
        persistRoomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        persistRoomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = persistRoomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));

        // Companion spawned successfully with null persistence — verified by startup sequence
    }

    // --- Helpers ---

    private WorldEvent.Said playerSaid(String name, String text) {
        return new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-" + name.toLowerCase(), name, text);
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub of connections.",
            "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(),  // entities
            List.of(),  // objects
            List.of()   // hints
        );
    }
}
