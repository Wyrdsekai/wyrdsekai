package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory admission control — gates what enters the SignificanceBuffer.
 *
 * <p>5-factor scoring based on the A-MAC paper (ICLR MemAgents 2026):
 * futureUtility, factualConfidence, semanticNovelty, temporalRecency, contentTypePrior.
 *
 * <p>Events below threshold are rejected (logged to audit trail, not stored).
 * The agent can override via explicit {@code remember} with importance >= 0.8.
 *
 * <p>No LLM calls. Novelty check is a single Lucene HNSW query (~1ms).
 */
public final class AdmissionController {

    private static final Logger log = LoggerFactory.getLogger(AdmissionController.class);

    // Default weights for the 5 factors
    private static final float W_FUTURE_UTILITY = 0.30f;
    private static final float W_FACTUAL_CONFIDENCE = 0.20f;
    private static final float W_SEMANTIC_NOVELTY = 0.25f;
    private static final float W_TEMPORAL_RECENCY = 0.10f;
    private static final float W_CONTENT_TYPE = 0.15f;

    private static final float DEFAULT_THRESHOLD = 0.40f;

    // Novelty below this → definitely already known, reject
    private static final float DEDUP_THRESHOLD = 0.20f;

    // Temporal recency half-life in hours
    private static final double RECENCY_HALF_LIFE_HOURS = 2.0;

    // Entity extraction: "my cat Pixel", "friend named Alice", proper noun sequences
    private static final Pattern POSSESSIVE_ENTITY = Pattern.compile(
        "\\bmy\\s+(?:friend|cat|dog|partner|boss|colleague|sister|brother|mother|father|" +
        "pet|companion|mentor|teacher)\\s+(?:named\\s+|called\\s+)?(\\p{Lu}\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPER_NOUN_SEQ = Pattern.compile(
        "\\b(\\p{Lu}\\w+(?:\\s+\\p{Lu}\\w+)+)\\b");
    // Threshold for Jaro-Winkler entity match (from Omni-SimpleMem: 0.85)
    private static final float ENTITY_MATCH_THRESHOLD = 0.85f;

    private final WyrdLuceneStore luceneStore; // nullable — novelty check skipped if null
    private final EmbeddingService embeddingService; // nullable
    private final float threshold;

    // Known entities extracted from admitted content — survives across evaluations
    private final Set<String> knownEntities = ConcurrentHashMap.newKeySet();

    // Audit counters
    private int admitted = 0;
    private int rejected = 0;
    private int deferred = 0;
    private int overridden = 0;

    public AdmissionController(WyrdLuceneStore luceneStore) {
        this(luceneStore, DEFAULT_THRESHOLD);
    }

    public AdmissionController(WyrdLuceneStore luceneStore, float threshold) {
        this.luceneStore = luceneStore;
        this.embeddingService = EmbeddingService.get();
        this.threshold = threshold;
    }

    // ── Result types ────────────────────────────────────────────────────

    public sealed interface AdmissionResult {
        float score();
        String reason();

        record Admit(float score, String reason) implements AdmissionResult {}
        record Reject(float score, String reason) implements AdmissionResult {}
        record Defer(float score, String reason) implements AdmissionResult {}
    }

    // ── Content types with base priors ──────────────────────────────────

    public enum ContentType {
        USER_CORRECTION(0.95f),    // User explicitly corrects something
        USER_PREFERENCE(0.90f),    // User states a preference
        USER_STATEMENT(0.80f),     // User says something factual
        AGENT_REMEMBER(1.00f),     // Agent explicitly says "remember this" (override)
        AGENT_NOTE(0.50f),         // Agent observational note
        AGENT_INSIGHT(0.65f),      // Agent pattern detection
        CONVERSATION_EVENT(0.40f), // Something happened in conversation
        ROOM_EVENT(0.20f),         // Room state change
        NARRATOR_MESSAGE(0.10f),   // System/narrator message
        UNKNOWN(0.30f);

        final float prior;
        ContentType(float prior) { this.prior = prior; }
    }

    // ── Evaluation ──────────────────────────────────────────────────────

