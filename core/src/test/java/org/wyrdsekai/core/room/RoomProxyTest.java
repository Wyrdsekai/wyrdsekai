package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for RoomProxy — verifies command forwarding, caching,
 * subscription management, and error handling with a mock transport.
 */
class RoomProxyTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.provider = "local"
            """));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private static RoomSnapshot makeSnapshot(String roomId, String name) {
        return new RoomSnapshot(roomId, name, "A room.", "test",
            List.of(), List.of(), List.of(), List.of());
    }

    private static String okResponseJson(String roomId, String name) {
        return """
            {"type":"ok","snapshot":{"roomId":"%s","name":"%s","description":"A room.","zone":"test","exits":[],"entities":[],"objects":[],"hints":[]}}
            """.formatted(roomId, name).trim();
    }

    @Test
    void forward_look_to_transport() {
        var responseJson = okResponseJson("nexus", "The Nexus");
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> CompletableFuture.completedFuture(responseJson)));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.LookRoom("player-1", "en", probe.ref()));

        var response = probe.receiveMessage();
        assertThat(response).isInstanceOf(RoomResponse.Ok.class);
        var ok = (RoomResponse.Ok) response;
        assertThat(ok.snapshot().name()).isEqualTo("The Nexus");
    }

    @Test
    void cached_snapshot_served_for_look() {
        var callCount = new AtomicInteger(0);
        var responseJson = okResponseJson("nexus", "The Nexus");
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                callCount.incrementAndGet();
                return CompletableFuture.completedFuture(responseJson);
            }));

        var probe = testKit.<RoomResponse>createTestProbe();

        // First look — goes to transport
        proxy.tell(new RoomCommand.LookRoom("player-1", "en", probe.ref()));
        probe.receiveMessage();
        assertThat(callCount.get()).isEqualTo(1);

        // Second look — should be served from cache (transport not called)
        proxy.tell(new RoomCommand.LookRoom("player-1", "en", probe.ref()));
        var cached = probe.receiveMessage();
        assertThat(cached).isInstanceOf(RoomResponse.Ok.class);
        assertThat(callCount.get()).isEqualTo(1); // still 1
    }

    @Test
    void forward_enter_room() {
        var responseJson = okResponseJson("nexus", "The Nexus");
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                assertThat(cmdJson).contains("\"type\":\"enter\"");
                assertThat(cmdJson).contains("Alice");
                return CompletableFuture.completedFuture(responseJson);
            }));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.EnterRoom("player-1", "Alice", "player", "north", probe.ref()));

        var response = probe.receiveMessage();
        assertThat(response).isInstanceOf(RoomResponse.Ok.class);
    }

    @Test
    void forward_say_in_room() {
        // Remote RoomActor returns Narrated for SayInRoom (no snapshot redraw
        // on the speech line). Mock the transport to return that shape too.
        var responseJson = "{\"type\":\"narrated\"}";
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                assertThat(cmdJson).contains("\"type\":\"say\"");
                assertThat(cmdJson).contains("Hello world");
                return CompletableFuture.completedFuture(responseJson);
            }));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.SayInRoom("player-1", "Alice", "Hello world", probe.ref()));

        assertThat(probe.receiveMessage()).isInstanceOf(RoomResponse.Narrated.class);
    }

    @Test
    void transport_error_returns_rejected() {
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> CompletableFuture.failedFuture(
                new RuntimeException("Connection lost"))));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.LookRoom("player-1", "en", probe.ref()));

        var response = probe.receiveMessage();
        assertThat(response).isInstanceOf(RoomResponse.Rejected.class);
        var rejected = (RoomResponse.Rejected) response;
        assertThat(rejected.code()).isEqualTo("unavailable");
    }

    @Test
    void subscribe_and_receive_remote_events() {
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> CompletableFuture.completedFuture(
                okResponseJson("nexus", "The Nexus"))));

        var eventProbe = testKit.<RoomNotification>createTestProbe();
        proxy.tell(new RoomCommand.Subscribe(eventProbe.ref()));

        // Simulate remote event delivery
        var event = new WorldEvent.Said("nexus", Instant.now(), "companion-wyrd", "Wyrd", "Hello traveler");
        proxy.tell(new RoomCommand.BroadcastRemoteEvent(event));

        var notification = eventProbe.receiveMessage();
        assertThat(notification.event()).isInstanceOf(WorldEvent.Said.class);
        assertThat(((WorldEvent.Said) notification.event()).text()).isEqualTo("Hello traveler");
    }

    @Test
    void unsubscribe_stops_events() {
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> CompletableFuture.completedFuture(
                okResponseJson("nexus", "The Nexus"))));

        var eventProbe = testKit.<RoomNotification>createTestProbe();
        proxy.tell(new RoomCommand.Subscribe(eventProbe.ref()));
        proxy.tell(new RoomCommand.Unsubscribe(eventProbe.ref()));

        var event = new WorldEvent.Said("nexus", Instant.now(), "companion-wyrd", "Wyrd", "Hello");
        proxy.tell(new RoomCommand.BroadcastRemoteEvent(event));

        eventProbe.expectNoMessage(Duration.ofMillis(200));
    }

    @Test
    void forward_take_object() {
        var response = """
            {"type":"object_taken_ok","snapshot":{"roomId":"nexus","name":"The Nexus","description":"A room.","zone":"test","exits":[],"entities":[],"objects":[],"hints":[]},"takenObject":{"id":"sword","name":"Iron Sword","description":"Sharp.","takeable":true,"visible":true,"cloneable":true}}
            """.trim();
        var proxy = testKit.spawn(RoomProxy.create("nexus",
            (roomId, cmdJson) -> {
                assertThat(cmdJson).contains("\"type\":\"take\"");
                return CompletableFuture.completedFuture(response);
            }));

        var probe = testKit.<RoomResponse>createTestProbe();
        proxy.tell(new RoomCommand.TakeObject("player-1", "sword", probe.ref()));

        var result = probe.receiveMessage();
        assertThat(result).isInstanceOf(RoomResponse.ObjectTakenOk.class);
        var taken = (RoomResponse.ObjectTakenOk) result;
        assertThat(taken.takenObject().name()).isEqualTo("Iron Sword");
    }
}
