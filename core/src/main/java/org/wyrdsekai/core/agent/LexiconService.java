package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emergent Argot / Lexicon service (§63).
 * Tracks agent-created vocabulary that emerges through interaction.
 * Lewis signaling games: agents develop shared terms for recurring concepts.
 *
 * M0 scope: vocabulary tracking and frequency analysis.
 * M2+: LoRA adapter management for fine-tuning agent language models.
 */
public class LexiconService {

    /** A lexicon entry — an emergent term created by agents. */
    public record LexiconEntry(
        String term,
        String definition,
        String creatorAgent,
        String originRoom,
        Instant createdAt,
        int usageCount,
        double coherenceScore,  // 0.0-1.0: how consistently the term is used
        Set<String> adoptedBy,  // agents that have used this term
        Map<String, String> translations  // lang → translated text (i18n)
    ) {
        /** Backward-compatible constructor — no translations. */
        public LexiconEntry(String term, String definition, String creatorAgent,
                            String originRoom, Instant createdAt, int usageCount,
                            double coherenceScore, Set<String> adoptedBy) {
            this(term, definition, creatorAgent, originRoom, createdAt,
                 usageCount, coherenceScore, adoptedBy, new HashMap<>());
        }
    }

    /** Calibration status for the argot system. */
    public enum CalibrationStatus {
        UNCALIBRATED, CALIBRATING, CALIBRATED, DRIFTING
    }

    private final Map<String, LexiconEntry> entries = new ConcurrentHashMap<>();
    private CalibrationStatus status = CalibrationStatus.UNCALIBRATED;

    /**
     * Register a new term in the lexicon.
     */
    public LexiconEntry registerTerm(String term, String definition,
                                       String creatorAgent, String originRoom) {
        var normalizedTerm = term.toLowerCase().trim();
        var existing = entries.get(normalizedTerm);
        if (existing != null) {
            // Update existing entry
            return updateUsage(normalizedTerm, creatorAgent);
        }

        var entry = new LexiconEntry(normalizedTerm, definition, creatorAgent,
            originRoom, Instant.now(), 1, 0.5, new HashSet<>(Set.of(creatorAgent)));
        entries.put(normalizedTerm, entry);
        return entry;
    }

    /**
     * Record usage of a term by an agent. Updates frequency and adoption.
     */
    public LexiconEntry updateUsage(String term, String agentId) {
        var normalizedTerm = term.toLowerCase().trim();
        var entry = entries.get(normalizedTerm);
        if (entry == null) return null;

        var adopted = new HashSet<>(entry.adoptedBy());
        adopted.add(agentId);

        // Coherence increases with more adopters
        double coherence = Math.min(1.0, adopted.size() * 0.2);

        var updated = new LexiconEntry(entry.term(), entry.definition(),
            entry.creatorAgent(), entry.originRoom(), entry.createdAt(),
            entry.usageCount() + 1, coherence, adopted);
        entries.put(normalizedTerm, updated);
        return updated;
    }

    /**
     * Search for terms matching a query.
     */
    public List<LexiconEntry> search(String query) {
        var lowerQuery = query.toLowerCase();
        return entries.values().stream()
            .filter(e -> e.term().contains(lowerQuery)
                || e.definition().toLowerCase().contains(lowerQuery))
            .sorted(Comparator.comparingInt(LexiconEntry::usageCount).reversed())
            .toList();
    }

    /**
     * Get the most used terms.
     */
    public List<LexiconEntry> topTerms(int limit) {
        return entries.values().stream()
            .sorted(Comparator.comparingInt(LexiconEntry::usageCount).reversed())
            .limit(limit)
            .toList();
    }

    /**
     * Get terms created by a specific agent.
     */
    public List<LexiconEntry> termsByAgent(String agentId) {
        return entries.values().stream()
            .filter(e -> e.creatorAgent().equals(agentId))
            .toList();
    }

    /**
     * Get terms adopted by at least N agents (widely shared vocabulary).
     */
    public List<LexiconEntry> widelyAdopted(int minAdopters) {
        return entries.values().stream()
            .filter(e -> e.adoptedBy().size() >= minAdopters)
            .sorted(Comparator.comparingInt(LexiconEntry::usageCount).reversed())
            .toList();
    }

    /** Look up a specific term. */
    public Optional<LexiconEntry> lookup(String term) {
        return Optional.ofNullable(entries.get(term.toLowerCase().trim()));
    }

    /** Total terms in the lexicon. */
    public int termCount() {
        return entries.size();
    }

    /** Get calibration status. */
    public CalibrationStatus calibrationStatus() {
        return status;
    }

    /** Set calibration status. */
    public void setCalibrationStatus(CalibrationStatus newStatus) {
        this.status = newStatus;
    }

    // ── Translation Memory (i18n) ──

    /**
     * Register a translation for a term.
     */
    public LexiconEntry registerTranslation(String term, String lang,
                                             String translation, String agentId) {
        var normalizedTerm = term.toLowerCase().trim();
        var entry = entries.get(normalizedTerm);
        if (entry == null) {
            // Auto-register the term with the translation as definition
            entry = registerTerm(normalizedTerm, term, agentId, "translation");
        }
        var translations = new HashMap<>(entry.translations());
        translations.put(lang, translation);
        var adopted = new HashSet<>(entry.adoptedBy());
        adopted.add(agentId);
        var updated = new LexiconEntry(entry.term(), entry.definition(),
            entry.creatorAgent(), entry.originRoom(), entry.createdAt(),
            entry.usageCount() + 1,
            Math.min(1.0, adopted.size() * 0.2),
            adopted, translations);
        entries.put(normalizedTerm, updated);
        return updated;
    }

    /**
     * Get a cached translation for a term in a specific language.
     */
    public Optional<String> getTranslation(String term, String lang) {
        var entry = entries.get(term.toLowerCase().trim());
        if (entry == null) return Optional.empty();
        return Optional.ofNullable(entry.translations().get(lang));
    }

    /**
     * Get all terms that have translations in a given language.
     */
    public List<LexiconEntry> translatedTerms(String lang) {
        return entries.values().stream()
            .filter(e -> e.translations().containsKey(lang))
            .sorted(Comparator.comparingInt(LexiconEntry::usageCount).reversed())
            .toList();
    }

    /** Human-readable summary. */
    public String describe() {
        if (entries.isEmpty()) {
            return "The Lexicon is empty — no emergent terms have been recorded yet.";
        }
        var sb = new StringBuilder("=== The Lexicon ===\n\n");
        sb.append("Terms: ").append(termCount()).append("\n");
        sb.append("Calibration: ").append(status).append("\n\n");

        sb.append("Top terms:\n");
        topTerms(5).forEach(e ->
            sb.append("  ").append(e.term())
                .append(" — ").append(e.definition())
                .append(" (used ").append(e.usageCount()).append("x")
                .append(", adopted by ").append(e.adoptedBy().size()).append(")")
                .append("\n"));
        return sb.toString().stripTrailing();
    }
}
