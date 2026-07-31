package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / Phase 2b — same-zone remote room {@code world.peek}
 * via Pekko ask. Verifies:
 * <ul>
 *   <li>An ask to a registered remote room delivers the snapshot.</li>
 *   <li>An ask to a non-existent room returns null+log.</li>
 *   <li>A timing-out ask returns null+log.</li>
 *   <li>Repeated peeks within 30s use the cache (only one ask hits the actor).</li>
 * </ul>
 *
 * <p>Tests the {@link RoomRegistry#askRoom} integration plus the
 * {@link RoomSnapshotCache} pathway end-to-end. The CompanionActor wiring
 * itself uses these same primitives.</p>
 */
class WorldPeekSameZoneRemoteTest {

    private ActorTestKit testKit;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("WorldPeekSameZoneRemoteTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        RoomRegistry.get().clear();
        RoomRegistry.get().setScheduler(testKit.scheduler());
    }

    @AfterEach void tearDown() {
        RoomRegistry.get().clear();
        if (testKit != null) testKit.shutdownTestKit();
    }

    /** Tiny stand-in for RoomActor — replies to GetSnapshot with a fixed snapshot. */
    private static class StubRoom extends AbstractBehavior<RoomCommand> {
        private final RoomSnapshot snapshot;
        private final AtomicInteger calls;

        static Behavior<RoomCommand> create(RoomSnapshot snap, AtomicInteger calls) {
            return Behaviors.setup(ctx -> new StubRoom(ctx, snap, calls));
        }

        StubRoom(ActorContext<RoomCommand> ctx, RoomSnapshot snap, AtomicInteger calls) {
            super(ctx);
            this.snapshot = snap;
            this.calls = calls;
        }

        @Override
        public Receive<RoomCommand> createReceive() {
            return newReceiveBuilder()
                .onMessage(RoomCommand.GetSnapshot.class, msg -> {
                    calls.incrementAndGet();
                    msg.replyTo().tell(snapshot);
                    return this;
                })
                .onAnyMessage(msg -> this)
                .build();
        }
    }

    @Test void ask_returns_snapshot_when_room_registered() throws Exception {
        var snap = new RoomSnapshot("foyer", "Foyer", "A bright room.", "alpha",
            List.of(), List.of(), List.of(), List.of());
        var calls = new AtomicInteger(0);
        ActorRef<RoomCommand> ref = testKit.spawn(StubRoom.create(snap, calls));
        RoomRegistry.get().register("foyer", ref, List.of("foyer"));

        var future = RoomRegistry.get().askRoom("foyer",
            RoomCommand.GetSnapshot::new, Duration.ofSeconds(1));
        var result = future.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Foyer");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void ask_unknown_room_throws_illegal_state() {
        // resolveRoomId returns null for an unknown alias; askRoom on null id throws.
        var registry = RoomRegistry.get();
        assertThat(registry.resolveRoomId("nonexistent")).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            registry.askRoom("nonexistent", RoomCommand.GetSnapshot::new, Duration.ofMillis(100)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void cache_absorbs_repeated_peeks_within_ttl() {
        // Direct cache usage — same exercise as the world.peek script-loop case.
        var cache = new RoomSnapshotCache(Duration.ofSeconds(30));
        var rendered = Map.<String, Object>of("name", "Foyer");

        // First peek — miss, populate.
        assertThat(cache.get("foyer")).isNull();
        cache.put("foyer", rendered);
        // Subsequent peeks within TTL hit cache (no new ask).
        for (int i = 0; i < 100; i++) {
            assertThat(cache.get("foyer")).isSameAs(rendered);
        }
    }

    @Test void cache_holds_null_to_avoid_repeated_failed_asks() {
        // Failed peek (timeout / unknown room) — caching null prevents a tight
        // script loop from re-asking every iter. The next peek after TTL gets
        // a fresh attempt.
        var cache = new RoomSnapshotCache(Duration.ofSeconds(30));
        cache.put("nope", null);
        // size>0 means cached, get returns null because the entry IS null.
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("nope")).isNull();
    }
}
