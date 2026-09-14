package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A companion's Home is hers — and the two places that make that true outside the room
 * itself must stay wired: the provisioner seals the Home when it makes it (and again at
 * every boot, so a Home made before the lock existed is sealed on upgrade), and a door
 * that refuses her sends her back to where she stood instead of leaving her nowhere.
 *
 * <p>Household node, 2026-09-13: her Home had been open to everyone since birth. The
 * provisioner's own comment said "private"; the word was never read.</p>
 */
class AHomeIsHersTest {

    private static final Path ZONE_GUARDIAN =
        Path.of("src/main/java/org/wyrdsekai/core/room/ZoneGuardian.java");
    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
    private static final Path ROOM =
        Path.of("src/main/java/org/wyrdsekai/core/room/RoomActor.java");

    @Test
    @DisplayName("the provisioner seals a Home to its companion whether the room is new or already there")
    void provisionerSealsTheHome() throws IOException {
        var src = Files.readString(ZONE_GUARDIAN);
        var at = src.indexOf("private void provisionHomeRoom(");
        assertTrue(at > 0, "provisionHomeRoom not found");
        var body = src.substring(at, Math.min(src.length(), at + 3000));
        var seal = body.indexOf("gate.sealHome(homeSpec.roomId(), profile.entityId(), profile.did())");
        assertTrue(seal > 0, "the Home must be sealed to her where it is provisioned");
        var branchEnd = body.indexOf("already exists for");
        assertTrue(branchEnd > 0 && seal > branchEnd,
            "the seal runs after BOTH branches — a Home that already existed is sealed at boot too");
    }

    @Test
    @DisplayName("the room asks the gate on entry, before quarantine and capacity")
    void roomAsksTheGate() throws IOException {
        var src = Files.readString(ROOM);
        var at = src.indexOf("onEnterRoom(RoomState state, RoomCommand.EnterRoom cmd)");
        assertTrue(at > 0);
        var body = src.substring(at, Math.min(src.length(), at + 4000));
        var gate = body.indexOf("HomeWardGate.get()");
        var quarantine = body.indexOf("isQuarantined(state)");
        assertTrue(gate > 0 && quarantine > gate, "the ward check belongs at the door, ahead of the room's own rules");
        assertTrue(body.contains("!state.entities().containsKey(cmd.entityId())"),
            "someone already inside may always come back in");
    }

    @Test
    @DisplayName("a door that refuses her sends her back to where she stood")
    void refusedDoorSendsHerBack() throws IOException {
        var src = Files.readString(ACTOR);
        assertTrue(src.contains("roomBeforeMove = previousRoomId;"),
            "moveToRoomById must remember where she came from before the door has answered");
        var at = src.indexOf("ENTRY_REFUSALS.contains(rejected.code())");
        assertTrue(at > 0, "an entry refusal must be recognised as a door, not a verb");
        var body = src.substring(at, Math.min(src.length(), at + 1200));
        assertTrue(body.contains("moveToRoomById(back, \"back\")"), "…and she walks back");
        assertTrue(body.contains("remember("), "…and remembers it as what it was");
    }
}
