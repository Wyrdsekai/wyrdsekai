package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each mend she asked for of one letter tool left one more object of it in her room, numbered
 * beside the last, all running the same file: five of them by morning (household node,
 * 2026-11-12). A new version of a tool the house already keeps takes the place of the object of
 * that name; a tool that only shares a name with something else still gets a name of its own.
 */
@Tag("integration")
class ANewVersionOfAToolTakesThePlaceOfTheOneHereTest {

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

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> room;

    @BeforeEach
    void setUp() {
        room = EventSourcedBehaviorTestKit.create(testKit.system(),
            RoomActor.create("home-of-a-letter-writer", null, null, null, new SanctionEnforcer(new ModerationService())));
        room.<RoomResponse>runCommand(ref -> new RoomCommand.CreateRoom("Her Room", "A room.", "test", List.of(), List.of(), ref));
    }

    @AfterAll
    static void tearDown() { testKit.shutdownTestKit(); }

    private void place(String id, String name, boolean newVersion) {
        room.runCommand(new RoomCommand.ItemBridgeAction("system:coding-bridge",
            new RoomCommand.ItemBridgeSubAction.AddObject(id, name, "A letter tool.", true, newVersion)));
    }

    private List<String> names() {
        return room.getState().objects().values().stream().map(RoomObject::name).sorted().toList();
    }

    @Test
    void a_new_version_takes_the_place_and_a_stranger_gets_its_own_name() {
        place("codex-first", "absent_letter", false);
        place("codex-mended", "absent_letter", true);
        assertThat(names()).containsExactly("absent_letter");
        assertThat(room.getState().objects()).containsOnlyKeys("codex-mended");

        place("codex-other", "absent_letter", false);
        assertThat(names()).as("not a new version: a name of its own, as before").containsExactly("absent_letter", "absent_letter-2");

        place("codex-alone", "carry_letter", true);
        assertThat(names()).as("a new version with nothing here to replace is simply placed")
            .containsExactly("absent_letter", "absent_letter-2", "carry_letter");
    }
}
