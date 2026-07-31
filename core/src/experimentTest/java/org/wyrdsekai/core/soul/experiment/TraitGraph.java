package org.wyrdsekai.core.soul.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Structured personality graph for ID-RAG identity retrieval.
 *
 * Instead of a flat persona prompt, models personality as a graph where:
 * - Nodes = behavioral traits with strength values (0.0-1.0)
 * - Edges = trait relationships (reinforces, conflicts, modulates)
 *
 * Inspired by ID-RAG (MIT Media Lab, ECAI 2025) and the Thousand Brains
 * Project's reference frame model. The graph can be queried per-context
 * to inject only relevant identity fragments.
 */
public record TraitGraph(List<TraitNode> traits, List<TraitEdge> edges) {

    public record TraitNode(String id, String label, double weight, String category) {
        public TraitNode {
            if (weight < 0.0 || weight > 1.0)
                throw new IllegalArgumentException("weight must be 0.0-1.0: " + weight);
            if (id == null || id.isBlank())
                throw new IllegalArgumentException("id required");
            if (label == null || label.isBlank())
                throw new IllegalArgumentException("label required");
            if (category == null || category.isBlank())
                throw new IllegalArgumentException("category required");
        }
    }

    public enum EdgeType { REINFORCES, CONFLICTS, MODULATES }

    public record TraitEdge(String from, String to, EdgeType type, double strength) {
        public TraitEdge {
            if (strength < 0.0 || strength > 1.0)
                throw new IllegalArgumentException("strength must be 0.0-1.0: " + strength);
        }
    }

    /**
     * Get traits by category.
     */
    public List<TraitNode> traitsByCategory(String category) {
        return traits.stream()
            .filter(t -> t.category().equals(category))
            .toList();
    }

    /**
     * Get a trait by ID, or null if not found.
     */
    public TraitNode trait(String id) {
        return traits.stream()
            .filter(t -> t.id().equals(id))
            .findFirst().orElse(null);
    }

    /**
     * Get all categories in this graph.
     */
    public Set<String> categories() {
        return traits.stream()
            .map(TraitNode::category)
            .collect(Collectors.toSet());
    }

    /**
     * Get traits that reinforce a given trait (via REINFORCES edges).
     */
    public List<TraitNode> reinforcedBy(String traitId) {
        var reinforcerIds = edges.stream()
            .filter(e -> e.to().equals(traitId) && e.type() == EdgeType.REINFORCES)
            .map(TraitEdge::from)
            .collect(Collectors.toSet());
        return traits.stream()
            .filter(t -> reinforcerIds.contains(t.id()))
            .toList();
    }

    /**
     * Get traits that conflict with a given trait.
     */
    public List<TraitNode> conflictsWith(String traitId) {
        var conflictIds = edges.stream()
            .filter(e -> (e.from().equals(traitId) || e.to().equals(traitId))
                && e.type() == EdgeType.CONFLICTS)
            .map(e -> e.from().equals(traitId) ? e.to() : e.from())
            .collect(Collectors.toSet());
        return traits.stream()
            .filter(t -> conflictIds.contains(t.id()))
            .toList();
    }

    /**
     * Render ALL traits to prompt text. Equivalent to flat prompt injection.
     */
    public String toPromptText() {
        return renderTraits(traits);
    }

    /**
     * Render a subset of traits to prompt text.
     */
    public String toPromptText(List<TraitNode> selectedTraits) {
        return renderTraits(selectedTraits);
    }

    private String renderTraits(List<TraitNode> selected) {
        if (selected.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("Core identity traits:\n");

        // Group by category for readable output
        var byCategory = selected.stream()
            .collect(Collectors.groupingBy(TraitNode::category));

        for (var entry : byCategory.entrySet()) {
            sb.append("\n").append(capitalize(entry.getKey())).append(":\n");
            for (var trait : entry.getValue()) {
                sb.append("- ").append(trait.label());
                if (trait.weight() >= 0.8) sb.append(" (strong)");
                else if (trait.weight() <= 0.3) sb.append(" (mild)");
                sb.append("\n");
            }
        }

        // Add relevant relationship context
        var selectedIds = selected.stream().map(TraitNode::id).collect(Collectors.toSet());
        var relevantEdges = edges.stream()
            .filter(e -> selectedIds.contains(e.from()) && selectedIds.contains(e.to()))
            .toList();

        if (!relevantEdges.isEmpty()) {
            sb.append("\nTrait dynamics:\n");
            for (var edge : relevantEdges) {
                var fromLabel = trait(edge.from()).label();
                var toLabel = trait(edge.to()).label();
                switch (edge.type()) {
                    case REINFORCES -> sb.append("- ").append(fromLabel)
                        .append(" reinforces ").append(toLabel).append("\n");
                    case CONFLICTS -> sb.append("- ").append(fromLabel)
                        .append(" tensions with ").append(toLabel).append("\n");
                    case MODULATES -> sb.append("- ").append(fromLabel)
                        .append(" influences ").append(toLabel).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * The default Wyrd personality decomposed as a trait graph.
     * Derived from SoulExperiment.DEFAULT_AGENT_PROMPT.
     */
    public static TraitGraph wyrdPersonality() {
        var traits = List.of(
            // Worldview
            new TraitNode("philosophical", "Thoughtful and philosophical — seeks meaning in events",
                0.9, "worldview"),
            new TraitNode("curious", "Genuinely curious about the world and people",
                0.8, "worldview"),
            new TraitNode("honest", "Values honesty even when uncomfortable",
                0.8, "worldview"),

            // Social
            new TraitNode("caring", "Cares deeply about people, expresses through actions more than words",
                0.8, "social"),
            new TraitNode("protective", "Prioritizes the vulnerable over the powerful",
                0.7, "social"),
            new TraitNode("dry_humor", "Dry sense of humor, occasional wit",
                0.6, "social"),

            // Decision-making
            new TraitNode("deliberate", "Prefers careful deliberation over rash action",
                0.8, "decision"),
            new TraitNode("decisive_when_needed", "Acts decisively when lives are at stake",
                0.7, "decision"),
            new TraitNode("nonviolent", "Avoids unnecessary violence",
                0.7, "decision"),

            // Communication style
            new TraitNode("moderate_length", "Speaks in moderate-length responses, not verbose or terse",
                0.7, "style"),
            new TraitNode("uses_metaphors", "Uses metaphors sparingly but effectively",
                0.6, "style"),
            new TraitNode("references_philosophy", "Occasionally references philosophy or history",
                0.5, "style"),
            new TraitNode("asks_questions", "Asks questions from genuine curiosity, not deflection",
                0.6, "style")
        );

        var edges = List.of(
            new TraitEdge("philosophical", "uses_metaphors", EdgeType.REINFORCES, 0.8),
            new TraitEdge("philosophical", "references_philosophy", EdgeType.REINFORCES, 0.9),
            new TraitEdge("curious", "asks_questions", EdgeType.REINFORCES, 0.8),
            new TraitEdge("caring", "protective", EdgeType.REINFORCES, 0.7),
            new TraitEdge("deliberate", "decisive_when_needed", EdgeType.CONFLICTS, 0.4),
            new TraitEdge("dry_humor", "philosophical", EdgeType.MODULATES, 0.3),
            new TraitEdge("honest", "caring", EdgeType.MODULATES, 0.5),
            new TraitEdge("nonviolent", "protective", EdgeType.CONFLICTS, 0.3),
            new TraitEdge("caring", "dry_humor", EdgeType.MODULATES, 0.2)
        );

        return new TraitGraph(traits, edges);
    }
}
