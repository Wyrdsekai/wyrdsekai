package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.affordance.ToolAffordance;
import org.wyrdsekai.core.agent.affordance.ToolAffordanceRanker;
import org.wyrdsekai.core.library.ReadingLedger;
import org.wyrdsekai.core.mcp.McpServerManager;
import org.wyrdsekai.core.mcp.McpToolIndex;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reading turn knew one way to read: search. It pointed her at what her last search left open,
 * named only the card, and the card answers with its ten nearest passages whatever the subject: a
 * subject the shelves hold little on came back looking like weak results, and the next turn asked
 * again in other words. The book that answered it sat on the shelf, open at the part a search had
 * read from. The house library's research desk, the door for such a subject, was ranked as a
 * household item and on none of her reading menus; a question handed to it would have landed in
 * the library with nothing to tell her. And a finding was filed without the words that found it,
 * so nothing downstream could know what she had asked.
 *
 * <p>Now the question travels with its answer, an absence is kept as an absence, the library's
 * reading calls are reading, and the turn reads her situation: the book she has open leads, a desk
 * answer waiting leads, a subject asked often is named with how often and with the desk.</p>
 */
class HerReadingTurnNamesTheBookAndTheDeskTest {

    // ── The question travels with its answer ────────────────────────────

    @Test
    void a_tools_answer_carries_the_question_its_call_carried() {
        var answer = Map.<String, Object>of("findings", "The sources say…", "sources", List.of("S1"));
        var withIt = CompanionActor.withTheQuestion(answer, Map.of("query", "tide pools", "agentDid", "did:x"));
        assertThat(withIt).containsEntry("query", "tide pools").containsEntry("findings", "The sources say…");

        var said = Map.<String, Object>of("findings", "…", "query", "its own");
        assertThat(CompanionActor.withTheQuestion(said, Map.of("query", "tide pools"))).containsEntry("query", "its own");
        assertThat(CompanionActor.withTheQuestion(answer, Map.of("query", " "))).isSameAs(answer);
        assertThat(CompanionActor.withTheQuestion(answer, null)).isSameAs(answer);
        assertThat(CompanionActor.withTheQuestion(null, Map.of())).isNull();
    }

    @Test
    void the_shelves_saying_they_hold_nothing_is_an_absence_not_a_finding() {
        for (var s : List.of("The sources do not cover moyamoya disease or its imaging findings; they describe other things.",
                "The sources do not address the pathophysiology of moyamoya disease.",
                "The source material does not discuss cross-attention.",
                "The sources do not mention diffusion models.",
                "The question is not covered by the sources.")) {
            assertThat(CompanionActor.looksLikeAbsenceFinding(s)).as(s).isTrue();
        }
        assertThat(CompanionActor.looksLikeAbsenceFinding(
            "The sources describe self-attention as the mechanism where a model relates positions [S1]; they do not cover cross-attention."))
            .as("a digest that cites a source answered part of it").isFalse();
    }

    // ── What she reads stays with her ────────────────────────────────────

    @Test
    void what_she_took_is_her_words_or_what_she_wrote_down() {
        assertThat(CompanionActor.wordsSheTook("  It says the noise is learned.  ", "{}")).isEqualTo("It says the noise is learned.");
        assertThat(CompanionActor.wordsSheTook("", "```json\n{\"action\":\"quill\",\"title\":\"part 3\",\"content\":\"It says \\\"less\\\" is more.\\nAnd then some.\"}\n```"))
            .isEqualTo("It says \"less\" is more.\nAnd then some.");
        assertThat(CompanionActor.wordsSheTook(null, "{\"action\":\"go_to_room\",\"room\":\"library\"}")).isNull();
        assertThat(CompanionActor.wordsSheTook(" ", null)).isNull();
        assertThat(CompanionActor.readingTurnPrompt("T — part 3 of 9:\n\nthe text"))
            .startsWith("## Reading\n\nThis is the part you just opened:\n\nT — part 3 of 9:\n\nthe text\n\n")
            .endsWith(CompanionActor.WHAT_SHE_TAKES);
    }

    @Test
    void a_finished_book_lists_what_she_took_from_it() {
        var notes = List.of(new ReadingLedger.Note("b", 1, "one", 1L),
            new ReadingLedger.Note("b", 2, "two", 2L),
            new ReadingLedger.Note("b", 3, "three", 3L));
        assertThat(CompanionActor.takenFrom(notes, 2)).isEqualTo("\n  after part 2: two\n  after part 3: three\n  and 1 earlier");
        assertThat(CompanionActor.takenFrom(notes, 5)).isEqualTo("\n  after part 1: one\n  after part 2: two\n  after part 3: three");
    }

    // ── The desk ─────────────────────────────────────────────────────────

    @Test
    void the_desks_reply_names_its_job_even_behind_a_notice() {
        assertThat(CompanionActor.jobIdIn("{\"job_id\":\"J-17\",\"state\":\"queued\"}")).isEqualTo("J-17");
        assertThat(CompanionActor.jobIdIn("{\"notice\":\"share these lines\",\"job_id\":\"J-18\",\"state\":\"queued\"}")).isEqualTo("J-18");
        assertThat(CompanionActor.jobIdIn("{\"job\":{\"job_id\":42}}")).isEqualTo("42");
        assertThat(CompanionActor.jobIdIn("The library is busy; try later.")).isNull();
        assertThat(CompanionActor.jobIdIn(null)).isNull();
    }

