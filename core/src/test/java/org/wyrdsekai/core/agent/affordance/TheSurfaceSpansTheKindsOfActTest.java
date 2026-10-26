package org.wyrdsekai.core.agent.affordance;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.agent.interiority.WantActBridge;
import org.wyrdsekai.core.config.WyrdConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On her own time a companion is offered one tool of each kind of act before two of any.
 *
 * <p>Ranked by score alone, a week of one household's own-time surfaces (3,492 passes, eight tools
 * each) offered "make a room" in 83% and "go to a room" in 72%; recalling in 0.9%, the journal in
 * 1.4%, a message to someone in 1.3%; and writing a note, reflecting, or a letter in none. Seven
 * household items named after rooms took the rest. Two companions made 133 rooms. What she is not
 * shown she cannot choose (household node, 2026-10-03). operator: "Eight tools at a time - yes we
 * need to measure this right away and fix this".
 */
class TheSurfaceSpansTheKindsOfActTest {

    /** The shape of that household: room verbs and room-named items match her wants' words best. */
    private static final Map<String, Double> RELEVANCE = new LinkedHashMap<>();
    private static final List<String> CANDIDATES = new ArrayList<>();
    static {
        add("create_room_from_template", 0.9); add("quiet_room", 0.85); add("bondholder_quiet_room", 0.8);
        add("quiet_room_settle", 0.8); add("empty_room", 0.75); add("quiet_place", 0.7); add("study_room", 0.7);
        add("go_to_room", 0.65); add("craft_from_template", 0.6); add("close_room", 0.6); add("dispatch_task", 0.4);
        add("dispatch_bunshin", 0.3); add("library_card", 0.25); add("read_book", 0.2); add("journal", 0.1);
        add("quill", 0.1); add("recall", 0.05); add("sending_stone", 0.05); add("tell_agent", 0.0);
        add("write_letter", 0.0); add("note", 0.0); add("reflect", 0.0); add("bear_the_wound", 0.0);
        add("seek_sanctuary", 0.0); add("remember", 0.0); add("examine", 0.0);
    }
    private static void add(String name, double relevance) { CANDIDATES.add(name); RELEVANCE.put(name, relevance); }

    private static final Function<String, ToolAffordance> SEED = AffordanceSeed::forTool;
    private static final Function<String, String> KIND = n -> ToolAffordanceRanker.kindOf(n, ActionPolicy.domainFor(n));
    private static final Map<String, Double> LEVELS = Map.of("Curiosity", 0.27, "Creativity", 0.25, "Restlessness", 0.22,
        "Care", 0.15, "Affiliation", 0.10, "Confidence", 1.0, "Integrity", 1.0);

    @Test
    void by_score_alone_eight_slots_are_rooms_and_nothing_to_read_write_or_reach_with() {
        var eight = ToolAffordanceRanker.rank(LEVELS, null, CANDIDATES, SEED, 8, RELEVANCE::get);
        assertThat(eight).contains("create_room_from_template", "quiet_room", "bondholder_quiet_room", "quiet_room_settle");
        assertThat(eight).doesNotContain("library_card", "read_book", "journal", "recall", "sending_stone", "tell_agent");
    }

