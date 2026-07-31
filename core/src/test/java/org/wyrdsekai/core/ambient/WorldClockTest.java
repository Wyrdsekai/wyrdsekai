package org.wyrdsekai.core.ambient;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.wyrdsekai.common.embodiment.AmbientPhase;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.room.RoomActor;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.Rooms;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link WorldClock} — driving phase transitions and
 * verifying AmbientChanged broadcasts to registered rooms.
 */
@Tag("integration")
class WorldClockTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setupKit() {
        var config = ConfigFactory.parseString("""
            pekko.actor.provider = local
            pekko.persistence.journal.plugin = "pekko.persistence.journal.inmem"
            pekko.persistence.snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
            pekko.persistence.snapshot-store.local.dir = "target/test-snapshots-world-clock"
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
            }
            """);
        testKit = ActorTestKit.create(config);
        RoomRegistry.get().setScheduler(testKit.scheduler());
        Rooms.setScheduler(testKit.scheduler());
    }

    @AfterAll
    static void tearDownKit() {
        testKit.shutdownTestKit();
    }

    /**
     * Per-test bag of spawned actors so we can stop them in {@link #postClear}.
     * Without this, a prior test's WorldClock keeps ticking against the global
     * {@link RoomRegistry} and broadcasts AmbientChanged events to rooms the
     * new test just registered — corrupting the assertion.
     */
    private final List<ActorRef<?>> spawnedActors =
        new ArrayList<>();

    @BeforeEach
    void clearState() {
        RoomRegistry.get().clear();
        WorldClock.clearAllForTests();
        spawnedActors.clear();
    }

    @AfterEach
    void postClear() {
        // Stop every actor spawned by this test BEFORE clearing the registry —
        // otherwise the actor's timer keeps firing into the next test.
        for (var ref : spawnedActors) {
            try {
                testKit.stop(ref);
            } catch (Exception ignored) {
                // Best-effort cleanup; some tests might already have stopped the actor.
            }
        }
        spawnedActors.clear();
        RoomRegistry.get().clear();
        WorldClock.clearAllForTests();
    }

    /** Spawn an actor and record it for {@link #postClear}. */
    private <T> ActorRef<T> spawnAndTrack(
            Behavior<T> behavior) {
        var ref = testKit.spawn(behavior);
        spawnedActors.add(ref);
        return ref;
    }

    @Test
    void initialPhaseIsRecordedInStaticCache() {
        var clock = AtomicReference.create(
            Instant.parse("2026-05-24T13:00:00Z"));
        spawnAndTrack(WorldClock.create("test-zone",
            Duration.ofHours(24), Duration.ofMinutes(1),
            clock::get, ZoneId.of("UTC")));
        // The initial phase is computed synchronously in the constructor,
        // so the static cache should be populated immediately.
        // (One short sleep to let the spawn settle — Pekko spawn is eager
        //  but we'd race the static publish without it.)
        await(() -> WorldClock.currentPhase("test-zone") != null, 2_000);
        assertThat(WorldClock.currentPhase("test-zone")).isEqualTo(AmbientPhase.MIDDAY);
    }

    @Test
    @Timeout(15)
    void tickRecomputesPhaseAndBroadcasts() {
        // Spawn one room actor and register it so the clock can broadcast.
        var roomId = "library-test-1";
        var roomRef = spawnRoom(roomId);

        // Subscribe to room notifications so we can watch for AmbientChanged.
        var notifProbe = testKit.<RoomNotification>createTestProbe();
        roomRef.tell(new RoomCommand.Subscribe(notifProbe.getRef()));
        // Ensure CreateRoom completes (otherwise BroadcastRemoteEvent won't reach subscribers).
        var resp = testKit.<RoomResponse>createTestProbe();
        roomRef.tell(new RoomCommand.CreateRoom("Library", "Books.", "alpha", List.of(),
            List.of(), List.of(), resp.getRef()));
        resp.expectMessageClass(RoomResponse.Ok.class, Duration.ofSeconds(5));

        var clock = AtomicReference.create(
            Instant.parse("2026-05-24T13:00:00Z"));  // MIDDAY at start
        var clockActor = spawnAndTrack(WorldClock.create("alpha",
            Duration.ofHours(24), Duration.ofMillis(200),
            clock::get, ZoneId.of("UTC")));

        // Wait for the actor's constructor to publish the initial phase BEFORE
        // we mutate the supplier — otherwise we race with the actor thread and
        // {@code epochOrigin = clock.get()} could already read the NIGHT-time.
        await(() -> WorldClock.currentPhase("alpha") == AmbientPhase.MIDDAY, 3_000);
        assertThat(WorldClock.currentPhase("alpha")).isEqualTo(AmbientPhase.MIDDAY);

        // Advance the clock to NIGHT (23:00 UTC) and force a tick.
        clock.set(Instant.parse("2026-05-24T23:00:00Z"));
        clockActor.tell(new WorldClock.Tick());

        var ev = fishForAmbient(notifProbe, Duration.ofSeconds(5));
        assertThat(ev).isNotNull();
        assertThat(ev.roomId()).isEqualTo(roomId);
        assertThat(ev.current()).isEqualTo(AmbientPhase.NIGHT.key());
        assertThat(ev.descriptor()).isNotBlank();
        assertThat(WorldClock.currentPhase("alpha")).isEqualTo(AmbientPhase.NIGHT);
    }

    @Test
    @Timeout(15)
    void overridePhaseAlsoBroadcasts() {
        var roomId = "atrium-test-2";
        var roomRef = spawnRoom(roomId);
        var notifProbe = testKit.<RoomNotification>createTestProbe();
        roomRef.tell(new RoomCommand.Subscribe(notifProbe.getRef()));
        var resp = testKit.<RoomResponse>createTestProbe();
        roomRef.tell(new RoomCommand.CreateRoom("Atrium", "Open.", "alpha", List.of(),
            List.of(), List.of(), resp.getRef()));
        resp.expectMessageClass(RoomResponse.Ok.class, Duration.ofSeconds(5));

        var fixed = Instant.parse("2026-05-24T13:00:00Z");
        var clockActor = spawnAndTrack(WorldClock.create("alpha",
            Duration.ofHours(24), Duration.ofSeconds(5),
            () -> fixed, ZoneId.of("UTC")));

        clockActor.tell(new WorldClock.OverridePhase(AmbientPhase.DUSK));

        var ev = fishForAmbient(notifProbe, Duration.ofSeconds(5));
        assertThat(ev).isNotNull();
        assertThat(ev.current()).isEqualTo(AmbientPhase.DUSK.key());
        assertThat(WorldClock.currentPhase("alpha")).isEqualTo(AmbientPhase.DUSK);
    }

    @Test
    @Timeout(10)
    void noBroadcastWhenPhaseDoesNotChange() {
        var roomId = "vault-test-3";
        var roomRef = spawnRoom(roomId);
        var notifProbe = testKit.<RoomNotification>createTestProbe();
        roomRef.tell(new RoomCommand.Subscribe(notifProbe.getRef()));
        var resp = testKit.<RoomResponse>createTestProbe();
        roomRef.tell(new RoomCommand.CreateRoom("Vault", "Deep.", "alpha", List.of(),
            List.of(), List.of(), resp.getRef()));
        resp.expectMessageClass(RoomResponse.Ok.class, Duration.ofSeconds(5));

        var fixed = Instant.parse("2026-05-24T13:00:00Z");  // MIDDAY
        var clockActor = spawnAndTrack(WorldClock.create("alpha",
            Duration.ofHours(24), Duration.ofSeconds(5),
            () -> fixed, ZoneId.of("UTC")));

        // Idempotent tick: time hasn't moved, so no broadcast should fire.
        clockActor.tell(new WorldClock.Tick());

        // We should not see an AmbientChanged in the next short window.
        var ev = fishForAmbient(notifProbe, Duration.ofSeconds(1));
        assertThat(ev).as("no AmbientChanged should fire when phase doesn't change").isNull();
    }

    @Test
    @Timeout(10)
    void getPhaseRepliesToProbe() {
        var fixed = Instant.parse("2026-05-24T13:00:00Z");  // MIDDAY
        var clockActor = spawnAndTrack(WorldClock.create("alpha",
            Duration.ofHours(24), Duration.ofSeconds(5),
            () -> fixed, ZoneId.of("UTC")));
        var probe = testKit.<AmbientPhase>createTestProbe();
        clockActor.tell(new WorldClock.GetPhase(probe.getRef()));
        assertThat(probe.receiveMessage(Duration.ofSeconds(2)))
            .isEqualTo(AmbientPhase.MIDDAY);
    }

    @Test
    void setPhaseForTestsUpdatesStaticCache() {
        WorldClock.setPhaseForTests("synthetic-zone", AmbientPhase.NIGHT);
        assertThat(WorldClock.currentPhase("synthetic-zone")).isEqualTo(AmbientPhase.NIGHT);
        assertThat(WorldClock.currentPhaseOrDefault("synthetic-zone")).isEqualTo(AmbientPhase.NIGHT);
        WorldClock.setPhaseForTests("synthetic-zone", null);
        assertThat(WorldClock.currentPhase("synthetic-zone")).isNull();
        assertThat(WorldClock.currentPhaseOrDefault("synthetic-zone")).isEqualTo(AmbientPhase.MIDDAY);
    }

    @Test
    void createRejectsBadConstructorArgs() {
        Assertions.assertThatThrownBy(() ->
            WorldClock.create(null))
            .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() ->
            WorldClock.create("alpha", Duration.ZERO, Duration.ofMinutes(1),
                Instant::now, ZoneId.of("UTC")))
            .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() ->
            WorldClock.create("alpha", Duration.ofHours(24), Duration.ZERO,
                Instant::now, ZoneId.of("UTC")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ----

    private ActorRef<RoomCommand> spawnRoom(String roomId) {
        var ref = spawnAndTrack(RoomActor.create(roomId));
        RoomRegistry.get().register(roomId, ref);
        return ref;
    }

    /**
     * Drain {@code notifProbe} for up to {@code timeout}, returning the first
     * {@link WorldEvent.AmbientChanged} carried in a {@code RoomNotification},
     * or null if none arrive. Avoids {@code receiveSeveralMessages(N, t)}
     * which fails when fewer than N messages are produced (and the subscriber
     * receives Said/EntityEntered/etc. notifications, not just AmbientChanged).
     */
    private static WorldEvent.AmbientChanged fishForAmbient(
            TestProbe<RoomNotification> probe, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            var slice = Duration.ofMillis(Math.min(200, remaining / 1_000_000));
            try {
                var msg = probe.receiveMessage(slice);
                if (msg.event() instanceof WorldEvent.AmbientChanged ac) {
                    return ac;
                }
            } catch (AssertionError noMsg) {
                // No message in this slice; loop and try again until deadline.
            }
        }
        return null;
    }

    private static void await(BooleanSupplier cond, long timeoutMs) {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class AtomicReference<T> {
        private volatile T value;
        AtomicReference(T v) { this.value = v; }
        static <T> AtomicReference<T> create(T v) { return new AtomicReference<>(v); }
        T get() { return value; }
        void set(T v) { this.value = v; }
    }
}
