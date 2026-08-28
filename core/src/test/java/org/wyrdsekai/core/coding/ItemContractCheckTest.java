package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The question the backend needs answered before it ships a file.
 *
 * <p>Measured live against the household 9B (2026-08-20): goose wrote the item, and the
 * bridge refused it twice out of two runs for the same reason — a manifest with no
 * {@code commands} block. This is that refusal asked as a question, so the backend can be
 * handed the defect and given another turn instead of shipping work that will be binned.
 */
class ItemContractCheckTest {

    private static final String COMPLIANT = """
        exports.manifest = {
          name: "teller",
          version: "1.0.0",
          description: "Tells a short story about a topic.",
          author: "did:wyrd:goose",
          capabilities: [],
          embodiment: {
            silent: false,
            emits: ["body_language"],
            descriptor_template: "{actor} turns the lens over and speaks"
          },
          commands: [ { label: "Tell a story", args: "<topic>" } ]
        };
        function invoke(params) { return { ok: true }; }
        """;

    /** What goose actually produced, twice: everything but the commands block. */
    private static final String MISSING_COMMANDS = """
        exports.manifest = {
          name: "teller",
          version: "1.0.0",
          description: "Tells a short story about a topic.",
          author: "did:wyrd:goose",
          capabilities: [],
          embodiment: {
            silent: false,
            emits: ["body_language"],
            descriptor_template: "{actor} turns the lens over and speaks"
          }
        };
        function invoke(params) { return { ok: true }; }
        """;

    private static final String NO_ENTRYPOINT = """
        exports.manifest = {
          name: "teller",
          version: "1.0.0",
          description: "Tells a short story about a topic.",
          author: "did:wyrd:goose",
          capabilities: [],
          embodiment: {
            silent: false,
            emits: ["body_language"],
            descriptor_template: "{actor} turns the lens over and speaks"
          },
          commands: [ { label: "Tell a story", args: "<topic>" } ]
        };
        """;

    @Test
    void a_compliant_item_has_no_problem() {
        assertThat(ItemContractCheck.firstProblem(COMPLIANT, "teller")).isEmpty();
        assertThat(ItemContractCheck.isCompliant(COMPLIANT, "teller")).isTrue();
    }

    @Test
    void the_live_failure_is_caught_and_named_actionably() {
        var problem = ItemContractCheck.firstProblem(MISSING_COMMANDS, "teller");
        assertThat(problem).isPresent();
        assertThat(problem.get())
            .as("the repair prompt is only as good as this sentence")
            .contains("commands")
            .contains("Declare at least one entry");
    }

    @Test
    void a_script_that_would_be_dead_on_use_is_caught() {
        var problem = ItemContractCheck.firstProblem(NO_ENTRYPOINT, "teller");
        assertThat(problem).isPresent();
        assertThat(problem.get()).contains("invoke");
    }

    @Test
    void an_empty_or_missing_file_is_a_problem_not_a_pass() {
        // Fail closed: "nothing to check" must never read as "nothing wrong".
        assertThat(ItemContractCheck.firstProblem(null, "x")).isPresent();
        assertThat(ItemContractCheck.firstProblem("   ", "x")).isPresent();
    }

    @Test
    void the_check_and_the_bridge_agree_about_what_registers() {
        // Two gates deciding the same question is a drift hazard. The bridge is the one
        // that actually refuses; this must not be laxer than it, or the backend ships
        // files it believes are fine and the bridge bins them anyway — which is exactly
        // the situation this was built to end.
        assertThat(ItemContractCheck.isCompliant(MISSING_COMMANDS, "teller"))
            .as("the bridge rejects this for missing commands — so must we")
            .isFalse();
        assertThat(ItemContractCheck.isCompliant(NO_ENTRYPOINT, "teller"))
            .as("the bridge rejects a script with no entrypoint — so must we")
            .isFalse();
        assertThat(ItemContractCheck.isCompliant(COMPLIANT, "teller"))
            .as("and neither may refuse a file that is actually fine")
            .isTrue();
    }
}
