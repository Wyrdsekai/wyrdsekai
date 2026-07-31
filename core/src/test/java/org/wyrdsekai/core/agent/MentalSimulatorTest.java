package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MentalSimulator} prompt-building + parsing logic.
 * The actor-based inference path is exercised via integration tests in
 * the Tier 2 suite; this class covers the deterministic glue.
 */
class MentalSimulatorTest {

    @Test
    void system_prompt_carries_world_model_prefix() {
        var prompt = MentalSimulator.buildSystemPrompt(() -> "ZONE STATE MAP\nhearth (alpha)");

        assertThat(prompt).contains("ZONE STATE MAP");
        assertThat(prompt).contains("hearth (alpha)");
        assertThat(prompt).contains("simulating a companion's plan");
        assertThat(prompt).contains("JSON only");
    }

    @Test
    void system_prompt_handles_null_prefix() {
        var prompt = MentalSimulator.buildSystemPrompt(null);
        assertThat(prompt).contains("simulating a companion's plan");
    }

    @Test
    void system_prompt_handles_null_supplier_value() {
        var prompt = MentalSimulator.buildSystemPrompt(() -> null);
        assertThat(prompt).contains("simulating a companion's plan");
    }

    @Test
    void user_prompt_lists_actions_in_order() {
        var prompt = MentalSimulator.buildUserPrompt(
            "in hearth, alone",
            List.of("examine drives_mirror", "introspect", "tell_agent(operator, 'ok')"));

        assertThat(prompt).contains("Current state: in hearth, alone");
        assertThat(prompt).contains("1. examine drives_mirror");
        assertThat(prompt).contains("2. introspect");
        assertThat(prompt).contains("3. tell_agent(operator, 'ok')");
        assertThat(prompt).contains("Respond JSON");
    }

    @Test
    void user_prompt_handles_null_state() {
        var prompt = MentalSimulator.buildUserPrompt(null, List.of("examine room"));
        assertThat(prompt).contains("Current state: (unspecified)");
        assertThat(prompt).contains("1. examine room");
    }

    @Test
    void parse_well_formed_response() {
        var json = """
            {
              "steps": [
                {"action": "library_search('amae')", "success": "yes", "outcome": "got 12 results"},
                {"action": "read_content(0)", "success": "yes", "outcome": "saw chunk"},
                {"action": "write_journal", "success": "yes", "outcome": "saved"},
                {"action": "goal_done", "success": "yes", "outcome": "plan complete"}
              ],
              "final_state": "in study, journal updated, plan complete",
              "confidence": 0.88,
              "reasoning": "Standard research workflow, no obstacles"
            }
            """;
        var pred = MentalSimulator.parsePrediction(json,
            List.of("library_search('amae')", "read_content(0)", "write_journal", "goal_done"));

        assertThat(pred.parseFailure()).isFalse();
        assertThat(pred.confidence()).isEqualTo(0.88);
        assertThat(pred.predictedEndState()).contains("plan complete");
        assertThat(pred.reasoning()).contains("Standard research workflow");
        assertThat(pred.stepResults()).hasSize(4);
        assertThat(pred.stepResults().get(0).predictedSuccess()).isTrue();
        assertThat(pred.stepResults().get(0).uncertain()).isFalse();
        assertThat(pred.highConfidence()).isTrue();
        assertThat(pred.shouldReject()).isFalse();
    }

    @Test
    void parse_low_confidence_rejection() {
        var json = """
            {
              "steps": [
                {"action": "examine room", "success": "yes", "outcome": "saw 3 objects"},
                {"action": "examine room", "success": "no", "outcome": "loop antipattern"},
                {"action": "examine room", "success": "no", "outcome": "still looping"}
              ],
              "final_state": "stuck in examine loop, no progress",
              "confidence": 0.05,
              "reasoning": "Three identical actions in a row triggers loop antipattern"
            }
            """;
        var pred = MentalSimulator.parsePrediction(json,
            List.of("examine room", "examine room", "examine room"));

        assertThat(pred.confidence()).isEqualTo(0.05);
        assertThat(pred.shouldReject()).isTrue();
        assertThat(pred.highConfidence()).isFalse();
        assertThat(pred.stepResults().get(1).predictedSuccess()).isFalse();
    }

