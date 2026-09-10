package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live 2026-09-02: she dispatched a tool from the Nexus, went to the sanctuary
 * to sit with the wait, and when codezaiku placed the finished tool in the
 * Nexus the hand-off looked in the sanctuary — "nothing placed" four times.
 * The hand-off must ask the registry WHERE the task's objects landed, and
 * walk there (leaving the sanctuary properly) before looking again.
 * Source-level guard in the style of GivingIsAMoveNotACopyTest.
 */
class SheWalksBackToHandItOverTest {

    private static String handoffBody() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        var start = src.indexOf("private Behavior<Command> onDispatchHandoff");
        assertThat(start).isPositive();
        var end = src.indexOf("\n    }\n", start);
        return src.substring(start, end);
    }

    @Test
    void handoffLooksUpThePlacementRoomAndWalksThere() throws Exception {
        var body = handoffBody();
        assertThat(body).contains("CodingItemRegistry.get().roomForTask(msg.taskId())");
        assertThat(body).contains("moveToRoomById(placedRoom");
    }

    @Test
    void leavingTheSanctuaryForTheHandoffClearsTheSanctuaryMarker() throws Exception {
        var body = handoffBody();
        assertThat(body).contains("leaveSanctuaryTo(placedRoom");
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        var routine = src.substring(src.indexOf("private void leaveSanctuaryTo("));
        routine = routine.substring(0, routine.indexOf("\n    }\n"));
        assertThat(routine).contains("preSanctuaryRoomId = null");
        assertThat(routine).contains("session.close(");
    }
}
