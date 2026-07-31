package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for step 1 — the {@code companionMode}
 * field, bondholder identification, and presence-mode transitions driven by
 * conversation activity. Behavior wires (follow, narration) come in step 2+.
 */
@Tag("integration")
class CompanionPresenceModeTest {

    private static ActorTestKit testKit;
    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "agent-wyrd", "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        // Crunch presence timings BEFORE first reference to CompanionActor —
        // the durations are static-final and resolve at class load.
        System.setProperty("WYRDSEKAI_PRESENCE_SILENCE_SEC", "1");
        System.setProperty("WYRDSEKAI_PRESENCE_GRACE_SEC", "1");
        System.setProperty("WYRDSEKAI_PRESENCE_CHECK_SEC", "1");

        AgentEventStream.init();
        EntityRegistry.init();

        testKit = ActorTestKit.create("companion-presence-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) testKit.shutdownTestKit();
        System.clearProperty("WYRDSEKAI_PRESENCE_SILENCE_SEC");
        System.clearProperty("WYRDSEKAI_PRESENCE_GRACE_SEC");
        System.clearProperty("WYRDSEKAI_PRESENCE_CHECK_SEC");
    }

    @BeforeEach
    void spawnCompanion() {
        // Re-init so previous test's bondholder/room registrations don't leak.
        EntityRegistry.init();

        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();

        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));

        var sub = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = sub.subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    @Test
    void initial_mode_is_present_with_user_no_bondholder() {
        var state = queryState();
        assertThat(state.companionMode())
            .as("default mode is PRESENT_WITH_USER")
            .isEqualTo(CompanionActor.CompanionMode.PRESENT_WITH_USER);
        assertThat(state.primaryBondholderDid())
            .as("no active bonds yet → no bondholder DID")
            .isNull();
    }

    @Test
    void bond_is_tracked_when_player_speaks() {
        // A player tell creates an acquaintance bond; primaryBondholderDid
        // should resolve to the player's DID afterward.
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "hello")));

        // Drain the inference round-trip the speech triggers.
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hello, Alice.", 5, 5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(5),
            () -> queryState().primaryBondholderDid() != null);

        assertThat(queryState().primaryBondholderDid())
            .as("the speaker's entity id becomes the bondholder")
            .isEqualTo("player-alice");
    }

    @Test
    void mode_flips_to_on_own_time_after_silence_then_back_on_activity() {
        // Establish bond.
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "hi")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hi.", 5, 5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(5),
            () -> queryState().primaryBondholderDid() != null);

        // Silence threshold is 1s + 1s grace + 1s check cadence; allow up to 8s.
        waitUntil(Duration.ofSeconds(8),
            () -> queryState().companionMode() == CompanionActor.CompanionMode.ON_OWN_TIME);

        // Bondholder speaks again — should flip back immediately on next event.
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "still here?")));
        var chatReq2 = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "Yes.", 3, 3));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(3),
            () -> queryState().companionMode() == CompanionActor.CompanionMode.PRESENT_WITH_USER);
    }

    @Test
    void bondholder_transit_triggers_follow() {
        // Establish bond and register both rooms in RoomRegistry so the follow
        // move can resolve the target ref.
        var gardenProbe = testKit.<RoomCommand>createTestProbe();
        RoomRegistry.get().register(ROOM_ID, roomProbe.ref());
        RoomRegistry.get().register("garden", gardenProbe.ref());

        EntityRegistry.get().enter("player-alice", "Alice", "player", ROOM_ID);

        // Bond formation via player speech.
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "hi")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hi.", 5, 5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(5),
            () -> queryState().primaryBondholderDid() != null);

        // Alice walks east — EntityRegistry sees the move, then EntityLeft fires
        // on the companion's room subscription.
        EntityRegistry.get().moved("player-alice", "garden");
        var aliceLeft = new WorldEvent.EntityLeft(
            ROOM_ID, Instant.now(), "player-alice", "Alice", "east");
        subscriberRef.tell(new RoomNotification(aliceLeft));

        // Companion should emote "follows Alice out", then Unsubscribe + LeaveRoom
        // on the source, then EnterRoom + Subscribe on the destination.
        var followEmote = roomProbe.expectMessageClass(
            RoomCommand.EmoteInRoom.class, Duration.ofSeconds(5));
        assertThat(followEmote.text()).contains("follows Alice");

        roomProbe.expectMessageClass(RoomCommand.Unsubscribe.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.LeaveRoom.class, Duration.ofSeconds(5));

        gardenProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        gardenProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        gardenProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        // W2 rooms-alive: arrival also queries the destination room's
        // script-declared agent tools (getToolDefinitions).
        gardenProbe.expectMessageClass(RoomCommand.GetToolDefinitions.class, Duration.ofSeconds(5));

        // And one final EmoteInRoom in the destination — "*follows Alice in*".
        var arrivalEmote = gardenProbe.expectMessageClass(
            RoomCommand.EmoteInRoom.class, Duration.ofSeconds(5));
        assertThat(arrivalEmote.text()).contains("follows Alice");

        EntityRegistry.get().remove("player-alice");
        RoomRegistry.get().remove(ROOM_ID);
        RoomRegistry.get().remove("garden");
    }

    @Test
    void cross_zone_traveling_without_invite_narrates_stays_behind() {
        EntityRegistry.get().enter("player-alice", "Alice", "player", ROOM_ID);
        // Establish bond.
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "hi")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hi.", 5, 5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(5),
            () -> queryState().primaryBondholderDid() != null);

        // Mark bondholder as traveling cross-zone.
        EntityRegistry.get().setTraveling("player-alice", "beta");
        // Trigger the follow path via EntityLeft on companion's room.
        var aliceLeft = new WorldEvent.EntityLeft(
            ROOM_ID, Instant.now(), "player-alice", "Alice", "portal");
        subscriberRef.tell(new RoomNotification(aliceLeft));

        // Companion should emote "watches Alice step through the portal toward beta"
        var emote = roomProbe.expectMessageClass(
            RoomCommand.EmoteInRoom.class, Duration.ofSeconds(5));
        assertThat(emote.text())
            .contains("watches")
            .contains("portal")
            .contains("beta");

        EntityRegistry.get().setReturned("player-alice");
        EntityRegistry.get().remove("player-alice");
    }

    @Test
    void cross_zone_invite_narrates_follow_through_portal() {
        EntityRegistry.get().enter("player-alice", "Alice", "player", ROOM_ID);
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "hi")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hi.", 5, 5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(5),
            () -> queryState().primaryBondholderDid() != null);

        // Bondholder issues the invite, then crosses.
        companion.tell(new CompanionActor.CrossZoneInvite("player-alice"));
        EntityRegistry.get().setTraveling("player-alice", "beta");
        subscriberRef.tell(new RoomNotification(new WorldEvent.EntityLeft(
            ROOM_ID, Instant.now(), "player-alice", "Alice", "portal")));

        var emote = roomProbe.expectMessageClass(
            RoomCommand.EmoteInRoom.class, Duration.ofSeconds(5));
        assertThat(emote.text())
            .as("invite triggers follow narration not stay-behind")
            .contains("follows")
            .contains("portal");

        EntityRegistry.get().setReturned("player-alice");
        EntityRegistry.get().remove("player-alice");
    }

    @Test
    void player_returned_event_flips_mode_back_to_present() {
        // Establish bond first.
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "hi")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hi.", 5, 5));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        waitUntil(Duration.ofSeconds(8),
            () -> queryState().companionMode() == CompanionActor.CompanionMode.ON_OWN_TIME);

        // PlayerReturned is the login signal — should re-tether immediately.
        companion.tell(new CompanionActor.PlayerReturned("player-alice", "Alice"));

        waitUntil(Duration.ofSeconds(3),
            () -> queryState().companionMode() == CompanionActor.CompanionMode.PRESENT_WITH_USER);
    }

    private static void waitUntil(Duration timeout, BooleanSupplier cond) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(100); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    // --- helpers ---

    private CompanionActor.TestStateResponse queryState() {
        var probe = testKit.<CompanionActor.TestStateResponse>createTestProbe();
        companion.tell(new CompanionActor.QueryTestState(probe.ref()));
        return probe.expectMessageClass(
            CompanionActor.TestStateResponse.class, Duration.ofSeconds(3));
    }

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
