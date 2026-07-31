package org.wyrdsekai.core.i18n;

import java.util.*;

/**
 * Memory locale policy for Forge consolidation (§104.2-104.3).
 *
 * Core principle: NEVER translate during consolidation.
 * Memories stay in origin language — translation loses
 * cultural-cognitive texture (Nisbett, COLING 2025).
 *
 * Consolidation groups by semantic similarity (via multilingual
 * embeddings), not by language. Mixed-language clusters are normal.
 *
 * Code-switching is preserved, not normalized (§104.7).
 */
public class MemoryLocalePolicy {

    /** Whether a fragment should be translated during Forge consolidation. */
    public boolean shouldTranslate(String fragmentLocale, String targetLocale) {
        // NEVER translate during consolidation
        return false;
    }

    /** Whether two fragments can be consolidated despite different languages. */
    public boolean canConsolidate(String localeA, String localeB, double semanticSimilarity) {
        // Cross-language consolidation allowed if semantically similar
        // The multilingual embedding model handles the similarity calculation
        return semanticSimilarity >= 0.7;
    }

    /** Sort fragments for dream generation (§104.3). */
    public List<String> dreamOrder(Map<String, Double> fragmentRecency,
                                     Map<String, String> fragmentLocales) {
        // Dreams are multilingual — recency-weighted, language-reflecting
        // natural bilingual behavior (dreaming in mixed languages)
        var entries = new ArrayList<>(fragmentRecency.entrySet());
        entries.sort(Comparator.comparingDouble(Map.Entry<String, Double>::getValue).reversed());
        return entries.stream().map(Map.Entry::getKey).toList();
    }

    /** Whether code-switching in a text should be normalized. */
    public boolean shouldNormalizeCodeSwitch(String text) {
        // Code-switching is a feature (ICLR 2026), not a bug.
        // Preserve in memory, do not normalize.
        return false;
    }

    /** Detect approximate language of a text fragment (heuristic). */
    public String detectLanguage(String text) {
        if (text == null || text.isEmpty()) return "unknown";

        // Simple heuristic — production would use a proper language detector
        boolean hasCjk = text.chars().anyMatch(c ->
            (c >= 0x4E00 && c <= 0x9FFF) ||   // CJK Unified
            (c >= 0x3040 && c <= 0x309F) ||   // Hiragana
            (c >= 0x30A0 && c <= 0x30FF) ||   // Katakana
            (c >= 0xAC00 && c <= 0xD7AF));    // Hangul

        if (hasCjk) {
            boolean hasHiragana = text.chars().anyMatch(c -> c >= 0x3040 && c <= 0x309F);
            boolean hasKatakana = text.chars().anyMatch(c -> c >= 0x30A0 && c <= 0x30FF);
            boolean hasHangul = text.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7AF);
            if (hasHiragana || hasKatakana) return "ja";
            if (hasHangul) return "ko";
            return "zh";
        }

        // Check for Spanish-specific characters
        boolean hasSpanish = text.chars().anyMatch(c ->
            c == 'ñ' || c == 'Ñ' || c == '¿' || c == '¡');
        if (hasSpanish) return "es";

        return "en"; // Default fallback
    }

    /** Policy for memory fragment locale metadata. */
    public record FragmentLocale(
        String fragmentId,
        String originLocale,
        boolean containsCodeSwitch,
        List<String> detectedLanguages
    ) {
        public boolean isMonolingual() { return detectedLanguages.size() <= 1; }
        public boolean isMultilingual() { return detectedLanguages.size() > 1; }
    }
}
