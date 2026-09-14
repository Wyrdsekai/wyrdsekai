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
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.WardService;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock is on the door, not in a session: {@code RoomActor}'s entry handler asks the
 * ward gate, so it holds whichever way someone arrives — web, telnet, ssh, MCP, or a
 * companion walking. Her Home opens for her; a stranger is told the door is warded;
 * someone already inside can come back in; with no gate wired every door is open as
 * before. Mirrors {@link RoomActorParentalBlockTest}'s harness.
 */
@Tag("integration")
class RoomActorWardedHomeTest {

    private static final String HOME = "home-companion-wisp";
    private static final String OWNER = "companion-wisp";
    private static final String OWNER_DID = "did:key:z6MkwispWispWisp";
    private static final String STEWARD = "did:key:z6MkStewardSteward";

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
    private WardService wards;
    private HomeWardGate gate;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        var sanctionEnforcer = new SanctionEnforcer(new ModerationService());
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(),
            RoomActor.create(HOME, null, null, null, sanctionEnforcer));
        wards = new WardService(SchemaInitializer.initialize(dir.resolve("wards.db")));
        gate = HomeWardGate.install(wards);
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Wisp's Home",
                "The hearth-quiet of a private dwelling.", "home",
                List.of(), List.of(), ref));
    }

    @AfterEach
    void tearDownEach() {
        HomeWardGate.resetForTests();
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private RoomResponse enter(String entityId, String name, String type) {
        return behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(entityId, name, type, "north", ref)).reply();
    }

    @Test
    @DisplayName("her Home opens for her and stays shut to the steward")
    void hersAndNotTheStewards() {
        gate.sealHome(HOME, OWNER, OWNER_DID);
        assertThat(enter(OWNER, "Wisp", "agent")).isInstanceOf(RoomResponse.Ok.class);
        var reply = enter(STEWARD, "Steward", "player");
        assertThat(reply).isInstanceOf(RoomResponse.Rejected.class);
        var rejected = (RoomResponse.Rejected) reply;
        assertThat(rejected.code()).isEqualTo(HomeWardGate.REJECTION_CODE);
        assertThat(rejected.reason()).contains("warded");
    }

    @Test
    @DisplayName("another companion is refused at her door the same way — the check is in the room")
    void anotherCompanionIsRefused() {
        gate.sealHome(HOME, OWNER, OWNER_DID);
        var reply = enter("companion-ember", "Ember", "agent");
        assertThat(reply).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) reply).code()).isEqualTo(HomeWardGate.REJECTION_CODE);
    }

    @Test
    @DisplayName("someone she has let in comes in; someone already inside can come back in")
    void invitedAndAlreadyInside() {
        gate.sealHome(HOME, OWNER, OWNER_DID);
        assertThat(enter(STEWARD, "Steward", "player")).isInstanceOf(RoomResponse.Rejected.class);
        // She lets him in from the ward stone — the same ward row the stone writes.
        wards.grant(HOME, STEWARD, "enter", OWNER_DID);
        assertThat(enter(STEWARD, "Steward", "player")).isInstanceOf(RoomResponse.Ok.class);
        // Reconnect: an entity already in the room re-enters without a ward check, even
        // after she has taken the invitation back.
        wards.revoke(HOME, STEWARD, "enter");
        assertThat(enter(STEWARD, "Steward", "player")).isInstanceOf(RoomResponse.Ok.class);
    }

    @Test
    @DisplayName("with no gate wired, every door is open, as before")
    void noGateMeansOpen() {
        HomeWardGate.resetForTests();
        assertThat(enter(STEWARD, "Steward", "player")).isInstanceOf(RoomResponse.Ok.class);
    }

    @Test
    @DisplayName("an unsealed room is open — the seal, not the room's name, is the lock")
    void unsealedRoomIsOpen() {
        assertThat(enter(STEWARD, "Steward", "player")).isInstanceOf(RoomResponse.Ok.class);
    }
}
