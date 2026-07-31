package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.test.TestDb;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parental room restriction at the RoomActor entry choke point: a member
 * whose blocked_rooms glob matches this room gets a gentle
 * {@code Rejected("parental_block", ...)} and does NOT enter; unrestricted
 * members enter; with no {@link ParentalControlService} registered the check
 * is a no-op ALLOW. Mirrors {@link RoomActorFurnishingItemTest}'s harness.
 */
@Tag("integration")
class RoomActorParentalBlockTest {

    private static final String ROOM_ID = "parental-block-test";

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
    private AuthService auth;
    private String stewardId;
    private String memberId;

    @BeforeEach
    void setUp() {
        var moderationService = new ModerationService();
        var sanctionEnforcer = new SanctionEnforcer(moderationService);
        behaviorTestKit = EventSourcedBehaviorTestKit.create(
            testKit.system(),
            RoomActor.create(ROOM_ID, null, null, null, sanctionEnforcer));

        var jdbcUrl = TestDb.createInMemory();
        auth = new AuthService(jdbcUrl);
        stewardId = auth.register("operator", "password123", "Masumi").orElseThrow().userId();
        memberId = auth.register("kaz", "password123", "Kaz").orElseThrow().userId();
        ParentalControlService.init(jdbcUrl, new SqlDialect.SQLite(), auth);
    }

    @AfterEach
    void tearDownEach() {
        ParentalControlService.resetForTests();
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private void createRoom() {
        behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.CreateRoom("Test Chamber",
                "A room for parental-block tests.", "test",
                List.of(), List.of(), ref));
    }

    private RoomResponse enter(String entityId, String name) {
        return behaviorTestKit.<RoomResponse>runCommand(
            ref -> new RoomCommand.EnterRoom(entityId, name, "player", "north", ref)).reply();
    }

    @Test
    void blocked_member_is_rejected_with_parental_block() {
        createRoom();
        ParentalControlService.get().setControls(stewardId, memberId,
            null, List.of(ROOM_ID), null, "off");

        var reply = enter(memberId, "Kaz");

        assertThat(reply).isInstanceOf(RoomResponse.Rejected.class);
        var rejected = (RoomResponse.Rejected) reply;
        assertThat(rejected.code()).isEqualTo("parental_block");
        assertThat(rejected.reason()).contains("household rule");
    }

    @Test
    void wildcard_glob_blocks_matching_room() {
        createRoom();
        ParentalControlService.get().setControls(stewardId, memberId,
            null, List.of("parental-*"), null, "off");

        var reply = enter(memberId, "Kaz");

        assertThat(reply).isInstanceOf(RoomResponse.Rejected.class);
        assertThat(((RoomResponse.Rejected) reply).code()).isEqualTo("parental_block");
    }

    @Test
    void unrestricted_member_enters_normally() {
        createRoom();
        // Controls exist for the member but bar a DIFFERENT room.
        ParentalControlService.get().setControls(stewardId, memberId,
            null, List.of("gpu-chamber"), null, "off");

        assertThat(enter(memberId, "Kaz")).isInstanceOf(RoomResponse.Ok.class);
        // And a member with no controls at all.
        assertThat(enter(stewardId, "Masumi")).isInstanceOf(RoomResponse.Ok.class);
    }

    @Test
    void service_not_registered_means_allow() {
        createRoom();
        ParentalControlService.get().setControls(stewardId, memberId,
            null, List.of(ROOM_ID), null, "off");
        // Drop the singleton — bare boots / tests must see no enforcement.
        ParentalControlService.resetForTests();

        assertThat(enter(memberId, "Kaz")).isInstanceOf(RoomResponse.Ok.class);
    }
}
