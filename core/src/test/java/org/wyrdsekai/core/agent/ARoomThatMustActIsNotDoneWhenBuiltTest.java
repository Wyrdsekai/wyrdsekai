package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Making the room is half the job.
 *
 * <h2>What went wrong</h2>
 * A template furnishes a place and holds no behaviour. Asked on 2026-08-22 for "a room
 * where anyone who goes in can ask for the weather of a city and state and hear it
 * spoken", she made the Weather Parlor — real, connected, exitable, described exactly as
 * asked — and stopped. The steward walked into an empty room. Creating it had looked like
 * the whole job, and the turn ended in a satisfied report.
 */
class ARoomThatMustActIsNotDoneWhenBuiltTest {

    @Test
    @DisplayName("the steward's own room asks are recognised as needing behaviour")
    void hisRoomAsksNeedBehaviour() {
        String[] asks = {
            "please make me a room called the weather parlor where anyone who goes in can "
                + "ask for the weather of a city and state and hear it spoken",
            "can you make a room where someone can go to look up a topic and hear a short "
                + "briefing about it",
            "please make me a room called the star chart room where anyone who goes in can "
                + "ask about a topic and hear a short briefing",
        };
        for (var ask : asks) {
            assertThat(CompanionActor.asksForARoomThatActs(ask))
                .as("asked: %s", ask)
                .isTrue();
        }
    }

    @Test
    @DisplayName("a room asked for as a place to be is finished when it exists")
    void aPlaceToBeIsJustAPlace() {
        for (var ask : new String[]{
            "make me a quiet room with a fireplace",
            "can you build a greenhouse",
            "i want a room for the two of us"}) {
            assertThat(CompanionActor.asksForARoomThatActs(ask))
                .as("asked: %s", ask)
                .isFalse();
        }
    }

    @Test
    @DisplayName("build-then-room: finishing the tool re-arms for the room it was for")
    void theBuildHalfRemembersTheRoomIsOwed() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        // 2026-08-22 20:07: build-first forced her straight to the workbench, the
        // lighthouse's weather tool came back SUCCEEDED, and no lighthouse was made. The
        // room path already re-armed for the reverse order; this is its mirror.
        assertThat(src).contains("roomOwedByTask.add(spec.taskId().toString());");
        assertThat(src).contains("finished the tool but still owes the ROOM");
    }

    @Test
    @DisplayName("a thing that is not a place is not this case at all")
    void anItemAskIsNotARoomAsk() {
        assertThat(CompanionActor.asksForARoomThatActs(
            "can you make me a tool that tells me the weather")).isFalse();
    }

    /**
     * 2026-08-22 20:27: the tool was built, the re-arm fired, and the re-armed step
     * narrowed straight back to the workbench — a room-that-acts says "tool", so the
     * request-only chooser always picked {@code dispatch_task}. She called goal_done six
     * seconds later. Two joins: the narrowing must ask which HALF is owed, and goal_done
     * must be refused while a room is.
     */
    @Test
    @DisplayName("when a room is owed, the turn narrows to the room-maker and goal_done is refused")
    void anOwedRoomWinsTheNarrowingAndBlocksGoalDone() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        // Scoped since 2026-08-23: the owed room belongs to the request that asked for it.
        assertThat(src).contains("if (thisAskOwesARoom && (roomStillOwedAfterBuild || !roomOwedByTask.isEmpty())) {");
        assertThat(src).contains("Room gate: goal_done blocked");
        // every in-loop narrowing goes through the owed-aware chooser, not the static one
        assertThat(src.split("var wanted = buildFirstToolsFor\\(").length - 1)
            .as("no in-loop call site may bypass the owed-half check")
            .isEqualTo(0);
    }

    /**
     * 20:38:36 the room gate fired and said "call create_room_from_template NOW"; 20:38:37
     * the history-seeded narrowing stripped it (never called this loop), and the force set
     * a line later did not contain it either. The gate demanded a tool she could not reach.
     */
    @Test
    @DisplayName("an owed room's door survives narrowing and is what the force selects")
    void theOwedDoorStaysReachable() throws Exception {
        var owed = CompanionActor.computeReactNarrowingAllowed(
            java.util.List.of("dispatch_task"), false, null,
            java.util.Set.of("create_room_from_template", "decline_with_reason"));
        assertThat(owed).contains("create_room_from_template");
        // The standard list already carries the creation family; the owed set must
        // hold when history is EMPTY and reconsider is in play too — the two branches.
        var reconsidering = CompanionActor.computeReactNarrowingAllowed(
            java.util.List.of(), false, java.util.Set.of("library_search"),
            java.util.Set.of("create_room_from_template"));
        assertThat(reconsidering).contains("create_room_from_template");

        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        assertThat(src).contains("? PLACE_BUILD_TOOLS\n                : Set.of(\"dispatch_task\"");
    }

    /**
     * Home node 2026-08-23 07:20: the library build (a room-that-acts) was in flight, the
     * steward asked for a plain weather TOOL, and the owed-room narrowing forced his new
     * ask to the room door — "no build tool on this dispatch surface — skipped". A debt
     * belongs to the request that incurred it, not to every later turn.
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an owed room does not hijack a later, unrelated build request")
    void anOwedRoomIsScopedToItsOwnRequest() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        assertThat(src).contains("boolean thisAskOwesARoom = asksForARoomThatActs(request);");
        // every gate that acts on the owed room also asks whose turn this is
        assertThat(src.split("turnOwesARoom\\(\\)").length - 1)
            .as("narrowing, goal_done gate, spoken-close gate, force set")
            .isGreaterThanOrEqualTo(4);
    }

    /** 07:33:25 the same morning: a second ask re-pinned the turn mid-force; the owed-room
     *  question was asked of the NEW request and the lantern room was never made. */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("whose-turn is answered by the loop's own request, not the latest pin")
    void theLoopsOwnRequestDecides() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        var body = src.substring(src.indexOf("private boolean turnOwesARoom()"));
        body = body.substring(0, body.indexOf("\n    }\n"));
        assertThat(body.indexOf("reactRequester"))
            .as("the loop's requester must be consulted before the pinned request")
            .isLessThan(body.indexOf("pinnedTurnRequest()"));
    }

    /** CodeZaiku builds return after the loop is over; a flag nobody reads is no re-arm. */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a re-arm with no live loop opens one")
    void aReArmStartsATurn() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        var rearm = src.indexOf("finished the tool but still owes the ROOM");
        var kick = src.indexOf("continueBuildAsReact(\"The tool is built. The ROOM", rearm);
        assertThat(kick).as("the re-arm must open a loop when none is running").isGreaterThan(rearm);
    }

    /** 11:10:00 and 11:10:06: forced create_room_from_template, called twice, both
     *  "blocked by autonomy gate (tier FORBIDDEN)" — a loop opened for the steward's
     *  request was running under own-time rules. */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a loop opened for a person's request is reactive, not own-time")
    void aLoopForAPersonIsReactive() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        var cont = src.indexOf("private void continueAsReact(String mission");
        var set = src.indexOf("if (reactRequester != null && isHumanRequest(reactRequester)) {\n                reactiveInference = true;", cont);
        assertThat(set).as("continueAsReact must mark a human-served loop reactive").isGreaterThan(cont);
    }
}