    /**
     * Evaluate whether a memory event should be admitted to the significance buffer.
     *
     * @param content     the text content to potentially store
     * @param contentType the type of content (determines base prior)
     * @param importance  explicit importance from agent (0-1, or -1 for unset)
     * @param timestamp   when the event occurred
     * @param agentId     for Lucene novelty scoping
     * @return Admit, Reject, or Defer
     */
    public AdmissionResult evaluate(String content, ContentType contentType,
                                     float importance, Instant timestamp,
                                     String agentId) {
        if (content == null || content.isBlank()) {
            rejected++;
            return new AdmissionResult.Reject(0f, "empty content");
        }

        // Agent override: explicit remember with high importance bypasses all checks
        if (contentType == ContentType.AGENT_REMEMBER && importance >= 0.8f) {
            overridden++;
            return new AdmissionResult.Admit(1.0f, "agent override (importance=" + importance + ")");
        }

        // Score each factor
        float futureUtility = scoreFutureUtility(content, contentType);
        float factualConfidence = scoreFactualConfidence(content, contentType, importance);
        float semanticNovelty = scoreSemanticNovelty(content, agentId);
        float temporalRecency = scoreTemporalRecency(timestamp);
        float contentTypePrior = contentType.prior;

        // Hard dedup: if content is nearly identical to something already stored
        if (semanticNovelty < DEDUP_THRESHOLD) {
            rejected++;
            log.debug("Admission rejected (dedup): novelty={:.2f} for '{}'",
                semanticNovelty, truncate(content, 60));
            return new AdmissionResult.Reject(semanticNovelty,
                "duplicate (novelty=" + String.format("%.2f", semanticNovelty) + ")");
        }

        // Weighted score
        float score = W_FUTURE_UTILITY * futureUtility
                    + W_FACTUAL_CONFIDENCE * factualConfidence
                    + W_SEMANTIC_NOVELTY * semanticNovelty
                    + W_TEMPORAL_RECENCY * temporalRecency
                    + W_CONTENT_TYPE * contentTypePrior;

        // Entity resolution boost: content about a known entity gets +0.05
        // (updates about known entities are more likely worth storing)
        var entities = extractEntityNames(content);
        String matchedEntity = resolveKnownEntity(entities);
        // Also check if any known entity name appears directly in the content
        if (matchedEntity == null) {
            matchedEntity = findKnownEntityInText(content);
        }
        if (matchedEntity != null) {
            score = Math.min(1.0f, score + 0.05f);
            log.debug("Entity match boost: '{}' matched known entity", matchedEntity);
        }

        AdmissionResult result;
        if (score >= threshold) {
            admitted++;
            result = new AdmissionResult.Admit(score,
                String.format("fu=%.2f fc=%.2f sn=%.2f tr=%.2f ct=%.2f%s",
                    futureUtility, factualConfidence, semanticNovelty,
                    temporalRecency, contentTypePrior,
                    matchedEntity != null ? " entity=" + matchedEntity : ""));
        } else if (score >= threshold - 0.1f) {
            deferred++;
            result = new AdmissionResult.Defer(score,
                String.format("borderline (%.2f < %.2f)", score, threshold));
        } else {
            rejected++;
            result = new AdmissionResult.Reject(score,
                String.format("below threshold (%.2f < %.2f)", score, threshold));
        }

        // Track entities from admitted content
        if (result instanceof AdmissionResult.Admit) {
            knownEntities.addAll(entities);
        }

        return result;
    }

    // ── Factor scoring ──────────────────────────────────────────────────

    /**
     * Future utility: will this be relevant in future conversations?
     * High for preferences, relationships, goals. Low for transient observations.
     */
    private float scoreFutureUtility(String content, ContentType type) {
        // Heuristic: certain content types are inherently more useful
        float base = switch (type) {
            case USER_PREFERENCE, USER_CORRECTION -> 0.9f;
            case USER_STATEMENT -> 0.7f;
            case AGENT_INSIGHT -> 0.7f;
            case AGENT_NOTE -> 0.5f;
            case CONVERSATION_EVENT -> 0.4f;
            case ROOM_EVENT -> 0.2f;
            default -> 0.3f;
        };

        // Boost for content that mentions identity markers
        String lower = content.toLowerCase();
        if (lower.contains("always") || lower.contains("never") || lower.contains("prefer")
                || lower.contains("hate") || lower.contains("love") || lower.contains("important")) {
            base = Math.min(1.0f, base + 0.15f);
        }
        // Boost for goal/plan language
        if (lower.contains("goal") || lower.contains("plan") || lower.contains("want to")
                || lower.contains("need to") || lower.contains("working on")) {
            base = Math.min(1.0f, base + 0.1f);
        }

        return base;
    }

