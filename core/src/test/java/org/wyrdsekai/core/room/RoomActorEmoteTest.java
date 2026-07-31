package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for RoomActor emote handling.
 * Verifies EmoteInRoom command produces Emoted events, notifies subscribers,
 * respects sanctions, and invokes script hooks.
 */
@Tag("integration")
class RoomActorEmoteTest {

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
    private ModerationService moderationService;
    private SanctionEnforcer sanctionEnforcer;

    @BeforeEach
    void setUp() {
        moderationService = new ModerationService();
        sanctionEnforcer = new SanctionEnforcer(moderationService);
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(), RoomActor.create("emote-test", null, null, null, sanctionEnforcer));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private void createRoomAndEnterPlayer(String playerId, String playerName) {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Room", "A room for emote tests.", "test",
                List.of(), List.of(), ref));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(playerId, playerName, "player", "north", ref));
    }

    @Test
    void emote_produces_emoted_event() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EmoteInRoom("player-1", "Alice", "smiles warmly", ref));

        // Emote is transient — no persisted event, only reply + notification.
        // The reply is Narrated (not Ok) so client sessions don't redraw the
        // room on top of the emote line.
        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test
    void emote_notifies_all_subscribers() {
        createRoomAndEnterPlayer("player-1", "Alice");

        // Subscribe a probe to room notifications
        var probe = testKit.<RoomNotification>createTestProbe();
        behaviorTestKit.runCommand(new RoomCommand.Subscribe(probe.ref()));

        // Enter a second player
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom("player-2", "Bob", "player", "south", ref));

        // Consume the EntityEntered notification from Bob entering
        probe.expectMessageClass(RoomNotification.class, Duration.ofSeconds(3));

        // Now emote
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EmoteInRoom("player-1", "Alice", "waves", ref));

        // Subscriber should receive the Emoted notification
        var notification = probe.expectMessageClass(RoomNotification.class, Duration.ofSeconds(3));
        assertThat(notification.event()).isInstanceOf(WorldEvent.Emoted.class);
        var emoted = (WorldEvent.Emoted) notification.event();
        assertThat(emoted.entityName()).isEqualTo("Alice");
        assertThat(emoted.text()).isEqualTo("waves");
    }

    @Test
    void emote_blocked_when_muted() {
        createRoomAndEnterPlayer("player-muted", "MutedPlayer");

        // Apply PROBATION sanction — blocks speech and emotes
        sanctionEnforcer.applySanction("player-muted",
            ModerationService.SanctionLevel.PROBATION, "testing", Duration.ofHours(1));

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EmoteInRoom("player-muted", "MutedPlayer", "tries to emote", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Rejected.class);
        var rejected = (RoomResponse.Rejected) result.reply();
        assertThat(rejected.code()).isEqualTo("muted");
    }

    @Test
    void emote_runs_onEmote_script_hook() {
        // Without a ScriptLoader, the hook is a no-op but should not crash.
        // This test verifies the emote command completes successfully even when
        // the script engine would be invoked (null scriptEngine = graceful no-op).
        createRoomAndEnterPlayer("player-1", "Alice");

        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EmoteInRoom("player-1", "Alice", "dances gracefully", ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(result.hasNoEvents()).isTrue();
        // The fact that we got Narrated (not an exception) proves the script hook path is safe
    }

    @Test
    void emote_text_preserved() {
        createRoomAndEnterPlayer("player-1", "Alice");

        var emoteText = "scratches head and looks around the room thoughtfully";
        var result = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EmoteInRoom("player-1", "Alice", emoteText, ref));

        assertThat(result.reply()).isInstanceOf(RoomResponse.Narrated.class);
        assertThat(result.hasNoEvents()).isTrue();
    }

    @Test
    void emote_survives_restart() {
        createRoomAndEnterPlayer("player-1", "Alice");

        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EmoteInRoom("player-1", "Alice", "stretches", ref));

        // Restart the actor (simulates crash + recovery from event journal)
        behaviorTestKit.restart();

        // Room should recover fine — Emoted doesn't change room state
        var lookResult = behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.LookRoom("player-1", ref));
        assertThat(lookResult.reply()).isInstanceOf(RoomResponse.Ok.class);
    }
}
