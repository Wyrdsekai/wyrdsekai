package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a companion says of a finished build is decided by what the bridge did with the file,
 * not by the repair loop's last word. On the household node the repair rounds ran out, a later
 * round in the same job fixed the file, the bridge registered and placed it, and in the same
 * second she was told it "didnt pass its checks and cant be used yet" (2026-10-25). And a book
 * that keeps nothing is not finished.
 */
class TheVerdictIsWhatLandedTest {

    @AfterEach
    void clean() { ItemContractRepair.resetOutcomesForTests(); }

    @Test
    void a_placed_file_is_the_verdict_and_a_later_refusal_of_a_sibling_does_not_undo_it() {
        ItemContractRepair.noteRefused("t1", "diffusion_practice-3.js: notes.add undeclared");
        ItemContractRepair.notePlaced("t1", "diffusion_practice_3");
        ItemContractRepair.noteRefused("t1", "diffusion_practice.js: still broken");
        var o = ItemContractRepair.outcomeOf("t1").orElseThrow();
        assertThat(o.fate()).isEqualTo(ItemContractRepair.Fate.PLACED);
        assertThat(o.itemId()).isEqualTo("diffusion_practice_3");
        assertThat(ItemContractRepair.outcomeOf("t1")).as("read once").isEmpty();
    }

    @Test
    void a_refusal_and_a_kept_previous_are_told_as_such() {
        ItemContractRepair.noteRefused("t2", "the script calls world.notes.add but the manifest does not declare it");
        assertThat(ItemContractRepair.outcomeOf("t2").orElseThrow().fate()).isEqualTo(ItemContractRepair.Fate.REFUSED);
        ItemContractRepair.noteKeptPrevious("t3", "diffusion_practice", "its version 1.0.0 is lower than the 1.0.1 in use");
        var kept = ItemContractRepair.outcomeOf("t3").orElseThrow();
        assertThat(kept.fate()).isEqualTo(ItemContractRepair.Fate.KEPT_PREVIOUS);
        assertThat(kept.detail()).contains("lower");
        assertThat(ItemContractRepair.outcomeOf("nothing")).isEmpty();
    }

    @Test
    void a_notebook_that_keeps_nothing_is_unfinished_and_one_that_keeps_is_not() {
        var request = "A simple book for recording what you worked on: a project note history log with entries";
        var forgets = """
            exports.manifest = { name: "project_note_history_log", version: "1.0.0", capabilities: [] };
            exports.invoke = function (params) { var entries = []; entries.push(params.args); return { ok: true, summary: "Recorded" }; };
            """;
        var keeps = forgets.replace("capabilities: []", "capabilities: [\"notes.add\"]")
            .replace("entries.push(params.args);", "world.notes.add(params.args, [\"project_note_history_log\"]);");
        assertThat(ItemIntentCheck.unfinished(request, forgets)).hasSize(1).first().asString().contains("keeps nothing");
        assertThat(ItemIntentCheck.unfinished(request, keeps)).isEmpty();
        assertThat(ItemIntentCheck.gaps(request, forgets, null)).anyMatch(g -> g.contains("KEEPS entries"));
        assertThat(ItemIntentCheck.unfinished("a lantern that glows", forgets)).as("nothing asked to be kept").isEmpty();
    }
}