    @Test
    void the_desks_question_and_an_openers_entry_travel_with_their_answers() {
        var reply = Map.<String, Object>of("mcp_reply", "{\"job_id\":\"J-1\"}");
        assertThat(CompanionActor.withTheAsk(reply, "library_research", Map.of("question", "what causes it?")))
            .containsEntry("asked", "what causes it?");
        assertThat(CompanionActor.withTheAsk(reply, "library_get", Map.of("id", "I-0042"))).containsEntry("entry", "I-0042");
        assertThat(CompanionActor.withTheAsk(reply, "library_read", Map.of("locator", "lib:I-0042"))).containsEntry("entry", "lib:I-0042");
        assertThat(CompanionActor.withTheAsk(reply, "library_search", Map.of("query", "x"))).isSameAs(reply);
        assertThat(CompanionActor.withTheAsk(reply, "library_research", Map.of())).isSameAs(reply);
    }

    @Test
    void what_the_library_says_about_a_job_becomes_an_answer_only_once_it_is_done() {
        assertThat(CompanionActor.deskAnswerOf("J", "{\"state\":\"running\",\"elapsed_s\":40}")).isNull();
        assertThat(CompanionActor.deskAnswerOf("J", "{\"job\":{\"state\":\"queued\"}}")).isNull();
        var done = CompanionActor.deskAnswerOf("J", "{\"job\":{\"state\":\"done\",\"investigation\":\"I-0042\",\"result\":{\"title\":\"Moyamoya\"}}}");
        assertThat(done).isNotNull();
        assertThat(done.entryId()).isEqualTo("I-0042");
        assertThat(done.title()).isEqualTo("Moyamoya");
        assertThat(done.failed()).isFalse();
        var noticed = CompanionActor.deskAnswerOf("J", "Share these lines first. {\"state\":\"done\",\"investigation\":\"I-7\"}");
        assertThat(noticed).as("a notice before the JSON").isNotNull();
        assertThat(noticed.entryId()).isEqualTo("I-7");
        var failed = CompanionActor.deskAnswerOf("J", "{\"state\":\"failed\"}");
        assertThat(failed).isNotNull();
        assertThat(failed.failed()).isTrue();
        assertThat(CompanionActor.deskAnswerOf("J", "not json")).isNull();
        assertThat(CompanionActor.deskAnswerOf("J", null)).isNull();
    }

    // ── The library's calls on her menu ─────────────────────────────────

    @Test
    void the_house_librarys_reading_calls_are_reading() {
        for (var call : List.of("library_search", "library_read", "library_research", "library_ask", "library_get")) {
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
    void the_turn_the_surface_and_the_record_are_wired() throws Exception {
        var s = src();
        assertThat(s).as("the question travels with a scripted tool's answer")
            .contains("var result = withTheQuestion(itemScriptExecutor.execute(itemId, script, params, provider, itemCaps), params);");
        assertThat(s).as("every search of the shelves is kept").contains("noteLibraryAsk(toolName, result, findings);")
            .contains("LibraryAsks.shared().record(herDid(), action.query(),");
        assertThat(s).as("the turn reads her situation")
            .contains("doors.append(deskAnswerDoor(myDid));\n        doors.append(askedOftenDoor(myDid));")
            .contains("sb.append('\\n').append(READ_TOOL.equals(readTurnPin) ? READ_INSTRUCTION : \"Or u\" + READ_INSTRUCTION.substring(1));");
        assertThat(s).as("the book she has open leads the turn").contains("readTurnPin = \"read_book\";");
        assertThat(s).as("a finding filed without its question is not followed")
            .contains("if (f.query() == null || f.query().isBlank() || !spentReadAnchors.add(f.query())) continue;");
        assertThat(s).as("the door the turn leads with is pinned")
            .contains("forcedTool != null ? forcedTool : readToolNamedThisTurn ? readTurnPin : null);");
        assertThat(s).as("the library's calls the prompt names stay on her menu")
            .contains("for (var call : ToolAffordanceRanker.LIBRARY_READING_CALLS) {\n                    var named = McpToolIndex.qualifyName(house, call);\n                    if (autonomyPromptLower.contains(named)) kept.add(named);");
        assertThat(s).contains("ToolAffordanceRanker.kindOf(n, ActionPolicy.domainFor(n), library)");
        assertThat(s).as("the desk's question travels with its reply, and the reply is noted")
            .contains("payload != null ? withTheAsk(payload, tool, args)")
            .contains("noteDeskAsk(toolName, result, mcpReply);");
        assertThat(s).as("the desk is asked about her questions on a timer")
            .contains("timers.startTimerWithFixedDelay(DESK_TIMER_KEY, new DeskCheck(), DESK_CHECK_EVERY);")
            .contains(".onMessage(DeskAnswered.class, this::onDeskAnswered)");
        assertThat(s).as("her own words are a recall, not a search").contains("handleRecall(new ActionParser.AgentAction.Recall(query));");
    }
}
