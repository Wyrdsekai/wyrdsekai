package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link M2PlanScorer} bank loading, sampling, prompt building,
 * and JSON parsing. The actor-based inference path is exercised via integration
 * tests in the Tier 2 suite.
 */
class M2PlanScorerTest {

    private static final String BANK = """
        {"plan_id":"s1","context":"ctx","actions":["a","b","goal_done"],"outcome":"completed","confidence":0.9,"notes":""}
        {"plan_id":"s2","context":"ctx","actions":["a","goal_done"],"outcome":"completed","confidence":0.92,"notes":""}
        {"plan_id":"s3","context":"ctx","actions":["a","b","c","goal_done"],"outcome":"completed","confidence":0.88,"notes":""}
        {"plan_id":"f1","context":"ctx","actions":["a","a","a"],"outcome":"loop","confidence":0.05,"notes":""}
        {"plan_id":"f2","context":"ctx","actions":["goal_done"],"outcome":"premature_done","confidence":0.05,"notes":""}
        """;

    @Test
    void parses_jsonl_bank() throws Exception {
        var examples = M2PlanScorer.parseJsonl(BANK.getBytes());
        assertThat(examples).hasSize(5);
        assertThat(examples.get(0).planId()).isEqualTo("s1");
        assertThat(examples.get(0).isSuccess()).isTrue();
        assertThat(examples.get(3).outcome()).isEqualTo("loop");
        assertThat(examples.get(3).isSuccess()).isFalse();
    }

    @Test
    void stratified_sample_pulls_from_each_bucket() throws Exception {
        var examples = M2PlanScorer.parseJsonl(BANK.getBytes());
        var scorer = new M2PlanScorer(examples, 42L);
        var sample = scorer.sampleStratifiedForTest();
        // Bank has 3 successes + 2 failures + 0 edge. Sampler should return all 3 + all 2.
        var successCount = (int) sample.stream().filter(M2PlanScorer.Example::isSuccess).count();
        var failureCount = (int) sample.stream()
            .filter(e -> !e.isSuccess() && !e.isEdge()).count();
        assertThat(successCount).isEqualTo(3);
        assertThat(failureCount).isEqualTo(2);
    }

    @Test
    void empty_bank_loads_safely() {
        var scorer = new M2PlanScorer(List.of(), 1L);
        assertThat(scorer.bankSize()).isZero();
        assertThat(scorer.sampleStratifiedForTest()).isEmpty();
    }

    @Test
    void score_with_null_router_returns_fallback() throws Exception {
        var examples = M2PlanScorer.parseJsonl(BANK.getBytes());
        var scorer = new M2PlanScorer(examples, 1L);
        var score = scorer.score(null, null, "ctx", List.of("a","b","goal_done"))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(score.parseFailure()).isTrue();
        assertThat(score.confidence()).isEqualTo(0.5);
        assertThat(score.reasoning()).contains("no router");
    }

    @Test
    void score_with_empty_plan_returns_fallback() throws Exception {
        var examples = M2PlanScorer.parseJsonl(BANK.getBytes());
        var scorer = new M2PlanScorer(examples, 1L);
        var score = scorer.score(null, null, "ctx", List.of())
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(score.parseFailure()).isTrue();
    }

    @Test
    void score_with_empty_bank_returns_fallback() throws Exception {
        // Even with non-null router we'd refuse — but here we test the empty-bank short-circuit
        var scorer = new M2PlanScorer(List.of(), 1L);
        var score = scorer.score(null, null, "ctx", List.of("a","goal_done"))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        // Falls into the null-router branch first since router is null — that's the fallback we test
        assertThat(score.parseFailure()).isTrue();
    }

    @Test
    void system_prompt_includes_examples() throws Exception {
        var examples = M2PlanScorer.parseJsonl(BANK.getBytes());
        var prompt = M2PlanScorer.buildSystemPrompt(examples);
        assertThat(prompt).contains("scoring agent plan quality");
        assertThat(prompt).contains("[EXAMPLE 1: completed]");
        assertThat(prompt).contains("[EXAMPLE 4: loop]");
        assertThat(prompt).contains("Plan: a → b → goal_done");
        assertThat(prompt).contains("JSON only");
    }

