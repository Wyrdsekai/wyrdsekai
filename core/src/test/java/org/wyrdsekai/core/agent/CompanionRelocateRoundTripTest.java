package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #477.6 — full round-trip: source companion captures state, hands it off
 * via an in-memory relocator, target spawns a fresh actor pre-loaded with
 * the snapshot. No NATS, no FederationService — exercises the actor-side
 * machinery end-to-end so any breakage in
 * {@link CompanionActor.CaptureTransitState} /
 * {@link CompanionActor.RestoreTransitState} surfaces immediately.
 */
class CompanionRelocateRoundTripTest {

    private static ActorTestKit testKit;

    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "wyrd-relocate", "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd.", 4096, 256, 0.7,
        "did:key:z6MkRelocate");

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("companion-relocate-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test
    void capture_then_restore_preserves_vitality_and_drives() throws Exception {
        var sourceRoom = testKit.<RoomCommand>createTestProbe();
        var router = testKit.<InferenceRouter.Command>createTestProbe();

        var source = testKit.spawn(CompanionActor.create(
            PROFILE, sourceRoom.ref(), "study-source", router.ref(), null));

        // Drain the bring-up handshake.
        sourceRoom.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        sourceRoom.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = sourceRoom.expectMessageClass(RoomCommand.LookRoom.class,
            Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot("study-source")));

        // Force vitality + drives to known values so the round-trip is observable.
        var sentVitality = new VitalityState(0.42, 0.55, 0.66, 0.4, 0.05,
            0.3, 0.5, 0.5, 0.7, 0.0);
        var sentDrives = new DriveState(0.1, 0.7, 0.2, 0.3, 0.5, 0.05, 0.05, 0.4);
        source.tell(new CompanionActor.ForceEnergy(sentVitality.energy()));
        source.tell(new CompanionActor.ForceDrives(sentDrives));

        // Capture state.
        var sink = testKit.<CompanionTransitState>createTestProbe();
        source.tell(new CompanionActor.CaptureTransitState(sink.ref()));
        var captured = sink.expectMessageClass(CompanionTransitState.class,
            Duration.ofSeconds(5));

        assertThat(captured.profile().did()).isEqualTo(PROFILE.did());
        assertThat(captured.drives()).containsEntry("care", 0.7);
        // Vitality energy was forced; the rest comes from the live state.
        assertThat(captured.vitalityTanks()).containsKey("energy");

        // Stop the source actor cleanly.
        source.tell(new CompanionActor.StopForRelocate("test"));

        // Spawn a fresh target actor with the same profile — simulates ARRIVE.
        var targetRoom = testKit.<RoomCommand>createTestProbe();
        var target = testKit.spawn(CompanionActor.create(
            PROFILE, targetRoom.ref(), "docks-target", router.ref(), null));
        targetRoom.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        targetRoom.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var targetLook = targetRoom.expectMessageClass(RoomCommand.LookRoom.class,
            Duration.ofSeconds(5));
        targetLook.replyTo().tell(new RoomResponse.Ok(testSnapshot("docks-target")));

        // Apply the snapshot.
        var restoredVitality = VitalityState.fromMap(captured.vitalityTanks());
        var restoredDrives = DriveState.fromMap(captured.drives());
        target.tell(new CompanionActor.RestoreTransitState(
            restoredVitality, restoredDrives, captured.companionMode()));

        // Read state back via the test query — drives.care should match.
        var readback = new AtomicReference<DriveState>();
        var stateProbe = testKit.<CompanionActor.TestStateResponse>createTestProbe();
        target.tell(new CompanionActor.QueryTestState(stateProbe.ref()));
        var info = stateProbe.expectMessageClass(CompanionActor.TestStateResponse.class,
            Duration.ofSeconds(5));
        readback.set(info.drives());
        assertThat(readback.get().care()).isCloseTo(0.7, within(0.01));
    }

    // --- helpers ---

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }

    private static RoomSnapshot testSnapshot(String roomId) {
        return new RoomSnapshot(
            roomId, roomId, "Test room.", "foundation",
            List.of(new Exit("east", "elsewhere", "Out")),
            List.of(), List.of(), List.of());
    }
}
