package org.wyrdsekai.app.engine.agent

/**
 * Compacts the working memory buffer before Layer 5 injection (KMP port).
 *
 * Merges related entries (same action type -> keep only last), drops
 * old low-importance entries, and preserves high-importance entries
 * (commitments, tells, findings).
 */
object MemoryCompactor {

    /** Memory should not exceed this fraction of remaining token budget. */
    const val MEMORY_BUDGET_FRACTION = 0.20

    private const val CHARS_PER_TOKEN = 4

    /** Entries containing these keywords are high-importance (always kept). */
    private val HIGH_IMPORTANCE_KEYWORDS = listOf(
        "commitment", "promised", "tell", "told", "found", "discovered",
        "created plan", "goal", "important", "urgent", "error", "failed",
    )

    /** Action type pattern in memory entries. */
    private val ACTION_TYPE = Regex(
        "\\b(go_to_room|library_search|web_search|remember|note|tell_agent|" +
        "query_oracle|read_content|equip|doff|goal_done|create_task_plan)\\b"
    )

    /**
     * Compact memory buffer if it exceeds the budget fraction.
     *
     * @param memoryBuffer         raw memory buffer string (newline-separated entries)
     * @param remainingTokenBudget tokens remaining after higher-priority layers
     * @return compacted buffer (may be unchanged if under budget)
     */
    fun compact(memoryBuffer: String?, remainingTokenBudget: Int): String? {
        if (memoryBuffer.isNullOrBlank()) return memoryBuffer

        val budgetTokens = (remainingTokenBudget * MEMORY_BUDGET_FRACTION).toInt()
        val currentTokens = estimateTokens(memoryBuffer)

        if (currentTokens <= budgetTokens) {
            return memoryBuffer // under budget — no compaction needed
        }

        // Parse into entries
        var entries = memoryBuffer.split("\n").toMutableList()
        if (entries.isEmpty()) return memoryBuffer

        // Strategy 1: Deduplicate by action type (keep last of each type)
        entries = deduplicateByAction(entries).toMutableList()

        // Check if under budget now
        var result = entries.joinToString("\n")
        if (estimateTokens(result) <= budgetTokens) return result

        // Strategy 2: Drop low-importance entries from the beginning
        entries = dropLowImportance(entries).toMutableList()

        // Check if under budget now
        result = entries.joinToString("\n")
        if (estimateTokens(result) <= budgetTokens) return result

        // Strategy 3: Hard truncate — keep only the last N entries that fit
        return hardTruncate(entries, budgetTokens)
    }

    /**
     * Keep only the last entry for each action type.
     * Non-action entries are always kept.
     */
    internal fun deduplicateByAction(entries: List<String>): List<String> {
        // Track last index for each action type
        val lastByAction = linkedMapOf<String, Int>()
        for (i in entries.indices) {
            val actionType = extractActionType(entries[i])
            if (actionType != null) {
                lastByAction[actionType] = i
            }
        }

        val result = mutableListOf<String>()
        for (i in entries.indices) {
            val actionType = extractActionType(entries[i])
            if (actionType == null || lastByAction[actionType] == i) {
                result.add(entries[i])
            }
        }
        return result
    }

    /**
     * Drop entries that are not high-importance.
     * Preserves at least the last 5 entries regardless.
     */
    internal fun dropLowImportance(entries: List<String>): List<String> {
        if (entries.size <= 5) return entries

        val result = mutableListOf<String>()
        val protectedCount = 5 // always keep last 5
        val dropCandidates = entries.size - protectedCount

        for (i in entries.indices) {
            if (i < dropCandidates && !isHighImportance(entries[i])) {
                continue // drop
            }
            result.add(entries[i])
        }
        return result
    }

    /**
     * Hard truncate: keep only the last entries that fit within token budget.
     */
    internal fun hardTruncate(entries: List<String>, budgetTokens: Int): String {
        var total = 0
        var startIdx = entries.size
        for (i in entries.indices.reversed()) {
            val entryTokens = estimateTokens(entries[i])
            if (total + entryTokens > budgetTokens) break
            total += entryTokens
            startIdx = i
        }
        return entries.subList(startIdx, entries.size).joinToString("\n")
    }

    internal fun isHighImportance(entry: String): Boolean {
        val lower = entry.lowercase()
        return HIGH_IMPORTANCE_KEYWORDS.any { lower.contains(it) }
    }

    internal fun extractActionType(entry: String): String? {
        val m = ACTION_TYPE.find(entry)
        return m?.groupValues?.get(1)
    }

    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return maxOf(1, text.length / CHARS_PER_TOKEN)
    }
}
