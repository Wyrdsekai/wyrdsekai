package org.wyrdsekai.core.agent.affordance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking for a tool has to reach the tool-maker.
 *
 * <h2>What went wrong</h2>
 * Live on staging 2026-08-22 the steward asked, twice, in the plainest words —
 * <i>"please build me an item called venture_scout"</i> — and the companion answered about
 * wanting his request properly heard, and never dispatched anything. It reads as evasion.
 * It was not: {@code dispatch_task} appeared on ZERO of three affordance menus. Nothing in
 * the intent table named it, so its only route to the menu was description overlap, which
 * is capped at 0.5 and competes with drive pressure. She was asked to build a tool with
 * the tool-building tool absent from her hands.
 */
class AskingForAToolReachesTheToolMakerTest {

    private static final String DISPATCH_DESC =
        "Hand a task to the workshop's coding backend. Use this to BUILD A TOOL OR ITEM "
            + "THAT HAS TO DO SOMETHING — query the library, speak aloud, fetch and "
            + "summarize, calculate, watch for something.";
    private static final String ROOM_DESC =
        "BUILD A NEW ROOM — a physical space with walls, exits, and objects. "
            + "NOT for creating items.";

    @Test
    @DisplayName("the words the steward actually used reach dispatch_task outright")
    void hisRealWordsReachIt() {
        for (var ask : new String[]{
            "please build me an item called venture_scout - i give it a subject and it "
                + "brainstorms radical business ideas",
            "can you make me a tool that looks up a topic on wikipedia",
            "so can you make me a tool / item that allows me to query the library",
            "can you make and give me a tool / item that allows me to query a location"}) {
            assertThat(RequestRelevance.score(ask, "dispatch_task", DISPATCH_DESC))
                .as("asked: %s", ask)
                .isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("asking for a room reaches the room-maker, not the tool-maker")
    void aRoomAskGoesToTheRoomMaker() {
        var ask = "can you make a room where someone can go to look up a topic";
        assertThat(RequestRelevance.score(ask, "create_room_from_template", ROOM_DESC))
            .isEqualTo(1.0);
        assertThat(RequestRelevance.score(ask, "dispatch_task", DISPATCH_DESC))
            .as("a room is not an item — the two asks must not collide at the top score")
            .isLessThan(1.0);
    }

    @Test
    @DisplayName("a question with no thing to build does not summon the builder")
    void ordinaryTalkDoesNotDispatch() {
        for (var ask : new String[]{
            "how are you feeling today",
            "what did we talk about yesterday",
            "tell me about the weather"}) {
            assertThat(RequestRelevance.score(ask, "dispatch_task", DISPATCH_DESC))
                .as("asked: %s", ask)
                .isLessThan(1.0);
        }
    }
}
