package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.InnerImprint;
import org.wyrdsekai.common.model.Posture;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for RoomActor posture handling.
 * Covers SetPosture / ClearPosture command flow, event persistence, subscriber
 * notification, replay-restoration, and entity-leave cleanup.
 */
@Tag("integration")
class RoomActorPostureTest {

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

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> behaviorTestKit;

    @BeforeEach
    void setUp() {
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create("posture-test", null, null, null, null));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private void createRoomAndEnterPlayer(String playerId, String playerName) {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Posture Test Room",
                "A room for posture tests.", "test", List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(playerId, playerName, "player", "north", ref));
    }

    private Posture seatedByHearth() {
        return new Posture("sat", "leather-chair",
            "settles into the worn leather chair, facing the hearth");
    }

    @Test
    void set_posture_persists_event_and_updates_entity_state() {
        createRoomAndEnterPlayer("player-1", "Alice");
        var posture = seatedByHearth();

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", posture, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(result.events()).hasSize(1);
        var event = (WorldEvent.PostureChanged) result.events().get(0).event();
        assertThat(event.entityId()).isEqualTo("player-1");
        assertThat(event.entityName()).isEqualTo("Alice");
        assertThat(event.previous()).isNull();
        assertThat(event.current()).isEqualTo(posture);

        // Entity in room state now has the posture
        var entity = result.state().entities().get("player-1");
        assertThat(entity.posture()).isEqualTo(posture);
    }

    @Test
    void set_posture_notifies_subscribers() {
        createRoomAndEnterPlayer("player-1", "Alice");
        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        var posture = seatedByHearth();
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", posture, ref));

        var notification = probe.expectMessageClass(RoomNotification.class, Duration.ofSeconds(3));
        assertThat(notification.event()).isInstanceOf(WorldEvent.PostureChanged.class);
        var pc = (WorldEvent.PostureChanged) notification.event();
        assertThat(pc.entityName()).isEqualTo("Alice");
        assertThat(pc.current().descriptor()).contains("worn leather chair");
    }

    @Test
    void set_posture_replaces_prior_and_records_previous_in_event() {
        createRoomAndEnterPlayer("player-1", "Alice");
        var first = seatedByHearth();
        var second = new Posture("knelt", null, "kneels by the hearth, watching the embers");

        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", first, ref));
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", second, ref));

        assertThat(result.events()).hasSize(1);
        var event = (WorldEvent.PostureChanged) result.events().get(0).event();
        assertThat(event.previous()).isEqualTo(first);
        assertThat(event.current()).isEqualTo(second);
        assertThat(result.state().entities().get("player-1").posture()).isEqualTo(second);
    }

    @Test
    void set_posture_rejects_null_posture() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", null, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
        var rejected = (RoomResponse.Rejected) result.reply();
        assertThat(rejected.code()).isEqualTo("invalid_posture");
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test
    void set_posture_rejects_unknown_entity() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("ghost-9000", seatedByHearth(), ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
        var rejected = (RoomResponse.Rejected) result.reply();
        assertThat(rejected.code()).isEqualTo("not_found");
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test
    void clear_posture_emits_change_event_with_null_current() {
        createRoomAndEnterPlayer("player-1", "Alice");
        var posture = seatedByHearth();
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", posture, ref));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.ClearPosture("player-1", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(result.events()).hasSize(1);
        var event = (WorldEvent.PostureChanged) result.events().get(0).event();
        assertThat(event.previous()).isEqualTo(posture);
        assertThat(event.current()).isNull();
        assertThat(result.state().entities().get("player-1").posture()).isNull();
    }

    @Test
    void clear_posture_is_idempotent_when_no_prior_posture() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.ClearPosture("player-1", ref));

        // No-op acknowledged; no event persisted.
        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test
    void clear_posture_rejects_unknown_entity() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.ClearPosture("ghost-9000", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) result.reply()).code()).isEqualTo("not_found");
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test
    void posture_survives_actor_restart_via_journal_replay() {
        createRoomAndEnterPlayer("player-1", "Alice");
        var posture = seatedByHearth();
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", posture, ref));

        behaviorTestKit.restart();

        var lookResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("player-1", ref));
        assertThat(lookResult.reply()).isInstanceOf(RoomResponse.Ok.class);
        // Posture restored on the entity in the post-restart state
        assertThat(lookResult.state().entities().get("player-1").posture()).isEqualTo(posture);
    }

    @Test
    void posture_cleared_when_entity_leaves_room() {
        createRoomAndEnterPlayer("player-1", "Alice");
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", seatedByHearth(), ref));

        var leaveResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LeaveRoom("player-1", "Alice", "south", ref));

        assertThat(leaveResult.state().entities()).doesNotContainKey("player-1");
    }

    @Test
    void posture_with_inner_imprint_round_trips_through_event() {
        createRoomAndEnterPlayer("player-1", "Alice");
        var imprint = InnerImprint.ofTanks(
            Map.of("equanimity", 0.02, "energy", 0.005), "settled");
        var posture = new Posture("sat", "leather-chair",
            "settles into the leather chair", Instant.now(), imprint);

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SetPosture("player-1", posture, ref));

        var event = (WorldEvent.PostureChanged) result.events().get(0).event();
        assertThat(event.current().innerImprint()).isEqualTo(imprint);
        assertThat(event.current().innerImprint().triggersOnSet()).isEqualTo("settled");
    }
}
