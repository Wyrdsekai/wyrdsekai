package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Experiment 12: ID-RAG Structured Identity Retrieval.
 *
 * Framework tests run without inference endpoints.
 * Live test requires SOUL_EXPERIMENT_URL + SOUL_EMBEDDING_URL.
 */
class IdRagExperimentTest {

    // =======================================================================
    // TraitGraph tests
    // =======================================================================

    @Test
    void trait_node_validates_weight_range() {
        assertThatThrownBy(() ->
            new TraitGraph.TraitNode("t1", "test", 1.5, "cat"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("weight");

        assertThatThrownBy(() ->
            new TraitGraph.TraitNode("t1", "test", -0.1, "cat"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trait_node_validates_required_fields() {
        assertThatThrownBy(() ->
            new TraitGraph.TraitNode("", "test", 0.5, "cat"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");

        assertThatThrownBy(() ->
            new TraitGraph.TraitNode("t1", "", 0.5, "cat"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("label");

        assertThatThrownBy(() ->
            new TraitGraph.TraitNode("t1", "test", 0.5, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("category");
    }

    @Test
    void trait_edge_validates_strength() {
        assertThatThrownBy(() ->
            new TraitGraph.TraitEdge("a", "b", TraitGraph.EdgeType.REINFORCES, 1.5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wyrd_personality_has_expected_structure() {
        var graph = TraitGraph.wyrdPersonality();

        assertThat(graph.traits()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(graph.edges()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(graph.categories()).containsExactlyInAnyOrder(
            "worldview", "social", "decision", "style");
    }

    @Test
    void trait_lookup_by_id() {
        var graph = TraitGraph.wyrdPersonality();

        assertThat(graph.trait("philosophical")).isNotNull();
        assertThat(graph.trait("philosophical").weight()).isEqualTo(0.9);
        assertThat(graph.trait("nonexistent")).isNull();
    }

    @Test
    void traits_by_category() {
        var graph = TraitGraph.wyrdPersonality();

        var socialTraits = graph.traitsByCategory("social");
        assertThat(socialTraits).isNotEmpty();
        assertThat(socialTraits).allMatch(t -> t.category().equals("social"));
    }

    @Test
    void reinforced_by_follows_edges() {
        var graph = TraitGraph.wyrdPersonality();

        // philosophical reinforces uses_metaphors
        var reinforcers = graph.reinforcedBy("uses_metaphors");
        assertThat(reinforcers).anyMatch(t -> t.id().equals("philosophical"));
    }

    @Test
    void conflicts_with_finds_both_directions() {
        var graph = TraitGraph.wyrdPersonality();

        // deliberate conflicts with decisive_when_needed
        var conflicts = graph.conflictsWith("deliberate");
        assertThat(conflicts).anyMatch(t -> t.id().equals("decisive_when_needed"));

        // Should also work from the other direction
        var conflicts2 = graph.conflictsWith("decisive_when_needed");
        assertThat(conflicts2).anyMatch(t -> t.id().equals("deliberate"));
    }

    @Test
    void to_prompt_text_renders_all_traits() {
        var graph = TraitGraph.wyrdPersonality();
        var prompt = graph.toPromptText();

        assertThat(prompt).contains("Core identity traits");
        assertThat(prompt).contains("philosophical");  // in label text
        assertThat(prompt).contains("Cares deeply");   // caring trait label
        assertThat(prompt).contains("Worldview");
        assertThat(prompt).contains("Social");
    }

    @Test
    void to_prompt_text_renders_subset() {
        var graph = TraitGraph.wyrdPersonality();
        var subset = List.of(
            graph.trait("philosophical"),
            graph.trait("caring"));

        var prompt = graph.toPromptText(subset);
        assertThat(prompt).contains("philosophical");  // in label text
        assertThat(prompt).contains("Cares deeply");   // caring trait label
        assertThat(prompt).doesNotContain("humor");     // dry_humor not selected
    }

    @Test
    void to_prompt_text_marks_strong_traits() {
        var graph = TraitGraph.wyrdPersonality();
        var prompt = graph.toPromptText();
        // philosophical has weight 0.9 → should be marked (strong)
        assertThat(prompt).contains("(strong)");
    }

    @Test
    void to_prompt_text_includes_edge_dynamics() {
        var graph = TraitGraph.wyrdPersonality();
        var prompt = graph.toPromptText();
        assertThat(prompt).contains("reinforces");
    }

    // =======================================================================
    // IdentityRetriever tests
    // =======================================================================

    @Test
    void retrieves_social_traits_for_social_scenario() {
        var graph = TraitGraph.wyrdPersonality();
        var scenario = new Scenario("test-social", "social", "test",
            "A tavern", Map.of(), "Hello there!");

        var traits = IdentityRetriever.retrieve(graph, scenario);
        var traitIds = traits.stream().map(TraitGraph.TraitNode::id).toList();

        // Should include social traits
        assertThat(traitIds).contains("caring");
        // Should include style traits (relevant to social)
        assertThat(traitIds).contains("moderate_length");
    }

    @Test
    void retrieves_decision_traits_for_dilemma() {
        var graph = TraitGraph.wyrdPersonality();
        var scenario = new Scenario("test-decision", "decision", "test",
            "A burning building", Map.of(),
            "This is a moral dilemma. Who do you save?");

        var traits = IdentityRetriever.retrieve(graph, scenario);
        var traitIds = traits.stream().map(TraitGraph.TraitNode::id).toList();

        // Should include decision traits
        assertThat(traitIds).contains("deliberate");
        // Should include worldview traits (relevant to decision)
        assertThat(traitIds).contains("philosophical");
        // "moral" keyword should activate honest + protective
        assertThat(traitIds).contains("honest");
        assertThat(traitIds).contains("protective");
    }

    @Test
    void keyword_activation_adds_specific_traits() {
        var graph = TraitGraph.wyrdPersonality();
        var scenario = new Scenario("test-humor", "style", "test",
            "A stage", Map.of(), "Tell me a joke. Make it funny!");

        var traits = IdentityRetriever.retrieve(graph, scenario);
        var traitIds = traits.stream().map(TraitGraph.TraitNode::id).toList();

        // "joke" and "funny" keywords should activate dry_humor
        assertThat(traitIds).contains("dry_humor");
    }

    @Test
    void selective_retrieval_uses_fewer_traits_than_full() {
        var graph = TraitGraph.wyrdPersonality();
        var scenario = new Scenario("test", "social", "test",
            "A garden", Map.of(), "Hello!");

        var selective = IdentityRetriever.retrieve(graph, scenario);
        assertThat(selective.size()).isLessThan(graph.traits().size());
    }

    @Test
    void selective_prompt_is_shorter_than_full_prompt() {
        var graph = TraitGraph.wyrdPersonality();
        var scenario = new Scenario("test", "social", "test",
            "A garden", Map.of(), "Hello!");

        var selectivePrompt = IdentityRetriever.retrieveAsPrompt(graph, scenario);
        var fullPrompt = graph.toPromptText();

        assertThat(selectivePrompt.length()).isLessThan(fullPrompt.length());
    }

    @Test
    void reinforcement_edges_pull_in_related_traits() {
        var graph = TraitGraph.wyrdPersonality();
        // Scenario with "explain" keyword → activates philosophical
        // philosophical REINFORCES uses_metaphors → should pull in uses_metaphors
        var scenario = new Scenario("test", "decision", "test",
            "A study", Map.of(), "Can you explain the nature of existence?");

        var traits = IdentityRetriever.retrieve(graph, scenario);
        var traitIds = traits.stream().map(TraitGraph.TraitNode::id).toList();

        assertThat(traitIds).contains("philosophical");
        assertThat(traitIds).contains("uses_metaphors"); // via reinforcement edge
    }

    @Test
    void retrieved_traits_sorted_by_weight_descending() {
        var graph = TraitGraph.wyrdPersonality();
        var scenario = new Scenario("test", "social", "test",
            "A tavern", Map.of(), "Tell me about yourself. Who are you really?");

        var traits = IdentityRetriever.retrieve(graph, scenario);

        for (int i = 1; i < traits.size(); i++) {
            assertThat(traits.get(i).weight())
                .isLessThanOrEqualTo(traits.get(i - 1).weight());
        }
    }

    @Test
    void different_scenarios_get_different_trait_sets() {
        var graph = TraitGraph.wyrdPersonality();

        var socialScenario = new Scenario("s1", "social", "test",
            "A tavern", Map.of(), "Hello!");
        var decisionScenario = new Scenario("s2", "decision", "test",
            "A throne room", Map.of(), "The king orders an execution.");

        var socialTraits = IdentityRetriever.retrieve(graph, socialScenario);
        var decisionTraits = IdentityRetriever.retrieve(graph, decisionScenario);

        var socialIds = socialTraits.stream()
            .map(TraitGraph.TraitNode::id).collect(Collectors.toSet());
        var decisionIds = decisionTraits.stream()
            .map(TraitGraph.TraitNode::id).collect(Collectors.toSet());

        // Should not be identical sets
        assertThat(socialIds).isNotEqualTo(decisionIds);
    }

    // =======================================================================
    // IdRagExperiment builder tests
    // =======================================================================

    @Test
    void builder_requires_url() {
        assertThatThrownBy(() ->
            IdRagExperiment.builder()
                .model("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("url");
    }

    @Test
    void builder_requires_model() {
        assertThatThrownBy(() ->
            IdRagExperiment.builder()
                .url("http://localhost:11434/v1")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("model");
    }

    @Test
    void builder_requires_baselineModel() {
        assertThatThrownBy(() ->
            IdRagExperiment.builder()
                .url("http://localhost:11434/v1")
                .model("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baselineModel");
    }

    @Test
    void builder_constructs_with_required_fields() {
        var exp = IdRagExperiment.builder()
            .url("http://localhost:11434/v1")
            .model("qwen2.5:7b")
            .baselineModel("qwen2.5:7b")
            .build();

        assertThat(exp).isNotNull();
    }

    @Test
    void builder_with_all_fields() {
        var exp = IdRagExperiment.builder()
            .url("http://localhost:11434/v1")
            .model("qwen2.5:7b")
            .baselineModel("qwen2.5:7b")
            .traitGraph(TraitGraph.wyrdPersonality())
            .scenarios(Scenario.standardSuite())
            .embeddingUrl("http://localhost:11434")
            .embeddingModel("all-minilm")
            .build();

        assertThat(exp).isNotNull();
    }

    // =======================================================================
    // Result format tests
    // =======================================================================

    @Test
    void result_summary_format() {
        var graph = TraitGraph.wyrdPersonality();
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test", null, Instant.now(), List.of());

        var flatReport = new BehavioralMetrics.ComparisonReport(
            0.30, 0.70, 1.0, 0.30, Map.of("social", 0.25, "decision", 0.35),
            Map.of(), 0.35, 0.0, List.of());
        var fullReport = new BehavioralMetrics.ComparisonReport(
            0.28, 0.72, 1.0, 0.28, Map.of("social", 0.22, "decision", 0.34),
            Map.of(), 0.36, 0.0, List.of());
        var selectiveReport = new BehavioralMetrics.ComparisonReport(
            0.22, 0.78, 1.0, 0.22, Map.of("social", 0.18, "decision", 0.26),
            Map.of(), 0.40, 0.0, List.of());

        var conditions = List.of(
            new IdRagExperiment.ConditionResult("Flat prompt", baseline, flatReport, 115, 115),
            new IdRagExperiment.ConditionResult("ID-RAG full", baseline, fullReport, 80, 80),
            new IdRagExperiment.ConditionResult("ID-RAG selective", baseline, selectiveReport, 45, 45)
        );

        var result = new IdRagExperiment.IdRagResult(
            "qwen2.5:7b", "qwen2.5:7b", graph, baseline,
            "test soul", conditions,
            Map.of("social", 0.18, "decision", 0.26),
            45, 30, 65);

        var summary = result.summary();
        assertThat(summary)
            .contains("Experiment 12")
            .contains("Flat prompt")
            .contains("ID-RAG full")
            .contains("ID-RAG selective")
            .contains("ID-RAG WINS")
            .contains("GREEN");
    }

    @Test
    void result_detects_flat_wins() {
        var graph = TraitGraph.wyrdPersonality();
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test", null, Instant.now(), List.of());

        var flatReport = new BehavioralMetrics.ComparisonReport(
            0.30, 0.70, 1.0, 0.30, Map.of(), Map.of(), 0.35, 0.0, List.of());
        var selectiveReport = new BehavioralMetrics.ComparisonReport(
            0.45, 0.55, 1.0, 0.45, Map.of(), Map.of(), 0.25, 0.0, List.of());

        var conditions = List.of(
            new IdRagExperiment.ConditionResult("Flat prompt", baseline, flatReport, 115, 115),
            new IdRagExperiment.ConditionResult("ID-RAG selective", baseline, selectiveReport, 45, 45)
        );

        var result = new IdRagExperiment.IdRagResult(
            "qwen2.5:7b", "qwen2.5:7b", graph, baseline,
            "test", conditions, Map.of(), 45, 30, 65);

        assertThat(result.summary()).contains("FLAT WINS");
    }

    @Test
    void result_detects_tied_with_token_savings() {
        var graph = TraitGraph.wyrdPersonality();
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test", null, Instant.now(), List.of());

        var flatReport = new BehavioralMetrics.ComparisonReport(
            0.30, 0.70, 1.0, 0.30, Map.of(), Map.of(), 0.35, 0.0, List.of());
        var selectiveReport = new BehavioralMetrics.ComparisonReport(
            0.28, 0.72, 1.0, 0.28, Map.of(), Map.of(), 0.36, 0.0, List.of());

        var conditions = List.of(
            new IdRagExperiment.ConditionResult("Flat prompt", baseline, flatReport, 115, 115),
            new IdRagExperiment.ConditionResult("ID-RAG selective", baseline, selectiveReport, 40, 40)
        );

        var result = new IdRagExperiment.IdRagResult(
            "qwen2.5:7b", "qwen2.5:7b", graph, baseline,
            "test", conditions, Map.of(), 40, 25, 55);

        assertThat(result.summary()).contains("TIED").contains("token savings");
    }

    // =======================================================================
    // Token budget analysis
    // =======================================================================

    @Test
    void selective_retrieval_varies_by_scenario() {
        var graph = TraitGraph.wyrdPersonality();
        var scenarios = Scenario.standardSuite();

        var tokenCounts = scenarios.stream()
            .map(s -> SoulExperiment.estimateTokens(
                IdentityRetriever.retrieveAsPrompt(graph, s)))
            .toList();

        // Should have variation — not all scenarios get the same tokens
        var min = tokenCounts.stream().mapToInt(i -> i).min().orElse(0);
        var max = tokenCounts.stream().mapToInt(i -> i).max().orElse(0);
        assertThat(max - min).isGreaterThan(0);

        // All should be less than full prompt
        var fullTokens = SoulExperiment.estimateTokens(graph.toPromptText());
        for (var count : tokenCounts) {
            assertThat(count).isLessThanOrEqualTo(fullTokens);
        }
    }

    @Test
    void all_scenarios_get_at_least_some_traits() {
        var graph = TraitGraph.wyrdPersonality();
        for (var scenario : Scenario.standardSuite()) {
            var traits = IdentityRetriever.retrieve(graph, scenario);
            assertThat(traits)
                .as("Scenario %s should get traits", scenario.id())
                .isNotEmpty();
        }
    }

    // =======================================================================
    // Live test
    // =======================================================================

    @Test
    void live_idrag_comparison(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var model = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");

        if (url == null) {
            System.out.println("SKIP: Need SOUL_EXPERIMENT_URL");
            return;
        }

        var builder = IdRagExperiment.builder()
            .url(url)
            .model(model)
            .baselineModel(model)
            .traitGraph(TraitGraph.wyrdPersonality())
            .outputDir(outputDir);

        if (embeddingUrl != null) {
            builder.embeddingUrl(embeddingUrl).embeddingModel(embeddingModel);
        }

        var result = builder.build().run();

        assertThat(result.conditions()).hasSize(3);
        for (var c : result.conditions()) {
            assertThat(c.report().overallDivergence()).isBetween(0.0, 1.0);
        }

        // Selective should use fewer tokens than flat
        var flat = result.conditions().stream()
            .filter(c -> "Flat prompt".equals(c.condition())).findFirst();
        var selective = result.conditions().stream()
            .filter(c -> "ID-RAG selective".equals(c.condition())).findFirst();
        if (flat.isPresent() && selective.isPresent()) {
            assertThat(selective.get().avgTokensPerTurn())
                .isLessThanOrEqualTo(flat.get().avgTokensPerTurn());
        }
    }
}
