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
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two persisted room changes a steward's demolition and the upgrade name repair rely on:
 * closing a doorway, and renaming a room. Both survive replay.
 */
@Tag("integration")
class RoomActorRenameAndCloseExitTest {

    private static final String ROOM = "the-garden-4421";

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

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kit;

    @BeforeEach
    void setUp() {
        kit = EventSourcedBehaviorTestKit.create(testKit.system(),
            RoomActor.create(ROOM, null, null, null, new SanctionEnforcer(new ModerationService())));
        kit.<RoomResponse>runCommand(ref -> new RoomCommand.CreateRoom(
            "The Garden — a room where things grow slowly and nobody hurries them along the path",
            "Green.", "home",
            List.of(new Exit("north", "nexus", "The Nexus"), new Exit("to-junk-7040", "junk-7040", "junk")),
            List.of(), ref));
    }

    @AfterEach
    void tearDownEach() { ZoneTopology.resetForTests(); }

    @AfterAll
    static void tearDown() { testKit.shutdownTestKit(); }

    @Test
    @DisplayName("RemoveExit closes the doorway and persists it; a missing doorway is a no-op")
    void removeExit() {
        var r = kit.<RoomResponse>runCommand(ref -> new RoomCommand.RemoveExit("to-junk-7040", ref));
        assertInstanceOf(RoomResponse.Ok.class, r.reply());
        assertFalse(r.state().exits().containsKey("to-junk-7040"));
        assertTrue(r.state().exits().containsKey("north"));
        var again = kit.<RoomResponse>runCommand(ref -> new RoomCommand.RemoveExit("to-junk-7040", ref));
        assertInstanceOf(RoomResponse.Ok.class, again.reply());
        assertFalse(again.hasNoEvents() == false, "nothing persisted the second time");
        kit.restart();
        assertFalse(kit.getState().exits().containsKey("to-junk-7040"), "the closed doorway stays closed after replay");
    }

    @Test
    @DisplayName("RenameRoom changes the name, keeps everything else, and survives replay")
    void rename() {
        var fixed = RoomNaming.repair(kit.getState().name());
        assertEquals("The Garden", fixed);
        var r = kit.<RoomResponse>runCommand(ref -> new RoomCommand.RenameRoom(fixed, null, ref));
        assertInstanceOf(RoomResponse.Ok.class, r.reply());
        assertEquals("The Garden", r.state().name());
        assertEquals("Green.", r.state().description());
        assertEquals(2, r.state().exits().size());
        var same = kit.<RoomResponse>runCommand(ref -> new RoomCommand.RenameRoom("The Garden", null, ref));
        assertTrue(same.hasNoEvents(), "renaming to the same name persists nothing");
        var empty = kit.<RoomResponse>runCommand(ref -> new RoomCommand.RenameRoom("  ", null, ref));
        assertInstanceOf(RoomResponse.Rejected.class, empty.reply());
        kit.restart();
        assertEquals("The Garden", kit.getState().name());
    }
}
