package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over ssh the bondholder is in the room as their login id; the bond names them by DID. The
 * follow path compared the two as strings and she never followed anyone who came in over ssh.
 * The room's id is what the registry can look up; the bond's id is who the person is. Both
 * are used, and the comparison is by person.
 */
class SheFollowsThePersonNotTheIdTest {

    private static final Path SRC = Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private static String section(String src, String start) {
        int a = src.indexOf(start);
        assertThat(a).as("found: " + start).isGreaterThan(0);
        int b = src.indexOf("\n    private ", a + start.length());
        return src.substring(a, b > 0 ? b : src.length());
    }

    @Test
    @DisplayName("the bondholder leaving is recognised by person, and the follow carries the room-side id")
    void leavingIsByPerson() throws Exception {
        var src = Files.readString(SRC);
        int left = src.indexOf("case WorldEvent.EntityLeft left ->");
        assertThat(left).isGreaterThan(0);
        var branch = src.substring(left, src.indexOf("case WorldEvent.", left + 40));
        assertThat(branch)
            .contains("PersonIds.samePerson(bondholder, left.entityId())")
            .contains("new FollowAttempt(bondholder, left.entityId())")
            .doesNotContain("bondholder.equals(left.entityId())");
        int entered = src.indexOf("case WorldEvent.EntityEntered entered ->");
        var enteredBranch = src.substring(entered, src.indexOf("case WorldEvent.", entered + 40));
        assertThat(enteredBranch).contains("PersonIds.samePerson(bondholderHere, entered.entityId())");
    }

    @Test
    @DisplayName("the follow looks the room up by the id the room knows, and falls back to the bond's")
    void roomIsLookedUpByTheRoomSideId() throws Exception {
        var src = Files.readString(SRC);
        var handler = section(src, "private Behavior<Command> onFollowAttempt(FollowAttempt msg)");
        assertThat(handler)
            .contains("PersonIds.samePerson(bondholder, msg.bondholderDid())")
            .contains("registry.roomOf(roomSide).or(() -> registry.roomOf(bondholder))")
            .contains("registry.presenceOf(roomSide)")
            .doesNotContain("bondholder.equals(msg.bondholderDid())");
        var deferred = section(src, "private void firePendingFollowIfReady()");
        assertThat(deferred).contains("registry.roomOf(roomSide).or(() -> registry.roomOf(bondholder))");
    }
}
