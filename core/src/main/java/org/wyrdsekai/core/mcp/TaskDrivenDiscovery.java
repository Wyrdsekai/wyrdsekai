package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * On-demand MCP service discovery (§91.3 Channel 3: Task-Driven).
 * When an agent needs a capability it doesn't have, this searches
 * registered and discovered sources for matching services.
 *
 * Discovery flow:
 *   1. Agent encounters a task it cannot handle
 *   2. TaskDrivenDiscovery.discover() searches syncer's discovered capabilities
 *   3. suggest() filters against already-installed services in the registry
 *   4. Agent or steward decides whether to install
 *
 * Matching is keyword-based against capability name, description, and ID.
 * Scores are based on keyword overlap normalized by query length.
 */
public class TaskDrivenDiscovery {

    private static final Logger log = LoggerFactory.getLogger(TaskDrivenDiscovery.class);

    /**
     * A suggestion for a service that could fulfill a task.
     *
     * @param serviceId  Capability or service ID
     * @param name       Human-readable name
     * @param reason     Why this was suggested
     * @param confidence Score 0.0-1.0 based on keyword match quality
     */
    public record DiscoverySuggestion(
        String serviceId,
        String name,
        String reason,
        double confidence
    ) {}

    private final McpRegistrySyncer syncer;

    public TaskDrivenDiscovery(McpRegistrySyncer syncer) {
        this.syncer = Objects.requireNonNull(syncer, "syncer must not be null");
    }

    /**
     * Discover capabilities matching a task description.
     * Searches all discovered capabilities in the syncer.
     *
     * @param taskDescription Natural language description of what the agent needs
     * @return Ranked list of suggestions, best match first
     */
    public List<DiscoverySuggestion> discover(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return List.of();
        }

        var keywords = extractKeywords(taskDescription);
        if (keywords.isEmpty()) {
            return List.of();
        }

        var discovered = syncer.getDiscovered();
        var suggestions = new ArrayList<DiscoverySuggestion>();

        for (var cap : discovered) {
            double score = scoreMatch(keywords, cap.name(), cap.description(), cap.id());
            if (score > 0.0) {
                String reason = buildReason(keywords, cap.name(), cap.description());
                suggestions.add(new DiscoverySuggestion(
                    cap.id(), cap.name(), reason, score));
            }
        }

        suggestions.sort(Comparator.comparingDouble(DiscoverySuggestion::confidence).reversed());
        log.debug("Task discovery for '{}': {} matches from {} discovered",
            taskDescription, suggestions.size(), discovered.size());
        return List.copyOf(suggestions);
    }

    /**
     * Suggest services for a task, filtering out already-installed ones.
     *
     * @param taskDescription Natural language description of what the agent needs
     * @param registry        Service registry to check installed services
     * @return Filtered ranked suggestions (only services not already installed)
     */
    public List<DiscoverySuggestion> suggest(String taskDescription, McpServiceRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");

        var all = discover(taskDescription);
        var installedIds = registry.serviceIds();

        var filtered = all.stream()
            .filter(s -> !installedIds.contains(s.serviceId()))
            .toList();

        log.debug("Task suggestion for '{}': {} suggestions ({} filtered as already installed)",
            taskDescription, filtered.size(), all.size() - filtered.size());
        return filtered;
    }

    /**
     * Extract meaningful keywords from a task description.
     * Strips common stop words and normalizes to lowercase.
     */
    static List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return List.of();

        var stopWords = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been",
            "i", "we", "you", "they", "it", "he", "she",
            "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "and", "or", "but", "not", "no", "so", "if", "do", "does",
            "that", "this", "what", "how", "can", "need", "want", "would",
            "should", "could", "will", "my", "your", "our", "their",
            "some", "any", "all", "each", "very", "just", "about"
        );

        return Arrays.stream(text.toLowerCase().split("[\\s,;.!?()\\[\\]{}\"']+"))
            .filter(w -> !w.isBlank())
            .filter(w -> w.length() > 1)
            .filter(w -> !stopWords.contains(w))
            .distinct()
            .toList();
    }

    /**
     * Score how well a capability matches the query keywords.
     * Returns 0.0 if no match, up to 1.0 for perfect match.
     */
    static double scoreMatch(List<String> keywords, String name, String description, String id) {
        if (keywords.isEmpty()) return 0.0;

        String corpus = (name + " " + description + " " + id).toLowerCase();
        int matched = 0;
        for (var kw : keywords) {
            if (corpus.contains(kw)) {
                matched++;
            }
        }

        if (matched == 0) return 0.0;
        return (double) matched / keywords.size();
    }

    /**
     * Build a human-readable reason for why a capability was suggested.
     */
    static String buildReason(List<String> keywords, String name, String description) {
        var matchedKeywords = keywords.stream()
            .filter(kw -> name.toLowerCase().contains(kw)
                || description.toLowerCase().contains(kw))
            .collect(Collectors.toList());

        if (matchedKeywords.isEmpty()) {
            return "Partial match based on service ID";
        }
        return "Matches keywords: " + String.join(", ", matchedKeywords);
    }
}
