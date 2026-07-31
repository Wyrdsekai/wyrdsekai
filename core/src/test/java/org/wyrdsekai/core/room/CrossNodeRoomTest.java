package org.wyrdsekai.core.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulated 2-node cross-node room test.
 * Node A (primary): runs a real RoomActor + RoomCommandDispatcher.
 * Node B (replica): runs a RoomProxy connected to Node A via in-process transport.
 *
 * No real NATS — the transport function directly calls RoomCommandDispatcher,
 * simulating what happens when NATS request/reply connects them.
 */
@Tag("integration")
class CrossNodeRoomTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
            }
            """).withFallback(EventSourcedBehaviorTestKit.config()));

    // Node A: primary room actor
    private static ActorRef<RoomCommand> primaryRoom;

    // Node B: proxy actor connected to primary via in-process transport
    private static ActorRef<RoomCommand> proxyRoom;

    @BeforeAll
    static void setUp() {
        Rooms.setScheduler(testKit.scheduler());

        // Create primary room (Node A)
        primaryRoom = testKit.spawn(RoomActor.create("cross-node-nexus"));

        // Initialize room on primary
        var initProbe = testKit.<RoomResponse>createTestProbe();
        primaryRoom.tell(new RoomCommand.CreateRoom(
            "The Nexus", "A shimmering hub connecting all paths.", "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(
                new RoomObject("crystal", "Nexus Crystal", "A pulsing crystal.", false),
                new RoomObject("scroll", "Welcome Scroll", "A rolled parchment.", true)
            ),
            initProbe.ref()));
        initProbe.receiveMessage();

        // Create proxy (Node B) — transport calls RoomCommandDispatcher directly
        proxyRoom = testKit.spawn(RoomProxy.create("cross-node-nexus",
            (roomId, commandJson) -> RoomCommandDispatcher.dispatch(primaryRoom, commandJson)));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void look_via_proxy_returns_same_room() {
        var probe = testKit.<RoomResponse>createTestProbe();
        proxyRoom.tell(new RoomCommand.LookRoom("player-b", "en", probe.ref()));

        var response = probe.receiveMessage();
        assertThat(response).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) response;
        assertThat(ok.snapshot().name()).isEqualTo("The Nexus");
        assertThat(ok.snapshot().description()).contains("shimmering hub");
        assertThat(ok.snapshot().exits()).hasSize(1);
        assertThat(ok.snapshot().objects()).hasSize(2);
    }

    @Test
    void enter_via_proxy_visible_on_primary() {
        // Player enters via proxy (Node B)
        var proxyProbe = testKit.<RoomResponse>createTestProbe();
        proxyRoom.tell(new RoomCommand.EnterRoom(
            "player-b", "Bob", "player", "south", proxyProbe.ref()));

        var enterResponse = proxyProbe.receiveMessage();
        assertThat(enterResponse).isInstanceOf(RoomResponse.Ok.class);
        var snapshot = ((RoomResponse.Ok) enterResponse).snapshot();
        assertThat(snapshot.entities()).anyMatch(e -> e.name().equals("Bob"));

        // Verify on primary (Node A) — look should show Bob
        var primaryProbe = testKit.<RoomResponse>createTestProbe();
        primaryRoom.tell(new RoomCommand.LookRoom("admin", "en", primaryProbe.ref()));

        var primaryResponse = primaryProbe.receiveMessage();
        var primarySnapshot = ((RoomResponse.Ok) primaryResponse).snapshot();
        assertThat(primarySnapshot.entities()).anyMatch(e -> e.name().equals("Bob"));
    }

    @Test
    void say_via_proxy_executes_on_primary() {
        // Ensure player is in room
        var enterProbe = testKit.<RoomResponse>createTestProbe();
        proxyRoom.tell(new RoomCommand.EnterRoom(
            "player-say", "Charlie", "player", "north", enterProbe.ref()));
        enterProbe.receiveMessage();

        // Say via proxy. SayInRoom replies Narrated (not Ok) so client
        // sessions don't redraw the room on top of the speech line.
        var sayProbe = testKit.<RoomResponse>createTestProbe();
        proxyRoom.tell(new RoomCommand.SayInRoom(
            "player-say", "Charlie", "Hello from Node B!", sayProbe.ref()));

        var response = sayProbe.receiveMessage();
        assertThat(response).isInstanceOf(RoomResponse.Narrated.class);
    }

    @Test
    void take_object_via_proxy() {
        // Enter
        var enterProbe = testKit.<RoomResponse>createTestProbe();
        proxyRoom.tell(new RoomCommand.EnterRoom(
            "player-take", "Diana", "player", "north", enterProbe.ref()));
        enterProbe.receiveMessage();

        // Take the scroll (takeable) via proxy
        var takeProbe = testKit.<RoomResponse>createTestProbe();
        proxyRoom.tell(new RoomCommand.TakeObject(
            "player-take", "Welcome Scroll", takeProbe.ref()));

        var response = takeProbe.receiveMessage();
        // Should get ObjectTakenOk (scroll is takeable)
        assertThat(response).isInstanceOfAny(
            RoomResponse.ObjectTakenOk.class, RoomResponse.Ok.class);
    }

    @Test
    void proxy_subscriber_receives_primary_events() {
        // Subscribe on proxy (Node B)
        var eventProbe = testKit.<RoomNotification>createTestProbe();
        proxyRoom.tell(new RoomCommand.Subscribe(eventProbe.ref()));

        // Simulate event delivery (as if NATS broadcast it from primary)
        var event = new WorldEvent.Said("cross-node-nexus",
            Instant.now(), "companion-wyrd", "Wyrd",
            "Welcome to the Nexus, traveler.");
        proxyRoom.tell(new RoomCommand.BroadcastRemoteEvent(event));

        var notification = eventProbe.receiveMessage();
        assertThat(notification.event()).isInstanceOf(WorldEvent.Said.class);
        var said = (WorldEvent.Said) notification.event();
        assertThat(said.text()).isEqualTo("Welcome to the Nexus, traveler.");
        assertThat(said.entityName()).isEqualTo("Wyrd");
    }

    @Test
    void proxy_caches_snapshot_after_first_look() {
        // Create a fresh proxy with a counting transport
        var callCount = new AtomicInteger(0);
        var freshProxy = testKit.spawn(RoomProxy.create("cross-node-nexus",
            (roomId, commandJson) -> {
                callCount.incrementAndGet();
                return RoomCommandDispatcher.dispatch(primaryRoom, commandJson);
            }));

        var probe = testKit.<RoomResponse>createTestProbe();

        // First look — transport called
        freshProxy.tell(new RoomCommand.LookRoom("cache-test", "en", probe.ref()));
        probe.receiveMessage();
        assertThat(callCount.get()).isEqualTo(1);

        // Second look — served from cache
        freshProxy.tell(new RoomCommand.LookRoom("cache-test", "en", probe.ref()));
        var cached = probe.receiveMessage();
        assertThat(cached).isInstanceOf(RoomResponse.Ok.class);
        assertThat(callCount.get()).isEqualTo(1); // transport NOT called again
    }

    @Test
    void leave_via_proxy() {
        // Enter then leave via proxy
        var enterProbe = testKit.<RoomResponse>createTestProbe();
        proxyRoom.tell(new RoomCommand.EnterRoom(
            "player-leave", "Eve", "player", "west", enterProbe.ref()));
        enterProbe.receiveMessage();

        var leaveProbe = testKit.<RoomResponse>createTestProbe();
        proxyRoom.tell(new RoomCommand.LeaveRoom(
            "player-leave", "Eve", "east", leaveProbe.ref()));

        var response = leaveProbe.receiveMessage();
        assertThat(response).isInstanceOf(RoomResponse.Ok.class);

        // Verify Eve is gone on primary
        var lookProbe = testKit.<RoomResponse>createTestProbe();
        primaryRoom.tell(new RoomCommand.LookRoom("admin", "en", lookProbe.ref()));
        var snapshot = ((RoomResponse.Ok) lookProbe.receiveMessage()).snapshot();
        assertThat(snapshot.entities()).noneMatch(e -> e.name().equals("Eve"));
    }
}