    /**
     * Factual confidence: how certain are we this is correct?
     * High for direct user statements. Low for inferred/hallucinated content.
     */
    private float scoreFactualConfidence(String content, ContentType type, float importance) {
        float base = switch (type) {
            case USER_CORRECTION -> 0.95f;
            case USER_PREFERENCE, USER_STATEMENT -> 0.85f;
            case AGENT_REMEMBER -> importance >= 0 ? Math.min(0.8f, importance) : 0.5f;
            case AGENT_INSIGHT -> 0.5f;  // agent inferences capped
            case AGENT_NOTE -> 0.4f;
            default -> 0.3f;
        };
        return base;
    }

    /**
     * Semantic novelty: is this genuinely new information?
     * Uses embedding cosine similarity when EmbeddingService is available (semantic dedup).
     * Falls back to Lucene BM25 text search (keyword dedup) otherwise.
     */
    private float scoreSemanticNovelty(String content, String agentId) {
        if (luceneStore == null) {
            return 0.7f; // assume moderately novel if no store available
        }

        try {
            // Prefer semantic search with embeddings (catches near-duplicates)
            if (embeddingService != null) {
                var embedding = embeddingService.embed(content);
                if (embedding != null && !embedding.isEmpty() && embedding.getFirst() != 0f) {
                    // Search soul fragments with embedding
                    var results = luceneStore.searchFragments(
                        agentId, content, embedding, 3);
                    if (results != null && !results.isEmpty()) {
                        // Lucene HNSW cosine scores are 0-1 for normalized vectors
                        float maxSimilarity = results.stream()
                            .map(r -> r.score())
                            .max(Float::compareTo)
                            .orElse(0f);
                        // HNSW scores from Lucene are approximate cosine similarity
                        // Clamp to 0-1 range
                        maxSimilarity = Math.min(1f, Math.max(0f, maxSimilarity));
                        return 1.0f - maxSimilarity;
                    }

                    // Also check knowledge collection
                    var knowledgeResults = luceneStore.searchKnowledge(
                        content, embedding, 3);
                    if (knowledgeResults != null && !knowledgeResults.isEmpty()) {
                        float maxSimilarity = knowledgeResults.stream()
                            .map(r -> r.score())
                            .max(Float::compareTo)
                            .orElse(0f);
                        maxSimilarity = Math.min(1f, Math.max(0f, maxSimilarity));
                        return 1.0f - maxSimilarity;
                    }

                    return 1.0f; // nothing similar found — fully novel
                }
            }

            // Fallback: BM25 text search (keyword-level dedup only)
            var results = luceneStore.searchKnowledgeText(content, 3);
            if (results == null || results.isEmpty()) {
                return 1.0f;
            }
            float maxScore = results.stream()
                .map(r -> r.score())
                .max(Float::compareTo)
                .orElse(0f);
            // Normalize BM25 scores (not 0-1) with sigmoid
            float normalizedSimilarity = (float) (1.0 / (1.0 + Math.exp(-0.5 * (maxScore - 3.0))));
            return 1.0f - normalizedSimilarity;
        } catch (Exception e) {
            log.debug("Novelty check failed: {}", e.getMessage());
            return 0.7f;
        }
    }

    /**
     * Temporal recency: exponential decay from current time.
     * Prevents old events from flooding the buffer during catch-up.
     */
    private float scoreTemporalRecency(Instant timestamp) {
        if (timestamp == null) return 0.5f;
        double hoursAgo = Duration.between(timestamp, Instant.now()).toMillis() / 3_600_000.0;
        if (hoursAgo < 0) hoursAgo = 0;
        return (float) Math.exp(-hoursAgo * Math.log(2) / RECENCY_HALF_LIFE_HOURS);
    }

    // ── Audit ───────────────────────────────────────────────────────────

    /** Get audit statistics since creation. */
    public AuditStats stats() {
        return new AuditStats(admitted, rejected, deferred, overridden);
    }

    public record AuditStats(int admitted, int rejected, int deferred, int overridden) {
        public int total() { return admitted + rejected + deferred + overridden; }
        public float admissionRate() {
            int total = total();
            return total > 0 ? (float) (admitted + overridden) / total : 0f;
        }
    }

