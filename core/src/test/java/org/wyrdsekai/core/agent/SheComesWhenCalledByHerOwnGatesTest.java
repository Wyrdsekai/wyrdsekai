package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A call is a tell she hears wherever she is, not a teleport. She comes by the follow's
 * gates: asleep is not woken and not queued, a shell is not left, mid-thought or worn thin
 * she comes when the state clears, and only her bondholder is answered. The caller is
 * always told which of these it was, so nobody sits wondering.
 */
class SheComesWhenCalledByHerOwnGatesTest {

    private static final Path SRC = Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private static String handler() throws Exception {
        var src = Files.readString(SRC);
        int a = src.indexOf("private Behavior<Command> onCalledBy(CalledBy msg)");
        assertThat(a).isGreaterThan(0);
        return src.substring(a, src.indexOf("\n    /**", a + 40));
    }

    @Test
    @DisplayName("bondholder only, by person; the follow's gates; sleep is not woken; the caller is told")
    void gates() throws Exception {
        var h = handler();
        assertThat(h).contains("PersonIds.samePerson(bondholder, msg.callerId())");
        assertThat(h).contains("followBlockedReason()");
        assertThat(h).contains("case \"sleeping\"").contains("is asleep");
        assertThat(h).doesNotContain("wakeUp").doesNotContain("isSleeping = false");
        assertThat(h).contains("case \"in_shell\"");
        assertThat(h).contains("pendingFollowRoom = theirRoom").contains("pendingFollowEntity = msg.callerId()");
        assertThat(h).contains("moveToRoomById(theirRoom, \"called\")");
        assertThat(h).contains("registry.roomOf(msg.callerId())");
        // every branch answers the caller
        assertThat(h.split("msg.replyTo\\(\\).tell\\(").length - 1).isGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("the rename authority check compares persons too (the same seam)")
    void renameByPerson() throws Exception {
        var src = Files.readString(SRC);
        int a = src.indexOf("private Behavior<Command> onRenameRequest(RenameRequest msg)");
        var h = src.substring(a, a + 900);
        assertThat(h).contains("PersonIds.samePerson(bondholder, msg.requesterId())");
    }
}
