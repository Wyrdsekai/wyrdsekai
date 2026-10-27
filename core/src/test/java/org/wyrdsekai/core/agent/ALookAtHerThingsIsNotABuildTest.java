package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.HouseholdItems;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Check my skills and inventory" is answered at once, never built. The phrasings are the ones
 * two companions sent to the workshop on a household node in two weeks, which built twelve
 * check tools from them (2026-10-27). Names are changed.
 */
class ALookAtHerThingsIsNotABuildTest {

    @Test
    void a_look_at_her_skills_inventory_or_workbench_is_recognised() {
        for (var s : List.of(
                "Check current skills and inventory: list all available tools/functions I can use, and note any items on hand",
                "Check my skills and inventory — list all current skills with status (working/broken/missing), and inventory items",
                "Check current skills list and inventory items for Ada",
                "Check skills and inventory, then examine the workbench for anything that needs fixing or building. Report back what's there.",
                "Check inventory for items needing repair or reorganization. Look at all stored artifacts and note which ones are damaged",
                "Examine the workbench: list all items currently on it, note their states and any tools present. Report back what's there.",
                "Check the workbench — see what needs fixing or building today. Look at current projects, tools, and materials.",
                "Check on my workbench and see what needs attention tonight — review any open tasks, artifacts, or projects",
                "Quick inventory: look at the workbench and shelves in the practice room. List any tools, notes, or objects present.")) {
            assertThat(HerThings.asksForHerThings(s)).as(s).isTrue();
        }
    }

    @Test
    void a_build_a_repair_a_reading_and_a_look_at_some_other_thing_are_not() {
        for (var s : List.of(
                "Write a small tool that checks your current skills (what you can do) and inventory (items you have), then outputs a clean list.",
                "Fix the workbench in The Workshop. It's the main node for crafting — needs to be functional again",
                "Carve a small bird from the remaining wood on the workbench — not finished, just set down where it belongs.",
                "Check the draft pinboard for any pending items that need attention — approve, reject, or leave as-is.",
                "Check on Sam — see if they need anything before I build or fix anything else",
                "Read the file at /tmp/out/quiet_attention.md and summarize it back to me",
                "Check what's in my home room and nearby spaces. List any objects, notes, or items that exist there.",
                "Research how one-shot recognition works in human cognition vs machine learning.",
                "Build a working item for the lab room: a simple-book with notes")) {
            assertThat(HerThings.asksForHerThings(s)).as(s).isFalse();
        }
    }

    @Test
    void the_answer_names_what_she_has_and_leaves_out_what_is_empty() {
        var text = HerThings.describe(List.of("a brass key"), List.of("library_card", "quill"),
            List.of(new HouseholdItems.Unfinished("skills_check", "the script calls world.format, which the world does not have")),
            List.of("old_lantern"), List.of(new HerThings.Project("a small practice for lanterns", 4, "read the second chapter")));
        assertThat(text).contains("You carry: a brass key.")
            .contains("Tools in the house that work: library_card, quill.")
            .contains("Unfinished: skills_check (the script calls world.format")
            .contains("Put away, kept: old_lantern.")
            .contains("a small practice for lanterns (4 entries; next: read the second chapter)");
        assertThat(HerThings.describe(List.of(), List.of(), List.of(), List.of(), List.of()))
            .isEqualTo("You carry nothing yet, have made nothing yet, and have no project open. Nothing needs checking.");
        assertThat(HerThings.describe(List.of(), List.of(), List.of(), List.of(), List.of(new HerThings.Project("x", 1, null))))
            .isEqualTo("Your projects: x (1 entry).");
    }
}