    @Test
    void parse_uncertain_step_marked_uncertain() {
        var json = """
            {
              "steps": [
                {"action": "rare_action", "success": "uncertain", "outcome": "no prior data"}
              ],
              "final_state": "unknown",
              "confidence": 0.5,
              "reasoning": "No history for this action"
            }
            """;
        var pred = MentalSimulator.parsePrediction(json, List.of("rare_action"));

        var step = pred.stepResults().get(0);
        assertThat(step.uncertain()).isTrue();
        assertThat(step.predictedSuccess()).isFalse(); // uncertain ≠ success
    }

    @Test
    void parse_malformed_json_returns_fallback() {
        var pred = MentalSimulator.parsePrediction("not json at all", List.of("examine"));

        assertThat(pred.parseFailure()).isTrue();
        assertThat(pred.confidence()).isEqualTo(0.0);
        assertThat(pred.shouldReject()).isTrue();
        assertThat(pred.reasoning()).contains("json parse failure");
        assertThat(pred.stepResults()).hasSize(1);
        assertThat(pred.stepResults().get(0).action()).isEqualTo("examine");
    }

    @Test
    void parse_empty_content_returns_fallback() {
        var pred = MentalSimulator.parsePrediction("", List.of("examine"));

        assertThat(pred.parseFailure()).isTrue();
        assertThat(pred.confidence()).isEqualTo(0.0);
        assertThat(pred.reasoning()).contains("empty inference content");
    }

    @Test
    void parse_clamps_out_of_range_confidence() {
        var jsonHigh = """
            {"steps":[],"final_state":"x","confidence":1.5,"reasoning":"oops"}
            """;
        var jsonLow = """
            {"steps":[],"final_state":"x","confidence":-0.3,"reasoning":"oops"}
            """;

        assertThat(MentalSimulator.parsePrediction(jsonHigh, List.of()).confidence()).isEqualTo(1.0);
        assertThat(MentalSimulator.parsePrediction(jsonLow, List.of()).confidence()).isEqualTo(0.0);
    }

    @Test
    void simulate_with_null_router_returns_fallback() throws Exception {
        var pred = MentalSimulator.simulate(null, null,
            () -> "prefix", "state", "action").toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(pred.parseFailure()).isTrue();
        assertThat(pred.confidence()).isEqualTo(0.0);
        assertThat(pred.reasoning()).contains("no router");
    }

    @Test
    void simulate_chain_with_empty_actions_returns_fallback() throws Exception {
        var pred = MentalSimulator.simulateChain(null, null,
            () -> "prefix", "state", List.of()).toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(pred.parseFailure()).isTrue();
        assertThat(pred.stepResults()).isEmpty();
    }

    // ── Phase 6.2 step-gate ───────────────────────────────

    @Test
    void executed_state_prefix_passes_through_when_no_steps_done() {
        var prefix = MentalSimulator.buildExecutedStatePrefix("in study", List.of());
        assertThat(prefix).isEqualTo("in study");
    }

    @Test
    void executed_state_prefix_passes_through_when_executed_null() {
        var prefix = MentalSimulator.buildExecutedStatePrefix("in study", null);
        assertThat(prefix).isEqualTo("in study");
    }

    @Test
    void executed_state_prefix_includes_executed_steps_as_ground_truth() {
        var prefix = MentalSimulator.buildExecutedStatePrefix(
            "in library, operator present",
            List.of("library_search('amae')", "read_content(0)"));

        assertThat(prefix).contains("in library, operator present");
        assertThat(prefix).contains("Already completed");
        assertThat(prefix).contains("ground truth");
        assertThat(prefix).contains("1. library_search('amae')");
        assertThat(prefix).contains("2. read_content(0)");
        assertThat(prefix).contains("Predict ONLY the remaining");
    }

    @Test
    void executed_state_prefix_handles_null_current_state() {
        var prefix = MentalSimulator.buildExecutedStatePrefix(null, List.of("introspect"));
        assertThat(prefix).startsWith("(unspecified)");
        assertThat(prefix).contains("1. introspect");
    }