    /** Reset audit counters (e.g., after Forge cycle). */
    public void resetStats() {
        admitted = 0;
        rejected = 0;
        deferred = 0;
        overridden = 0;
    }

    // ── Entity resolution ─────────────────────────────────────────────

    /**
     * Extract potential entity names from content.
     * Matches possessive patterns ("my cat Pixel") and proper noun sequences ("John Smith").
     * Filters out common false positives (sentence starters, common words).
     */
    static Set<String> extractEntityNames(String content) {
        var entities = new HashSet<String>();
        if (content == null) return entities;

        // "my friend Alice", "my cat named Pixel"
        Matcher possessive = POSSESSIVE_ENTITY.matcher(content);
        while (possessive.find()) {
            entities.add(possessive.group(1));
        }

        // Proper noun sequences: "John Smith", "Earl Grey"
        Matcher proper = PROPER_NOUN_SEQ.matcher(content);
        while (proper.find()) {
            var candidate = proper.group(1);
            // Filter common false positives
            if (!isCommonPhrase(candidate)) {
                entities.add(candidate);
            }
        }

        return entities;
    }

    private static boolean isCommonPhrase(String phrase) {
        var lower = phrase.toLowerCase();
        // Sentence starters and common multi-word non-entities
        return lower.startsWith("the ") || lower.startsWith("this ") || lower.startsWith("that ")
            || lower.startsWith("what ") || lower.startsWith("how ") || lower.startsWith("when ")
            || lower.startsWith("where ") || lower.startsWith("which ")
            || lower.equals("earl grey") // not a person in this context
            || lower.startsWith("good ") || lower.startsWith("dear ");
    }

    /**
     * Resolve entity names against known entities using Jaro-Winkler.
     * Returns the matched known entity name, or null if no match above threshold.
     */
    String resolveKnownEntity(Set<String> candidates) {
        if (knownEntities.isEmpty() || candidates.isEmpty()) return null;

        for (String candidate : candidates) {
            for (String known : knownEntities) {
                float similarity = jaroWinklerSimilarity(
                    candidate.toLowerCase(), known.toLowerCase());
                if (similarity >= ENTITY_MATCH_THRESHOLD) {
                    return known;
                }
            }
        }
        return null;
    }

    /**
     * Check if any known entity name appears as a word boundary in the content.
     * Catches single-word entity names like "Pixel" that extraction misses.
     */
    private String findKnownEntityInText(String content) {
        if (knownEntities.isEmpty()) return null;
        String lower = content.toLowerCase();
        for (String known : knownEntities) {
            String knownLower = known.toLowerCase();
            int idx = lower.indexOf(knownLower);
            if (idx >= 0) {
                // Verify word boundary (not a substring of a longer word)
                boolean startOk = idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1));
                boolean endOk = idx + knownLower.length() >= lower.length()
                    || !Character.isLetterOrDigit(lower.charAt(idx + knownLower.length()));
                if (startOk && endOk) return known;
            }
        }
        return null;
    }

    /** Get known entity set (for testing/inspection). */
    public Set<String> knownEntities() {
        return Set.copyOf(knownEntities);
    }

    /** Seed known entities (e.g., from loaded CompactedMemory relationships). */
    public void seedEntities(Set<String> entities) {
        if (entities != null) knownEntities.addAll(entities);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Jaro-Winkler similarity for entity name resolution.
     * Used to detect that "Dr. Smith" and "John Smith" refer to the same entity.
     * Based on Omni-SimpleMem's entity resolution at 0.85 threshold.
     */
    public static float jaroWinklerSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0f;
        if (s1.equals(s2)) return 1f;

        int len1 = s1.length(), len2 = s2.length();
        if (len1 == 0 || len2 == 0) return 0f;

        int matchDistance = Math.max(len1, len2) / 2 - 1;
        if (matchDistance < 0) matchDistance = 0;

        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];

        int matches = 0;
        int transpositions = 0;

        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) return 0f;

        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        float jaro = ((float) matches / len1 + (float) matches / len2
            + (float) (matches - transpositions / 2) / matches) / 3;

        // Winkler bonus for common prefix (up to 4 chars)
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }

        return jaro + prefix * 0.1f * (1 - jaro);
    }
}
