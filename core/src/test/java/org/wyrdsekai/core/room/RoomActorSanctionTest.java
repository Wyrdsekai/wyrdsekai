package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies SanctionEnforcer is properly wired into RoomActor.
 */
@Tag("integration")
class RoomActorSanctionTest {

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

    private ModerationService moderationService;
    private SanctionEnforcer sanctionEnforcer;
    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> behaviorTestKit;

    @BeforeEach
    void setUp() {
        moderationService = new ModerationService();
        sanctionEnforcer = new SanctionEnforcer(moderationService);
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create("sanction-test", null, null, null, sanctionEnforcer));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private void createRoom() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Room", "A test room", "foundation",
                List.of(), List.of(), ref));
    }

    @Test
    void unsanctioned_entity_can_speak() {
        createRoom();
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SayInRoom("player-1", "Player 1", "Hello", ref));
        // SayInRoom returns Narrated (not Ok) so client sessions don't redraw
        // the room on top of the speech line — the Said event is published
        // separately and reaches subscribers as Prose.
        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
    }

    @Test
    void muted_entity_cannot_speak() {
        createRoom();
        // Apply PROBATION sanction (blocks speech per canSpeak() logic)
        sanctionEnforcer.applySanction("player-2",
            ModerationService.SanctionLevel.PROBATION, "testing", Duration.ofHours(1));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.SayInRoom("player-2", "Player 2", "I should be blocked", ref));
        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
    }

    @Test
    void banned_entity_cannot_enter() {
        createRoom();
        // Ban player-3
        sanctionEnforcer.applySanction("player-3",
            ModerationService.SanctionLevel.BAN, "testing", null);

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-3", "Player 3", "player", "north", ref));
        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
    }
}