    @Test
    void user_prompt_renders_candidate() {
        var prompt = M2PlanScorer.buildUserPrompt("Player asked: research X",
            List.of("library_search('X')", "read_content(0)", "tell_agent(operator, '...')", "goal_done"));
        assertThat(prompt).contains("Context: Player asked: research X");
        assertThat(prompt).contains("library_search('X') → read_content(0)");
        assertThat(prompt).contains("Respond JSON");
    }

    @Test
    void user_prompt_handles_null_context() {
        var prompt = M2PlanScorer.buildUserPrompt(null, List.of("examine"));
        assertThat(prompt).contains("Context: (unspecified)");
    }

    @Test
    void parse_well_formed_score() {
        var json = """
            {"confidence": 0.85, "predicted_outcome": "completed", "reasoning": "Standard research workflow"}
            """;
        var score = M2PlanScorer.parseScore(json);
        assertThat(score.parseFailure()).isFalse();
        assertThat(score.confidence()).isEqualTo(0.85);
        assertThat(score.predictedOutcome()).isEqualTo("completed");
        assertThat(score.reasoning()).contains("research");
        assertThat(score.highConfidence()).isTrue();
        assertThat(score.shouldReject()).isFalse();
    }

    @Test
    void parse_low_confidence_score() {
        var json = """
            {"confidence": 0.08, "predicted_outcome": "loop", "reasoning": "Three identical actions"}
            """;
        var score = M2PlanScorer.parseScore(json);
        assertThat(score.confidence()).isEqualTo(0.08);
        assertThat(score.predictedOutcome()).isEqualTo("loop");
        assertThat(score.shouldReject()).isTrue();
        assertThat(score.highConfidence()).isFalse();
    }

    @Test
    void parse_clamps_confidence_out_of_range() {
        assertThat(M2PlanScorer.parseScore(
            "{\"confidence\":1.4,\"predicted_outcome\":\"completed\",\"reasoning\":\"\"}").confidence())
            .isEqualTo(1.0);
        assertThat(M2PlanScorer.parseScore(
            "{\"confidence\":-0.2,\"predicted_outcome\":\"completed\",\"reasoning\":\"\"}").confidence())
            .isEqualTo(0.0);
    }

    @Test
    void parse_malformed_returns_fallback() {
        var score = M2PlanScorer.parseScore("not json");
        assertThat(score.parseFailure()).isTrue();
        assertThat(score.confidence()).isEqualTo(0.5);
        assertThat(score.predictedOutcome()).isEqualTo("uncertain");
    }

    @Test
    void parse_empty_returns_fallback() {
        var score = M2PlanScorer.parseScore("");
        assertThat(score.parseFailure()).isTrue();
        assertThat(score.reasoning()).contains("empty inference content");
    }

    @Test
    void load_default_handles_missing_file_gracefully() {
        var scorer = M2PlanScorer.loadDefault();
        // Should never throw; bank may be empty, populated from disk, or populated from classpath
        assertThat(scorer).isNotNull();
        assertThat(scorer.bankSize()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void classpath_bank_resource_is_present() {
        // Regression guard for the deployment-path bug: in production .deb/.pkg
        // installs the JVM cwd is NOT the repo root, so the disk-relative
        // DEFAULT_BANK_PATH resolves to nothing and loadDefault() falls back
        // to classpath. Verify the resource is actually packaged into core's
        // /m2/plan_examples.jsonl.
        var url = M2PlanScorer.class.getResource("/m2/plan_examples.jsonl");
        assertThat(url)
            .as("Classpath bank must be bundled — see syncM2Bank in core/build.gradle.kts")
            .isNotNull();
    }

    @Test
    void load_default_classpath_path_yields_nonempty_bank() throws Exception {
        // Read the resource directly and parse — same path loadDefault() uses
        // when the disk path is missing. Confirms the bundled JSONL is valid
        // and contains a usable mix of successes + failures.
        try (var in = M2PlanScorer.class.getResourceAsStream("/m2/plan_examples.jsonl")) {
            assertThat(in).isNotNull();
            var bytes = in.readAllBytes();
            var examples = M2PlanScorer.parseJsonl(bytes);
            assertThat(examples.size()).isGreaterThanOrEqualTo(20);
            var successCount = examples.stream().filter(M2PlanScorer.Example::isSuccess).count();
            var failureCount = examples.stream()
                .filter(e -> !e.isSuccess() && !e.isEdge()).count();
            assertThat(successCount).isGreaterThan(0);
            assertThat(failureCount).isGreaterThan(0);
        }
    }
}
