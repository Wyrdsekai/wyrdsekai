package org.wyrdsekai.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compacts the working memory buffer before Layer 5 injection.
 *
 * <p>Merges related entries (same action type → keep only last), drops
 * old low-importance entries, and preserves high-importance entries
 * (commitments, tells, findings).</p>
 */
public final class MemoryCompactor {

    private MemoryCompactor() {}

    /** Memory should not exceed this fraction of remaining token budget. */
    static final double MEMORY_BUDGET_FRACTION = 0.20;

    private static final int CHARS_PER_TOKEN = 4;

    /** Entries containing these keywords are high-importance (always kept). */
    private static final List<String> HIGH_IMPORTANCE_KEYWORDS = List.of(
        "commitment", "promised", "tell", "told", "found", "discovered",
        "created plan", "goal", "important", "urgent", "error", "failed"
    );

    /** Time prefix pattern: HH:MM at start of entry. */
    private static final Pattern TIME_PREFIX = Pattern.compile("^\\d{2}:\\d{2}\\s+");

    /** Action type pattern in memory entries. */
    private static final Pattern ACTION_TYPE = Pattern.compile(
        "\\b(go_to_room|library_search|web_search|remember|note|tell_agent|" +
        "query_oracle|read_content|equip|doff|goal_done|create_task_plan)\\b");

    /**
     * Compact memory buffer if it exceeds the budget fraction.
     *
     * @param memoryBuffer        raw memory buffer string (newline-separated entries)
     * @param remainingTokenBudget tokens remaining after higher-priority layers
     * @return compacted buffer (may be unchanged if under budget)
     */
    public static String compact(String memoryBuffer, int remainingTokenBudget) {
        if (memoryBuffer == null || memoryBuffer.isBlank()) return memoryBuffer;

        int budgetTokens = (int) (remainingTokenBudget * MEMORY_BUDGET_FRACTION);
        int currentTokens = estimateTokens(memoryBuffer);

        if (currentTokens <= budgetTokens) {
            return memoryBuffer; // under budget — no compaction needed
        }

        // Parse into entries
        var entries = new ArrayList<>(List.of(memoryBuffer.split("\n")));
        if (entries.isEmpty()) return memoryBuffer;

        // Strategy 1: Deduplicate by action type (keep last of each type)
        entries = new ArrayList<>(deduplicateByAction(entries));

        // Check if under budget now
        var result = String.join("\n", entries);
        if (estimateTokens(result) <= budgetTokens) return result;

        // Strategy 2: Drop low-importance entries from the beginning
        entries = new ArrayList<>(dropLowImportance(entries));

        // Check if under budget now
        result = String.join("\n", entries);
        if (estimateTokens(result) <= budgetTokens) return result;

        // Strategy 3: Hard truncate — keep only the last N entries that fit
        return hardTruncate(entries, budgetTokens);
    }

    /**
     * Keep only the last entry for each action type.
     * Non-action entries are always kept.
     */
    static List<String> deduplicateByAction(List<String> entries) {
        // Track last index for each action type
        var lastByAction = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < entries.size(); i++) {
            var actionType = extractActionType(entries.get(i));
            if (actionType != null) {
                lastByAction.put(actionType, i);
            }
        }

        var result = new ArrayList<String>();
        for (int i = 0; i < entries.size(); i++) {
            var actionType = extractActionType(entries.get(i));
            if (actionType == null || lastByAction.get(actionType) == i) {
                result.add(entries.get(i));
            }
        }
        return result;
    }

    /**
     * Drop entries that are not high-importance.
     * Preserves at least the last 5 entries regardless.
     */
    static List<String> dropLowImportance(List<String> entries) {
        if (entries.size() <= 5) return entries;

        var result = new ArrayList<String>();
        int protectedCount = 5; // always keep last 5
        int dropCandidates = entries.size() - protectedCount;

        for (int i = 0; i < entries.size(); i++) {
            if (i < dropCandidates && !isHighImportance(entries.get(i))) {
                continue; // drop
            }
            result.add(entries.get(i));
        }
        return result;
    }

    /**
     * Hard truncate: keep only the last entries that fit within token budget.
     */
    static String hardTruncate(List<String> entries, int budgetTokens) {
        int total = 0;
        int startIdx = entries.size();
        for (int i = entries.size() - 1; i >= 0; i--) {
            int entryTokens = estimateTokens(entries.get(i));
            if (total + entryTokens > budgetTokens) break;
            total += entryTokens;
            startIdx = i;
        }
        return String.join("\n", entries.subList(startIdx, entries.size()));
    }

    static boolean isHighImportance(String entry) {
        var lower = entry.toLowerCase();
        for (var kw : HIGH_IMPORTANCE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    static String extractActionType(String entry) {
        var m = ACTION_TYPE.matcher(entry);
        return m.find() ? m.group(1) : null;
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }
}
