package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.household.QuietHours;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quiet hours are kept by the room: a visitor at the door during them is told when the
 * household wakes; a resident, a companion, or a visitor already inside is not turned out.
 */
@Tag("integration")
class RoomActorVisitorQuietHoursTest {

    private static final String ROOM = "nexus-quiet-test";
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
            testKit.system(),
            RoomActor.create(ROOM, null, null, null, new SanctionEnforcer(new ModerationService())));
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Nexus", "A hub.", "test", List.of(), List.of(), ref));
    }

    @AfterEach
    void tearDownEach() { QuietHours.configure(null); }

    @AfterAll
    static void tearDown() { testKit.shutdownTestKit(); }

    private RoomResponse enter(String id, String name, String type) {
        return behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(id, name, type, "north", ref)).reply();
    }

    @Test
    @DisplayName("a visitor is refused at the door during quiet hours, with the hour it may return")
    void visitorRefusedDuringQuietHours() {
        QuietHours.configure("00:00-23:59");                       // quiet all day, for the test
        var reply = enter("u-guest", "Guest (visitor, MCP)", "visitor");
        assertThat(reply).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) reply).code()).isEqualTo("quiet_hours");
        assertThat(((RoomResponse.Rejected) reply).reason()).contains("23:59");
    }

    @Test
    @DisplayName("residents and companions are not bound; nor is a visitor already inside")
    void othersAreNotBound() {
        var guest = enter("u-guest", "Guest (visitor, MCP)", "visitor");
        assertThat(guest).isInstanceOf(RoomResponse.Ok.class);
        QuietHours.configure("00:00-23:59");
        assertThat(enter("u-steward", "Steward", "player")).isInstanceOf(RoomResponse.Ok.class);
        assertThat(enter("companion-wisp", "Wisp", "agent")).isInstanceOf(RoomResponse.Ok.class);
        assertThat(enter("u-guest", "Guest (visitor, MCP)", "visitor")).isInstanceOf(RoomResponse.Ok.class);
    }
}
