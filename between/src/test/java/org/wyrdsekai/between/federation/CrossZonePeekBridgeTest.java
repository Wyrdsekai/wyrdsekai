package org.wyrdsekai.between.federation;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Definitive re-audit fix (#33-4): {@link org.wyrdsekai.core.agent.CrossZonePeekService}
 * had a test-only {@code setCaller} and no responder, so cross-zone
 * {@code world.peek} never resolved in production. {@link CrossZonePeekBridge}
 * closes both sides. This test wires two bridges (source zone "alpha" running
 * the caller, target zone "beta" running the responder) over a shared in-memory
 * pub/sub bus that stands in for the relay, and asserts a real request/reply
 * round-trip returns the rendered §8 snapshot.
 */
class CrossZonePeekBridgeTest {

    private ActorTestKit testKit;
    private CrossZonePeekBridge alpha;   // caller
    private CrossZonePeekBridge beta;    // responder

    // Shared in-memory relay: subject → handlers.
    private final Map<String, List<Consumer<byte[]>>> bus = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create("CrossZonePeekBridgeTest");
        RoomRegistry.get().clear();
        RoomRegistry.get().setScheduler(testKit.system().scheduler());

        BiConsumer<String, byte[]> publish = (subject, data) -> {
            var hs = bus.get(subject);
            if (hs != null) for (var h : hs) h.accept(data);
        };
        BiConsumer<String, Consumer<byte[]>> subscribe = (subject, h) ->
            bus.computeIfAbsent(subject, k -> new CopyOnWriteArrayList<>()).add(h);

        alpha = new CrossZonePeekBridge("alpha", publish, subscribe);
        beta = new CrossZonePeekBridge("beta", publish, subscribe);
        alpha.startCaller();
        beta.startResponder();
    }

    @AfterEach
    void tearDown() {
        alpha.close();
        beta.close();
        RoomRegistry.get().clear();
        testKit.shutdownTestKit();
    }

    /** Stand-in RoomActor that answers GetSnapshot with a fixed snapshot. */
    private ActorRef<RoomCommand> snapshotRoom(RoomSnapshot snap) {
        return testKit.spawn(Behaviors.receive(RoomCommand.class)
            .onMessage(RoomCommand.GetSnapshot.class, msg -> {
                msg.replyTo().tell(snap);
                return Behaviors.same();
            })
            .build());
    }

    @Test
    void cross_zone_peek_returns_rendered_snapshot() throws Exception {
        var snap = new RoomSnapshot("beta-parlor", "Parlor", "A cozy room", "beta",
            List.of(), List.of(new Entity("did:wyrd:wisp", "Wisp", "agent", "")),
            List.of(), List.of());
        var room = snapshotRoom(snap);
        // Register on beta's side (single shared RoomRegistry in-process).
        RoomRegistry.get().register("beta-parlor", room, List.of("parlor"));

        var future = alpha.peek("beta", "alpha", "parlor");
        var result = future.get(2, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("Parlor");
        assertThat(result.get("description")).isEqualTo("A cozy room");
        @SuppressWarnings("unchecked")
        var entities = (List<Map<String, Object>>) result.get("entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).get("alias")).isEqualTo("Wisp");
    }

    @Test
    void unknown_room_replies_null() throws Exception {
        var future = alpha.peek("beta", "alpha", "no-such-room");
        var result = future.get(2, TimeUnit.SECONDS);
        assertThat(result).isNull();
    }
}
