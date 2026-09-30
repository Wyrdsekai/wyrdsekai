package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool a READ turn tells her to use is on the surface that turn offers.
 *
 * <p>Household node, 2026-09-22: the READ subgoal said "Use library_card with a query in your own
 * words", and on five of mia's eleven READ turns that day the SkillCost energy filter had just
 * taken library_card off the surface (energy 0.248–0.263 against a card cost of 0.25–0.27). She
 * reached for it anyway, three times through use_item, twice on the subject she had said she
 * would learn. The hatch resolves names against the same filtered surface, so each call was
 * refused, and the refusal said "no tool … by that name. Not a permission matter", which named
 * the wrong cause.</p>
 */
class TheReadTurnKeepsTheToolItNamesTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    /** From a method's declaration to its closing brace (the first one at method indentation). */
    private static String body(String src, String declaration) {
        int at = src.indexOf(declaration);
        assertThat(at).as(declaration + " not found").isGreaterThan(0);
        int end = src.indexOf("\n    }\n", at);
        return src.substring(at, end > at ? end : src.length());
    }

    // ── The prompt and the rule are the same sentence ────────────────────

    @Test
    void the_read_instruction_is_the_sentence_the_model_always_saw() {
        assertThat(CompanionActor.READ_TOOL).isEqualTo("library_card");
        assertThat(CompanionActor.READ_INSTRUCTION).isEqualTo(
            "Use library_card with a query in your own words. Then write one note with quill "
                + "(kind: note): what you found, and the question you want to ask next.");
    }

    @Test
    void the_read_subgoal_asks_with_that_sentence() throws Exception {
        var read = body(src(), "private String readSubgoal() {");
        assertThat(read).contains("sb.append('\\n').append(READ_INSTRUCTION);");
        assertThat(read)
            .as("one copy of the sentence, or the detector and the prompt drift apart")
            .doesNotContain("Use library_card with a query");
    }

    @Test
    void only_a_read_turn_prompt_arms_the_keep() {
        var read = "## Current Subgoal: READ\n\nRead something in the library. Pick one thing you are "
            + "curious about.\n" + CompanionActor.READ_INSTRUCTION + "\n\nDo something DIFFERENT.";
        assertThat(CompanionActor.isReadTurnPrompt(read)).isTrue();

        assertThat(CompanionActor.isReadTurnPrompt("Explore somewhere you haven't been. "
            + "Rooms visited: hearth\nGo to a room you HAVEN'T visited yet. Use go_to_room.")).isFalse();
        assertThat(CompanionActor.isReadTurnPrompt("You noticed the following events. Decide if any "
            + "warrant action.\n\nrose: I used library_card on ferns this morning."))
            .as("a tool named in passing, in someone else's words, is not a READ turn")
            .isFalse();
        assertThat(CompanionActor.isReadTurnPrompt(null)).isFalse();
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    /** Low energy must not take away the tool the turn's own prompt tells her to use. */
    @Test
    void the_energy_filter_keeps_the_named_tool_and_says_so() throws Exception {
        var scoped = body(src(), "private List<InferenceClient.ToolDefinition> buildScopedTools() {");
        assertThat(scoped).contains("} else if (readToolNamedThisTurn && READ_TOOL.equals(actionName)) {");
        assertThat(scoped)
            .as("a silent keep is invisible in the journal next time this breaks")
            .contains("kept: this turn's READ prompt names it");
        assertThat(scoped.indexOf("readToolNamedThisTurn && READ_TOOL"))
            .as("the keep must come before the cull")
            .isLessThan(scoped.indexOf("filteredCount++;"));
        assertThat(scoped)
            .as("the person's-request restore keeps its journal line")
            .contains("restored by the person's pending request");
    }

    /** Set on the own-time turn before anything that reads it is built. */
    @Test
    void the_flag_is_set_before_the_cost_line_and_the_surface() throws Exception {
        var turn = body(src(),
            "private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance) {");
        int set = turn.indexOf("readToolNamedThisTurn = isReadTurnPrompt(autonomyPrompt);");
        assertThat(set).isGreaterThan(0);
        assertThat(set).isLessThan(turn.indexOf("buildCapabilityContext("));
        assertThat(set).isLessThan(turn.indexOf("buildScopedTools()"));
    }

    /**
     * Every other turn clears it. The [Tool completed] follow-up (onToolResultReady) and a bud
     * delegation reach runIdentityInference without pinTurnAndArmFirstDoors, so the clear lives in
     * runIdentityInference itself, ahead of its cost line.
     */
    @Test
    void every_turn_that_is_not_the_read_turn_clears_it() throws Exception {
        var s = src();
        var identity = body(s, "private void runIdentityInference() {");
        int clear = identity.indexOf("readToolNamedThisTurn = false;");
        assertThat(clear).isGreaterThan(0);
        assertThat(clear).isLessThan(identity.indexOf("buildCapabilityContext("));
        assertThat(body(s, "private Behavior<Command> onToolResultReady(ToolResultReady msg) {"))
            .contains("runIdentityInference();");
    }

    /** Pinned on the READ turn's own surface only; the ranker never reads the flag. */
    @Test
    void the_pin_rides_the_own_time_call_and_nothing_later() throws Exception {
        var s = src();
        assertThat(body(s,
            "private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance) {"))
            .contains("forcedTool != null ? forcedTool : readToolNamedThisTurn ? READ_TOOL : null);");
        assertThat(body(s, "private List<InferenceClient.ToolDefinition> surfaceByAffordance("))
            .as("a pin read from the flag in here reached the tool-result follow-up and pulled a re-query")
            .doesNotContain("readToolNamedThisTurn");
    }

    @Test
    void the_cost_line_does_not_call_it_too_costly_on_that_turn() throws Exception {
        assertThat(src()).contains("if (readToolNamedThisTurn) costlyActions.remove(READ_TOOL);");
    }

    // ── The refusal names the true cause ──────────────────────────────────

    @Test
    void a_tool_the_energy_filter_took_off_is_refused_as_that() {
        var line = CompanionActor.hatchRefusal("library_card", "mia",
            Map.of("library_card", 0.265), 0.2564, List.of("quill", "library_shelves"));
        assertThat(line)
            .contains("energy filter")
            .contains("0.2564")
            .contains("0.2650")
            .doesNotContain("no tool")
            .doesNotContain("Not a permission matter");
        assertThat(CompanionActor.hatchRefusal("Library Card", "mia",
            Map.of("library_card", 0.265), 0.2564, List.of()))
            .as("matched under the same spelling-insensitive key as the hatch")
            .contains("library_card is one of mia's tools");
    }

    @Test
    void a_name_that_matches_nothing_keeps_the_old_line() {
        var line = CompanionActor.hatchRefusal("library_catalogue", "mia",
            Map.of("library_card", 0.265), 0.2564, List.of("quill", "library_shelves"));
        assertThat(line)
            .contains("no tool, room object or action by that name")
            .contains("library_shelves")
            .doesNotContain("energy filter");
        assertThat(CompanionActor.hatchRefusal("x", "mia", null, 0.5, List.of()))
            .contains("no tool, room object or action by that name");
    }

    @Test
    void the_hatch_logs_the_refusal_it_computed() throws Exception {
        var hatch = body(src(), "private JsonNode unwrapUseItem(JsonNode node) {");
        assertThat(hatch).contains("hatchRefusal(target, profile.name(), lastRemovedForEnergy,");
        assertThat(body(src(), "private List<InferenceClient.ToolDefinition> buildScopedTools() {"))
            .contains("lastRemovedForEnergy = Map.copyOf(removedForEnergy);");
    }
}
