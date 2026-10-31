package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.affordance.ToolAffordance;
import org.wyrdsekai.core.agent.affordance.ToolAffordanceRanker;
import org.wyrdsekai.core.library.FindingsLedger;
import org.wyrdsekai.core.mcp.McpServerManager;
import org.wyrdsekai.core.mcp.McpToolIndex;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reading turn pointed her at what her last search left open, named only the card, and the card
 * answers with its ten nearest passages whatever the subject: a subject the shelves hold little on
 * came back looking like weak results, and the next turn asked again in other words. The house
 * library's research desk, the door for such a subject, was ranked as a household item and was on
 * none of her menus. Now the turn names the book a passage came from, and how often she has asked
 * lately with the desk; the library's reading calls are reading, and the desk the prompt names is
 * in her hand.
 */
class HerReadingTurnNamesTheBookAndTheDeskTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private static FindingsLedger.Finding asked(String query, Instant at) {
        return new FindingsLedger.Finding("f-" + query.hashCode() + "-" + at.getEpochSecond(), "did:key:z6MkExampleWren",
            "A claim.", FindingsLedger.ClaimType.SYNTHESIS, "medium", FindingsLedger.State.DRAFT, "wren", query,
            at, List.of(), null, null, 1, FindingsLedger.hash("A claim."));
    }

    // ── How often she has asked ─────────────────────────────────────────

    @Test
    void the_same_subject_in_other_words_counts_together() {
        var recent = List.of(
            asked("tide pool ecology intertidal zone animals", NOW.minus(Duration.ofHours(2))),
            asked("intertidal zone animals how tide pools work", NOW.minus(Duration.ofDays(1))),
            asked("tide pool ecology", NOW.minus(Duration.ofDays(2))),
            asked("ecology of the intertidal zone", NOW.minus(Duration.ofDays(3))),
            asked("sourdough starter bread baking", NOW.minus(Duration.ofDays(1))),
            asked("pool cleaning chemicals", NOW.minus(Duration.ofDays(1))));
        int n = CompanionActor.timesAsked("tide pool ecology intertidal zone animals", recent,
            NOW.minus(CompanionActor.ASKED_LATELY));
        assertThat(n).isEqualTo(4);
        assertThat(n).isGreaterThanOrEqualTo(CompanionActor.ASKED_OFTEN);
    }

    @Test
    void one_shared_word_is_not_the_same_subject() {
        var recent = List.of(
            asked("attention in meditation practice", NOW.minus(Duration.ofHours(1))),
            asked("attention span of children", NOW.minus(Duration.ofHours(2))),
            asked("attention economy advertising", NOW.minus(Duration.ofHours(3))));
        assertThat(CompanionActor.timesAsked("attention in meditation practice", recent,
            NOW.minus(CompanionActor.ASKED_LATELY))).isEqualTo(1);
    }

    @Test
    void questions_older_than_a_week_are_not_counted() {
        var recent = List.of(
            asked("tide pool ecology", NOW.minus(Duration.ofDays(1))),
            asked("tide pool ecology", NOW.minus(Duration.ofDays(9))),
            asked("tide pool ecology", NOW.minus(Duration.ofDays(12))));
        assertThat(CompanionActor.timesAsked("tide pool ecology", recent, NOW.minus(CompanionActor.ASKED_LATELY)))
            .isEqualTo(1);
    }

    @Test
    void a_one_word_question_is_not_counted() {
        var recent = List.of(asked("tides", NOW), asked("tides", NOW), asked("tides", NOW));
        assertThat(CompanionActor.timesAsked("tides", recent, NOW.minus(CompanionActor.ASKED_LATELY))).isZero();
    }

    // ── The library's calls on her menu ─────────────────────────────────

    @Test
    void the_house_librarys_reading_calls_are_reading() {
        for (var call : List.of("library_search", "library_read", "library_research", "library_ask")) {
            assertThat(ToolAffordanceRanker.kindOf(McpToolIndex.qualifyName("shelfkeeper", call), null, "shelfkeeper"))
                .as(call).isEqualTo("reading");
        }
        assertThat(ToolAffordanceRanker.kindOf(McpToolIndex.qualifyName("shelfkeeper", "library_db"), null, "shelfkeeper"))
            .as("keeping the library is not reading in it").isEqualTo("household");
        assertThat(ToolAffordanceRanker.kindOf(McpToolIndex.qualifyName("weather", "library_search"), null, "shelfkeeper"))
            .as("another service's tool of the same name").isEqualTo("household");
        assertThat(ToolAffordanceRanker.kindOf(McpToolIndex.qualifyName("shelfkeeper", "library_search"), null, ""))
            .as("no library in this house").isEqualTo("household");
    }

    @Test
    void a_reading_call_takes_a_reading_seat_not_one_of_two_household_seats() {
        var desk = McpToolIndex.qualifyName("shelfkeeper", "library_research");
        var names = new ArrayList<String>();
        for (int i = 0; i < 60; i++) names.add("household_item_" + i);
        names.add(desk);
        var surface = ToolAffordanceRanker.rankSpanning(Map.of(), null, names,
            n -> new ToolAffordance(n, Map.of(), "", n.equals(desk) ? 0.0 : 0.5),
            16, n -> 0.0, n -> ToolAffordanceRanker.kindOf(n, null, "shelfkeeper"), 4);
        assertThat(surface).as("one of each kind first: the desk is the reading kind's").contains(desk);
    }

    @Test
    void the_desk_is_named_only_when_the_library_offers_it() {
        var mgr = new McpServerManager(null);
        assertThat(CompanionActor.researchDeskOf("", mgr)).isNull();
        assertThat(CompanionActor.researchDeskOf("shelfkeeper", mgr)).as("not discovered").isNull();
        mgr.toolIndex().register("shelfkeeper", new JsonRpcMessage.McpTool("library_research", "Research a question.", Map.of()));
        assertThat(CompanionActor.researchDeskOf("shelfkeeper", mgr)).isEqualTo("mcp__shelfkeeper__library_research");
        assertThat(CompanionActor.researchDeskOf(null, mgr)).isNull();
    }

    // ── The wiring ─────────────────────────────────────────────────────

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    void the_turn_and_the_surface_are_wired() throws Exception {
        var s = src();
        assertThat(s).contains("doors = readingDoors(f, recent);");
        assertThat(s).contains("sb.append(doors);\n        sb.append('\\n').append(READ_INSTRUCTION);");
        assertThat(s).as("the desk the prompt names is kept on her menu")
            .contains("if (desk != null && autonomyPromptLower.contains(desk)) kept.add(desk);");
        assertThat(s).contains("ToolAffordanceRanker.kindOf(n, ActionPolicy.domainFor(n), library)");
        assertThat(s).as("the book is opened there by read_book").contains("lastBookFromSearch = place;");
    }
}