    @Test
    void simulate_remaining_with_null_router_returns_fallback() throws Exception {
        var pred = MentalSimulator.simulateRemaining(null, null,
            () -> "prefix", "state",
            List.of("library_search('amae')"),
            List.of("read_content(0)", "write_journal"))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(pred.parseFailure()).isTrue();
        assertThat(pred.confidence()).isEqualTo(0.0);
        assertThat(pred.stepResults()).hasSize(2);
        assertThat(pred.stepResults().get(0).action()).isEqualTo("read_content(0)");
    }

    @Test
    void simulate_remaining_with_empty_remaining_returns_fallback() throws Exception {
        var pred = MentalSimulator.simulateRemaining(null, null,
            () -> "prefix", "state",
            List.of("library_search('amae')"),
            List.of())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(pred.parseFailure()).isTrue();
        assertThat(pred.stepResults()).isEmpty();
    }

    // ── Path A: bank loading + example rendering ───────────

    @Test
    void load_bank_returns_examples_from_disk_or_classpath() {
        var bank = MentalSimulator.loadBank();
        // Either disk (dev) or classpath (prod) should yield ≥150 examples.
        assertThat(bank).isNotNull();
        assertThat(bank.size())
            .as("bank should be loaded — disk %s or classpath /m2/plan_examples.jsonl",
                "scripts/m2/plan_examples.jsonl")
            .isGreaterThanOrEqualTo(150);
    }

    @Test
    void sample_stratified_returns_4_successes_and_2_failures() {
        var bank = MentalSimulator.loadBank();
        var sample = MentalSimulator.sampleStratified(bank, new Random(42));

        assertThat(sample).hasSize(6); // 4 success + 2 failure
        var successCount = sample.stream().filter(e -> "completed".equals(e.outcome())).count();
        var failureCount = sample.stream().filter(e -> !"completed".equals(e.outcome())).count();
        assertThat(successCount).isEqualTo(4);
        assertThat(failureCount).isEqualTo(2);
    }

    @Test
    void sample_stratified_handles_empty_bank() {
        assertThat(MentalSimulator.sampleStratified(List.of(), new Random(0)))
            .isEmpty();
        assertThat(MentalSimulator.sampleStratified(null, new Random(0)))
            .isEmpty();
    }

    @Test
    void system_prompt_with_examples_renders_step_by_step_traces() {
        var ex = new M2PlanScorer.Example(
            "demo", "Player asked: tell me about amae",
            List.of("library_search('amae')", "summarize", "tell_agent", "goal_done"),
            "completed", 0.92, "standard research");
        var prompt = MentalSimulator.buildSystemPrompt(() -> "ZONE STATE MAP\n", List.of(ex));

        assertThat(prompt).contains("EXAMPLES");
        assertThat(prompt).contains("[EXAMPLE 1: completed]");
        assertThat(prompt).contains("Context: Player asked: tell me about amae");
        assertThat(prompt).contains("Predicted steps:");
        assertThat(prompt).contains("library_search('amae') → success");
        assertThat(prompt).contains("goal_done → success");
        assertThat(prompt).contains("final_state: goal delivered cleanly");
        assertThat(prompt).contains("confidence: 0.92");
    }

    @Test
    void system_prompt_with_failure_example_marks_breakdown() {
        var loopEx = new M2PlanScorer.Example(
            "loop-demo", "Player asked: examine the room three times",
            List.of("examine room", "examine room", "examine room"),
            "loop", 0.05, "loop antipattern");
        var prompt = MentalSimulator.buildSystemPrompt(() -> "", List.of(loopEx));

        assertThat(prompt).contains("[EXAMPLE 1: loop]");
        assertThat(prompt).contains("examine room → success");          // step 1 marked OK
        assertThat(prompt).contains("examine room → no progress (loop antipattern)"); // step 2-3 broken
        assertThat(prompt).contains("plan failed — loop");
        assertThat(prompt).contains("confidence: 0.05");
    }

    @Test
    void system_prompt_without_examples_omits_examples_block() {
        var prompt = MentalSimulator.buildSystemPrompt(() -> "ZONE STATE MAP\n", List.of());
        assertThat(prompt).doesNotContain("EXAMPLES");
        assertThat(prompt).doesNotContain("[EXAMPLE");
        assertThat(prompt).contains("simulating a companion's plan");
    }
}
