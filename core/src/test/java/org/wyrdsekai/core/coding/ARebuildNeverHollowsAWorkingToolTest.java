package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Overnight on the household node the workshop rebuilt a companion's practice tool: 775 bytes,
 * no abilities, a fixed reply, version 1.0.0. It replaced the 10 KB one at 1.0.1 that used the
 * model and her notes, the thirteenth version since October 8, and nothing said so. Her request
 * had named the tool with a hyphen where its name has an underscore, so it was not even treated
 * as a repair of it (2026-10-25).
 */
class ARebuildNeverHollowsAWorkingToolTest {

    private static final String RICH = """
        exports.manifest = {
          name: "diffusion_practice",
          version: "1.0.1",
          capabilities: ["llm.complete", "notes.add", "tags.list"],
          embodiment: { silent: true, reason: "private" },
          commands: [{ label: "Practice", args: "" }]
        };
        exports.invoke = function (params) {
          var res = world.llm.complete("a question");
          world.notes.add("attempt", ["diffusion_practice"]);
          return { ok: true, summary: res.text };
        };
        """;

    private static final String HOLLOW = """
        exports.manifest = {
          name: "diffusion_practice",
          version: "1.0.0",
          capabilities: [],
          embodiment: { silent: true, reason: "private" },
          commands: [{ label: "Practice", args: "" }]
        };
        function invoke(params) { return { ok: true, summary: "Ready to practice. Go ahead." }; }
        exports.invoke = invoke;
        """;

    @Test
    void a_lower_version_does_less() {
        assertThat(ItemRevision.doesLess(RICH, HOLLOW)).isPresent().get().asString().contains("1.0.0 is lower than the 1.0.1");
    }

    @Test
    void fewer_abilities_and_nothing_kept_does_less_even_at_the_same_version() {
        var sameVersion = HOLLOW.replace("version: \"1.0.0\"", "version: \"1.0.1\"");
        var why = ItemRevision.doesLess(RICH, sameVersion);
        assertThat(why).isPresent();
        assertThat(why.get()).contains("fewer abilities").contains("keeps nothing");
    }

    @Test
    void a_richer_or_equal_rebuild_may_replace() {
        var richer = RICH.replace("version: \"1.0.1\"", "version: \"1.0.2\"");
        assertThat(ItemRevision.doesLess(RICH, richer)).isEmpty();
        assertThat(ItemRevision.doesLess(RICH, RICH)).as("the same file again is not less").isEmpty();
        // Fewer abilities but still keeping notes: a different tool, not a hollow one; the version rule decides.
        var leaner = RICH.replace("[\"llm.complete\", \"notes.add\", \"tags.list\"]", "[\"notes.add\"]").replace("1.0.1", "1.0.2");
        assertThat(ItemRevision.doesLess(RICH, leaner)).isEmpty();
    }

    @Test
    void the_candidate_is_kept_beside_the_one_in_use(@TempDir Path items) throws Exception {
        var src = items.resolve("scratch").resolve("diffusion_practice.js");
        Files.createDirectories(src.getParent());
        Files.writeString(src, HOLLOW);
        var kept = ItemRevision.keepCandidate(items, "diffusion_practice", src).orElseThrow();
        assertThat(kept.getFileName().toString()).isEqualTo("diffusion_practice.1.0.0-candidate.js");
        var again = ItemRevision.keepCandidate(items, "diffusion_practice", src).orElseThrow();
        assertThat(again.getFileName().toString()).isEqualTo("diffusion_practice.1.0.0-candidate-2.js");
    }

    @Test
    void a_fix_request_names_the_item_by_any_spelling() {
        var known = List.of("diffusion_practice_3", "cross_attention", "project_note_history_log");
        assertThat(ItemRevision.revisionNamedIn("Fix diffusion_practice-3 so it can be used. The manifest is missing…", known))
            .contains("diffusion_practice_3");
        assertThat(ItemRevision.revisionNamedIn("repair the Diffusion Practice 3 tool: it refuses notes.add", known))
            .contains("diffusion_practice_3");
        assertThat(ItemRevision.revisionNamedIn("Update `cross_attention.js` to read the file back", known))
            .contains("cross_attention");
        assertThat(ItemRevision.revisionNamedIn("Write a single-page note on cross-attention in diffusion models", known))
            .as("a build that merely mentions a subject is not a revision").isEmpty();
        assertThat(ItemRevision.revisionNamedIn("REVISE AN EXISTING ITEM. Do not write a new one…", known))
            .as("already a revision instruction").isEmpty();
        assertThat(ItemRevision.revisionNamedIn("fix the thing", known)).isEmpty();
    }

    @Test
    void names_are_compared_in_one_spelling() {
        assertThat(ItemRevision.normalize("Diffusion-Practice 3.js")).isEqualTo("diffusion_practice_3");
        assertThat(ItemRevision.normalize("diffusion_practice_3")).isEqualTo("diffusion_practice_3");
    }
}