    @Test
    void spanning_there_is_one_of_each_kind_in_eight_and_depth_in_sixteen() {
        var eight = ToolAffordanceRanker.rankSpanning(LEVELS, null, CANDIDATES, SEED, 8, RELEVANCE::get, KIND, 2);
        assertThat(eight).hasSize(8);
        var kinds = eight.stream().map(KIND).distinct().toList();
        assertThat(kinds).contains("making", "household", "going", "working", "reading", "keeping", "reaching", "tending");
        assertThat(eight.getFirst()).as("what her want's words point at is still first").isEqualTo("create_room_from_template");

        var sixteen = ToolAffordanceRanker.rankSpanning(LEVELS, null, CANDIDATES, SEED, 16, RELEVANCE::get, KIND, 4);
        assertThat(sixteen).hasSize(16).doesNotHaveDuplicates();
        assertThat(sixteen).contains("library_card", "read_book", "journal", "go_to_room", "dispatch_task");
        assertThat(sixteen.stream().filter(n -> KIND.apply(n).equals("household")).count())
            .as("household items named alike take two places, not seven").isEqualTo(2);
        assertThat(sixteen.stream().filter(n -> KIND.apply(n).equals("reaching")).count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void a_verb_the_want_already_chose_stays_first_and_a_small_list_is_only_ordered() {
        var pinned = ToolAffordanceRanker.rankSpanning(LEVELS, "read_book", CANDIDATES, SEED, 8, RELEVANCE::get, KIND, 2);
        assertThat(pinned.getFirst()).isEqualTo("read_book");
        assertThat(pinned).hasSize(8).doesNotHaveDuplicates();
        var few = List.of("go_to_room", "library_card", "journal");
        assertThat(ToolAffordanceRanker.rankSpanning(LEVELS, null, few, SEED, 8, RELEVANCE::get, KIND, 2))
            .containsExactlyInAnyOrder("go_to_room", "library_card", "journal");
    }

    @Test
    void a_full_tank_of_confidence_is_not_what_pulls_her() {
        assertThat(ToolAffordanceRanker.dominantNeed(LEVELS)).as("the old label").isIn("Confidence", "Integrity");
        assertThat(ToolAffordanceRanker.dominantPull(LEVELS, WantActBridge.NON_PULL)).isEqualTo("Curiosity");
    }

    @Test
    void sixteen_by_default_and_what_she_chose_is_written_down() throws Exception {
        assertThat(WyrdConfig.get().affordanceTopK()).isEqualTo(16);
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains("boolean ownTimeSurface = !reactiveInference && !aPersonAskedForThisTurn();");
        assertThat(ca).contains("ToolAffordanceRanker.rankSpanning(pressures, forced, names, resolve, AFFORDANCE_TOPK, relevance,");
        assertThat(ca).contains("ToolAffordanceRanker.dominantPull(pressures, WantActBridge.NON_PULL),");
        assertThat(ca.split("noteEmittedTool\\(", -1).length - 1).as("both own-time dispatch paths, and the method").isEqualTo(3);
    }

    /**
     * Two companions' own-time prompts named the workshop door on nearly every turn and the
     * notebook on the same turns; one pin went to the workshop and the notebook was ranked out:
     * in her hand on 6% and 13% of turns (household node, 2026-10-26). Every door the prompt
     * names now survives the trim.
     */
    @Test
    void every_named_door_survives_the_trim_not_only_the_first() {
        var withOne = ToolAffordanceRanker.rankSpanning(LEVELS, "dispatch_task", CANDIDATES, SEED, 8, RELEVANCE::get, KIND, 2);
        assertThat(withOne).startsWith("dispatch_task");
        var withBoth = ToolAffordanceRanker.rankSpanning(LEVELS, "dispatch_task", CANDIDATES, SEED, 8, RELEVANCE::get, KIND, 2,
            List.of("dispatch_task", "note", "write_letter"));
        assertThat(withBoth).hasSize(8).startsWith("dispatch_task").contains("note", "write_letter");
        assertThat(withBoth.indexOf("note")).as("kept names come right after the front").isLessThan(3);
        var flat = ToolAffordanceRanker.rank(LEVELS, null, CANDIDATES, SEED, 8, RELEVANCE::get, List.of("recall", "absent_tool"));
        assertThat(flat).hasSize(8).contains("recall").doesNotContain("absent_tool");
        assertThat(ToolAffordanceRanker.rank(LEVELS, null, CANDIDATES, SEED, 8, RELEVANCE::get, List.of()))
            .as("nothing kept: the ranking as before")
            .isEqualTo(ToolAffordanceRanker.rank(LEVELS, null, CANDIDATES, SEED, 8, RELEVANCE::get));
    }
}
