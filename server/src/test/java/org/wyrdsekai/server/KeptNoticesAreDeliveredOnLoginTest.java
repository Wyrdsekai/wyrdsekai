package org.wyrdsekai.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A notice for a person with no live session is kept in the notification service's buffer, and
 * the comment there said it was "flushed on their next connect". It was flushed only on a return
 * from travel to another zone, so a steward who logged in the next morning never saw what was
 * kept (58 notices in a day on the household node, 2026-10-09). Every door now empties it as the
 * person enters the world: the web session by the in-world delivery, the telnet and SSH shells on
 * their own session, since the in-world delivery reaches web sessions only.
 */
class KeptNoticesAreDeliveredOnLoginTest {

    @Test
    @DisplayName("each door empties the buffer right after the person is registered as present")
    void everyDoorFlushes() throws Exception {
        var ws = Files.readString(Path.of("src/main/java/org/wyrdsekai/server/ws/WyrdWebSocket.java"));
        assertThat(ws).contains("entityRegistry.enter(playerId, playerName, \"player\", startRoom);\n            }\n"
            + "            // Notices kept for them while no session of theirs was live are delivered now.");
        assertThat(ws).contains("if (keptNotices != null && playerId != null) keptNotices.flushBuffered(playerId);");

        var telnet = Files.readString(Path.of("src/main/java/org/wyrdsekai/server/telnet/TelnetSession.java"));
        assertThat(telnet).contains("entityRegistry.enter(playerId, playerName, \"player\", currentRoomId);\n            }\n"
            + "            // Notices kept for them while no session of theirs was live, shown at this door now");
        assertThat(telnet).contains("for (var kept : keptNotices.flushBuffered(playerId)) {\n"
            + "                    sessionRef.tell(new ClientSessionActor.SendMessage(kept));");

        var ssh = Files.readString(Path.of("src/main/java/org/wyrdsekai/server/ssh/WyrdShellCommand.java"));
        assertThat(ssh).contains("entityRegistry.enter(playerId, playerName, \"player\", currentRoomId);\n        }\n"
            + "        // Notices kept for them while no session of theirs was live, shown at this door now");
        assertThat(ssh).contains("for (var kept : keptNotices.flushBuffered(playerId)) {\n"
            + "                sessionRef.tell(new ClientSessionActor.SendMessage(kept));");
    }
}
