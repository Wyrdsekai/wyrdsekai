package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.parlor.ParlorManager;
import org.wyrdsekai.core.parlor.ParlorPresenceMode;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ParlorManager → RoomActor wiring.
 *
 * <p>Verifies:</p>
 * <ul>
 *   <li>Entering a managed Parlor invokes {@link ParlorManager#entered} and bumps
 *       occupancy.</li>
 *   <li>The 11th entry triggers a {@code FULL → SAMPLED} mode transition whose
 *       diegetic narration is delivered to room subscribers as
 *       {@link WorldEvent.Said} from speaker {@code narrator}.</li>
 *   <li>Leaving decrements occupancy.</li>
 *   <li>Rooms NOT registered as managed don't touch the ParlorManager (no
 *       bookkeeping leaks into normal rooms).</li>
 * </ul>
 */
@Tag("integration")
class RoomActorParlorTest {

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

    private static final String PARLOR_ID = "parlor-test";
    private static final String NORMAL_ID = "normal-test";

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> parlorKit;
    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> normalKit;
    private ParlorManager parlorManager;

    @BeforeEach void setUp() {
        ParlorManager.resetForTests();
        parlorManager = ParlorManager.getOrInit(n -> {});
        parlorManager.register(PARLOR_ID);
        // NORMAL_ID is intentionally NOT registered.

        parlorKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create(PARLOR_ID));
        normalKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create(NORMAL_ID));
    }

    @AfterEach void tearDown() {
        ParlorManager.resetForTests();
    }

    @AfterAll static void shutdown() {
        testKit.shutdownTestKit();
    }

    private void createRoom(EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kit,
                             String name) {
        kit.<RoomResponse>runCommand(ref -> new RoomCommand.CreateRoom(
            name, "A test room.", "test", List.of(), List.of(), ref));
    }

    @Test
    void managedParlor_entryIncrementsOccupancy() {
        createRoom(parlorKit, "Parlor");
        parlorKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("u1", "Alice", "player", "north", ref));

        assertThat(parlorManager.snapshot(PARLOR_ID)).isPresent();
        assertThat(parlorManager.snapshot(PARLOR_ID).get().occupancy()).isEqualTo(1);
        assertThat(parlorManager.snapshot(PARLOR_ID).get().mode())
            .isEqualTo(ParlorPresenceMode.FULL);
    }

    @Test
    void managedParlor_leaveDecrementsOccupancy() {
        createRoom(parlorKit, "Parlor");
        parlorKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("u1", "Alice", "player", "north", ref));
        parlorKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LeaveRoom("u1", "Alice", "east", ref));

        assertThat(parlorManager.snapshot(PARLOR_ID).get().occupancy()).isEqualTo(0);
    }

    @Test
    void managedParlor_elevenEntriesTriggerSampledTransitionWithNarration() {
        createRoom(parlorKit, "Parlor");

        var probe = testKit.<RoomNotification>createTestProbe();
        parlorKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        // Push occupancy to 11 — FULL → SAMPLED transition fires on entry 11.
        for (int i = 0; i < 11; i++) {
            var idx = i;
            parlorKit.<RoomResponse>runCommand(
                ref -> new RoomCommand.EnterRoom("u" + idx, "User" + idx,
                    "player", "north", ref));
        }

        assertThat(parlorManager.snapshot(PARLOR_ID).get().mode())
            .isEqualTo(ParlorPresenceMode.SAMPLED);

        // Drain subscriber queue. The 11 entries produce 11 EntityEntered
        // notifications AND one Said(narrator) narration for the transition.
        // Collect everything that arrives within a short window and verify
        // exactly one narrator-said event appears.
        var received = probe.receiveSeveralMessages(12, Duration.ofSeconds(3));
        var narratorSays = received.stream()
            .map(RoomNotification::event)
            .filter(e -> e instanceof WorldEvent.Said)
            .map(e -> (WorldEvent.Said) e)
            .filter(s -> "narrator".equals(s.entityId()))
            .toList();

        assertThat(narratorSays)
            .as("expected one FULL→SAMPLED narrator narration in %d events", received.size())
            .hasSize(1);
        var text = narratorSays.get(0).text().toLowerCase();
        assertThat(text)
            .as("diegetic narration expected: '%s'", narratorSays.get(0).text())
            .matches(t -> t.contains("busier") || t.contains("voices") || t.contains("fill"));
    }

    @Test
    void unmanagedRoom_doesNotRegisterWithParlorManager() {
        createRoom(normalKit, "Normal");
        normalKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("u1", "Alice", "player", "north", ref));
        normalKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("u2", "Bob", "player", "north", ref));

        // Unmanaged room — ParlorManager shouldn't have any state for it.
        assertThat(parlorManager.snapshot(NORMAL_ID)).isEmpty();
        // And specifically, the managed parlor wasn't touched either.
        assertThat(parlorManager.snapshot(PARLOR_ID)).isEmpty();
    }

    @Test
    void atMaxOccupants_newArrivalRejected() {
        createRoom(parlorKit, "Parlor");
        // Fill ParlorManager to MAX_OCCUPANTS WITHOUT going through RoomActor
        // (room capacity would reject well before 500). We only need the
        // ParlorManager to report at-cap for the pre-persist check.
        for (int i = 0; i < ParlorPresenceMode.MAX_OCCUPANTS; i++) {
            parlorManager.entered(PARLOR_ID, "prefill-" + i);
        }

        var result = parlorKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(
                "overflow", "Overflow", "player", "north", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) result.reply()).code()).isEqualTo("at_capacity");
        // Entry was rejected → no persisted event.
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test
    void existingOccupantAtCap_stillAdmitted() {
        createRoom(parlorKit, "Parlor");
        // Pre-fill and include "already-here" among the occupants.
        parlorManager.entered(PARLOR_ID, "already-here");
        for (int i = 1; i < ParlorPresenceMode.MAX_OCCUPANTS; i++) {
            parlorManager.entered(PARLOR_ID, "prefill-" + i);
        }
        assertThat(parlorManager.snapshot(PARLOR_ID).get().occupancy())
            .isEqualTo(ParlorPresenceMode.MAX_OCCUPANTS);

        // "already-here" reconnecting should succeed — not a new overflow.
        var result = parlorKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(
                "already-here", "Alice", "player", "north", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Ok.class);
    }
}
