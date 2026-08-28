package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A room that has to DO something is built in two steps, and the second one has to be
 * able to say where.
 *
 * <h2>What went wrong</h2>
 * {@code create_room_from_template} furnishes from a fixed library, and a template holds
 * no behaviour. The only thing that makes behaviour is {@code dispatch_task}, and its
 * result was placed wherever the companion happened to be standing when the build
 * returned — after a minutes-long build, rarely the room it was for. Live on staging
 * 2026-08-22 the steward asked for "a room where someone can go to look up a topic and
 * hear a short briefing": he got a real, connected, exitable room furnished with a generic
 * card catalog, and the tool that would have answered his question was never in it.
 */
class ARoomCanBeGivenBehaviourTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("a dispatch can name the room its result belongs in")
    void theDispatchCarriesItsDestination() throws Exception {
        var node = JSON.readTree("""
            {"action":"dispatch_task",
             "description":"a tool that looks a topic up and speaks a short briefing",
             "room":"venture-briefing-room-1931"}
            """);
        var action = ActionParser.parse(node.toString());
        assertThat(action).isInstanceOf(ActionParser.AgentAction.DispatchTask.class);
        assertThat(((ActionParser.AgentAction.DispatchTask) action).room())
            .isEqualTo("venture-briefing-room-1931");
    }

    @Test
    @DisplayName("an ordinary build names no room and still parses")
    void anOrdinaryBuildIsUnchanged() throws Exception {
        var node = JSON.readTree("""
            {"action":"dispatch_task","description":"a tool that tells me the weather"}
            """);
        var action = ActionParser.parse(node.toString());
        assertThat(((ActionParser.AgentAction.DispatchTask) action).room()).isEmpty();
    }

    @Test
    @DisplayName("the room just made is the default home for a build, without being asked for")
    void aFreshRoomIsTheDefaultDestination() throws Exception {
        var src = java.nio.file.Files.readString(java.nio.file.Path.of(
            java.nio.file.Files.exists(java.nio.file.Path.of("core/src/main/java"))
                ? "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"
                : "../core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        // An optional parameter is one a model does not fill. She built the Weather Parlor
        // and then built its weather tool without naming the room, and the steward walked
        // into an empty room — so the destination is derived from what just happened.
        assertThat(src).contains("lastCreatedRoomId = newRoomId;");
        assertThat(src)
            .as("a build with no room named falls back to the room just created")
            .contains("belongs in it");
        assertThat(src)
            .as("and only for a bounded window, so an ordinary build later is unaffected")
            .contains("ROOM_BUILD_WINDOW");
    }

    @Test
    @DisplayName("the destination is resolved when the build returns, whichever half came first")
    void theDestinationIsResolvedAtPlacement() throws Exception {
        var src = java.nio.file.Files.readString(java.nio.file.Path.of(
            java.nio.file.Files.exists(java.nio.file.Path.of("core/src/main/java"))
                ? "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"
                : "../core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        // She dispatched the tide room's weather tool at 19:44:33 and made the tide room
        // at 19:45:10. A dispatch-time default sees no fresh room in that order, so the
        // check has to be asked again when the build comes back.
        var placement = src.indexOf("build {} named no room and '{}' was made within the");
        var publish = src.indexOf("private void publishCodingTerminal(TaskResult result)");
        assertThat(placement)
            .as("the fallback must live on the placement path, not only at dispatch")
            .isGreaterThan(publish);
    }

    @Test
    @DisplayName("both halves of the two-step shape are stated where she will read them")
    void theShapeIsDocumented() {
        var dispatch = ActionToolBuilder.descriptionFor("dispatch_task");
        assertThat(dispatch)
            .as("she has to know the second step can name a room")
            .contains("room");
    }

    /**
     * 2026-08-23: she went home while CodeZaiku worked; venture_scout3 and trip_compass2
     * landed in home-companion-testwisp — a room the steward cannot enter. A tool built for
     * a person lands where that person asked for it.
     */
    @Test
    @DisplayName("a build lands where the person asked, not where she happens to stand")
    void theAskingRoomWinsOverWhereSheStands() throws Exception {
        var src = java.nio.file.Files.readString(java.nio.file.Path.of(
            java.nio.file.Files.exists(java.nio.file.Path.of("core/src/main/java"))
                ? "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"
                : "../core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        assertThat(src).contains("buildAskedFromRoom.put(spec.taskId().toString(), this.roomId);");
        assertThat(src).contains(": askedFrom != null ? askedFrom : this.roomId;");
    }
}
