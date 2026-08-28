package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repair loop's gate must be the loader's gate.
 *
 * <h2>What went wrong</h2>
 * Live on staging 2026-08-22: a steward asked for a Wikipedia briefing tool. goose wrote
 * a good one and named it {@code web-sight}. {@link ItemContractCheck} found no problem
 * and the log said "repair SUCCEEDED — it will register"; the loader then refused it
 * ({@code name must match [a-z][a-z0-9_]&#123;2,63&#125;}) and the steward got an inert
 * artifact. The pre-check was asking a SUBSET of what registration asks — the same shape
 * as the 2026-08-21 entrypoint bug, where a textual test stood in for a runtime one.
 *
 * <p>Nothing here is about hyphens. The point is that one validator decides, so a rule
 * added to it tomorrow is enforced in the repair loop without anyone remembering to.
 */
class TheRepairAsksWhatTheLoaderAsksTest {

    private static String itemNamed(String name) {
        return """
            exports.manifest = {
              name: "%s",
              version: "1.0.0",
              description: "A tool that briefs a topic.",
              author: "did:wyrd:test",
              capabilities: ["web.search"],
              embodiment: { silent: false, emits: ["body_language"],
                            descriptor_template: "{actor} reads" },
              commands: [{ label: "Brief a topic", args: "" }]
            };
            function invoke(params) { return { ok: true, summary: "briefed" }; }
            """.formatted(name);
    }

    @Test
    @DisplayName("a manifest the loader would refuse is a defect the repair reports")
    void aLoaderRefusalIsADefect() {
        var problems = ItemContractCheck.problems(itemNamed("web-sight"), "web-sight.js");
        assertThat(problems)
            .as("the repair loop must see what registration will see")
            .isNotEmpty();
        assertThat(String.join(" ", problems)).contains("name");
    }

    @Test
    @DisplayName("a manifest the loader accepts is not invented into a defect")
    void aValidManifestIsLeftAlone() {
        assertThat(ItemContractCheck.problems(itemNamed("web_sight"), "web_sight.js"))
            .as("a harness must never invent a complaint for a coder to chase")
            .isEmpty();
    }

    @Test
    @DisplayName("the rules an author is given come from the validator, not from prose")
    void theContractStatesTheRules() {
        var rules = String.join("\n", ItemManifestValidator.rules());
        // Rendered from the patterns the loader enforces, so the two cannot drift.
        assertThat(rules).contains("[a-z][a-z0-9_]{2,63}");
        assertThat(ItemApiSurface.manifestRulesBlock())
            .as("the authoring model is told what registration requires")
            .contains("[a-z][a-z0-9_]{2,63}");
        // And it survives a node with no external keys at all, where adapterBlock is empty.
        assertThat(ItemApiSurface.manifestRulesBlock()).isNotBlank();
    }
}
