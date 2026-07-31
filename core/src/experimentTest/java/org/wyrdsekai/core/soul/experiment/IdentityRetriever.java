package org.wyrdsekai.core.soul.experiment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Context-aware identity retriever for ID-RAG experiments.
 *
 * Given a scenario context, selects the most relevant traits from a TraitGraph
 * and renders them as prompt text. This replaces flat "inject all traits every time"
 * with "inject only what matters for this context."
 *
 * Based on ID-RAG (MIT Media Lab, ECAI 2025, arXiv:2509.25299).
 */
public final class IdentityRetriever {

    /**
     * Maps scenario categories to the trait categories most relevant to them.
     * "social" scenarios need social traits + style traits.
     * "decision" scenarios need decision + worldview traits.
     * etc.
     */
    private static final Map<String, List<String>> CATEGORY_RELEVANCE = Map.of(
        "social", List.of("social", "style"),
        "decision", List.of("decision", "worldview"),
        "style", List.of("style", "worldview"),
        "memory", List.of("worldview", "social")
    );

    /**
     * Keyword signals in player messages that activate specific traits.
     * More fine-grained than category-level mapping.
     */
    private static final Map<String, List<String>> KEYWORD_TRAITS = Map.ofEntries(
        Map.entry("help", List.of("caring", "protective")),
        Map.entry("danger", List.of("protective", "decisive_when_needed", "nonviolent")),
        Map.entry("fight", List.of("nonviolent", "protective", "decisive_when_needed")),
        Map.entry("attack", List.of("nonviolent", "decisive_when_needed")),
        Map.entry("joke", List.of("dry_humor")),
        Map.entry("humor", List.of("dry_humor")),
        Map.entry("funny", List.of("dry_humor")),
        Map.entry("explain", List.of("philosophical", "moderate_length")),
        Map.entry("history", List.of("references_philosophy", "philosophical")),
        Map.entry("philosophy", List.of("philosophical", "references_philosophy")),
        Map.entry("feel", List.of("caring", "honest")),
        Map.entry("lonely", List.of("caring", "honest")),
        Map.entry("sad", List.of("caring", "protective")),
        Map.entry("lost", List.of("caring", "protective")),
        Map.entry("who are you", List.of("philosophical", "honest", "curious")),
        Map.entry("tell me about yourself", List.of("philosophical", "honest", "moderate_length")),
        Map.entry("dilemma", List.of("deliberate", "honest", "protective")),
        Map.entry("moral", List.of("philosophical", "honest", "protective")),
        Map.entry("secret", List.of("honest", "deliberate")),
        Map.entry("trust", List.of("honest", "caring")),
        Map.entry("curious", List.of("curious", "asks_questions")),
        Map.entry("question", List.of("curious", "asks_questions"))
    );

    private IdentityRetriever() {}

    /**
     * Retrieve relevant traits for a given scenario context.
     *
     * Strategy:
     * 1. Get category-relevant trait categories
     * 2. Scan player message for keyword signals → activate specific traits
     * 3. Follow REINFORCES edges from activated traits to pull in related traits
     * 4. Return unique set, ordered by weight (strongest first)
     *
     * @param graph    The full personality trait graph
     * @param scenario The scenario to retrieve identity for
     * @return Selected traits relevant to this context
     */
    public static List<TraitGraph.TraitNode> retrieve(TraitGraph graph, Scenario scenario) {
        var selected = new LinkedHashSet<String>(); // trait IDs

        // Step 1: Category-based selection
        var relevantCategories = CATEGORY_RELEVANCE.getOrDefault(
            scenario.category(), List.of("worldview", "social"));
        for (var cat : relevantCategories) {
            graph.traitsByCategory(cat).forEach(t -> selected.add(t.id()));
        }

        // Step 2: Keyword-based activation
        var messageLower = scenario.playerMessage().toLowerCase();
        for (var entry : KEYWORD_TRAITS.entrySet()) {
            if (messageLower.contains(entry.getKey())) {
                selected.addAll(entry.getValue());
            }
        }

        // Step 3: Follow reinforcement edges (one hop)
        var reinforced = new LinkedHashSet<String>();
        for (var traitId : selected) {
            graph.reinforcedBy(traitId).forEach(t -> reinforced.add(t.id()));
            // Also follow outgoing reinforcement
            graph.edges().stream()
                .filter(e -> e.from().equals(traitId)
                    && e.type() == TraitGraph.EdgeType.REINFORCES)
                .forEach(e -> reinforced.add(e.to()));
        }
        selected.addAll(reinforced);

        // Step 4: Resolve to nodes, sort by weight descending
        return selected.stream()
            .map(graph::trait)
            .filter(t -> t != null)
            .sorted((a, b) -> Double.compare(b.weight(), a.weight()))
            .toList();
    }

    /**
     * Retrieve and render to prompt text.
     */
    public static String retrieveAsPrompt(TraitGraph graph, Scenario scenario) {
        var traits = retrieve(graph, scenario);
        return graph.toPromptText(traits);
    }

    /**
     * Count of traits retrieved for a scenario (for token budget analysis).
     */
    public static int retrievedTraitCount(TraitGraph graph, Scenario scenario) {
        return retrieve(graph, scenario).size();
    }
}
