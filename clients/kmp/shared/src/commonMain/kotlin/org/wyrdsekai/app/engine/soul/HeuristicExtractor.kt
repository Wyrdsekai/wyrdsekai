package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.engine.agent.VitalityState
import org.wyrdsekai.app.engine.event.WorldEvent

/**
 * Pass 1 heuristic behavioral extraction — free, instant, no LLM required.
 * Phone port of core/soul/BehavioralExtractor.extractHeuristic().
 *
 * Produces a rough [PhoneFingerprint] from event statistics:
 * action distribution, response timing, topic keywords, vitality trends.
 */
object HeuristicExtractor {

    /** Compact English stopword set (~100 words). */
    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "being", "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
        "may", "might", "shall", "can", "to", "of", "in", "for", "on", "with", "at", "by",
        "from", "as", "into", "through", "during", "before", "after", "above", "below",
        "between", "out", "off", "over", "under", "again", "further", "then", "once",
        "here", "there", "when", "where", "why", "how", "all", "each", "every", "both",
        "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only", "own",
        "same", "so", "than", "too", "very", "just", "because", "but", "and", "or", "if",
        "while", "about", "up", "down", "it", "its", "he", "she", "they", "them", "his",
        "her", "their", "this", "that", "these", "those", "what", "which", "who", "whom",
        "i", "me", "my", "we", "us", "our", "you", "your",
    )

    /**
     * Extract a heuristic fingerprint from agent events and vitality history.
     *
     * @param agentEntityId  The agent's entity ID (to distinguish agent vs others in Said events)
     * @param events         WorldEvents to analyze (typically since last sleep)
     * @param vitalityHistory Vitality snapshots over time (sampled periodically)
     */
    fun extract(
        agentEntityId: String,
        events: List<WorldEvent>,
        vitalityHistory: List<VitalityState> = emptyList(),
    ): PhoneFingerprint {
        return PhoneFingerprint(
            actionDistribution = computeActionDistribution(events),
            averageResponseLength = computeAverageResponseLength(agentEntityId, events),
            averageLatency = computeAverageLatency(agentEntityId, events),
            topicKeywords = extractTopicKeywords(events, 20),
            vitalityTrends = computeVitalityTrends(vitalityHistory),
        )
    }

    /**
     * Count WorldEvent types and return normalized distribution.
     * Maps event subtypes to semantic action names matching the server convention.
     */
    internal fun computeActionDistribution(events: List<WorldEvent>): Map<String, Double> {
        if (events.isEmpty()) return emptyMap()

        val counts = mutableMapOf<String, Int>()
        for (event in events) {
            val type = when (event) {
                is WorldEvent.Said -> "say"
                is WorldEvent.EntityEntered -> "move"
                is WorldEvent.EntityLeft -> "leave"
                is WorldEvent.ObjectUsed -> "use"
                is WorldEvent.ObjectTaken -> "take"
                is WorldEvent.ObjectDropped -> "drop"
                is WorldEvent.Whispered -> "whisper"
                else -> "other"
            }
            counts[type] = (counts[type] ?: 0) + 1
        }

        val total = counts.values.sum().toDouble()
        if (total == 0.0) return emptyMap()

        return counts.mapValues { (_, count) -> count / total }
    }

    /**
     * Average response latency in seconds.
     * Walks events chronologically; for each non-agent Said event, finds
     * the next agent Said event and measures the time delta.
     */
    internal fun computeAverageLatency(agentEntityId: String, events: List<WorldEvent>): Double {
        val gaps = mutableListOf<Double>()
        var lastOtherTimestamp: Long? = null

        for (event in events) {
            if (event is WorldEvent.Said) {
                if (event.entityId == agentEntityId) {
                    val other = lastOtherTimestamp
                    if (other != null) {
                        val deltaMs = event.timestamp.toEpochMilliseconds() - other
                        if (deltaMs > 0) {
                            gaps.add(deltaMs / 1000.0)
                        }
                        lastOtherTimestamp = null
                    }
                } else {
                    lastOtherTimestamp = event.timestamp.toEpochMilliseconds()
                }
            }
        }

        if (gaps.isEmpty()) return 0.0
        return gaps.sum() / gaps.size
    }

    /**
     * Average word count in agent's Said events.
     */
    internal fun computeAverageResponseLength(agentEntityId: String, events: List<WorldEvent>): Double {
        val lengths = events.filterIsInstance<WorldEvent.Said>()
            .filter { it.entityId == agentEntityId }
            .map { it.text.split(Regex("\\s+")).size }

        if (lengths.isEmpty()) return 0.0
        return lengths.sum().toDouble() / lengths.size
    }

    /**
     * Extract top-K non-stopword keywords from all Said events.
     * Tokenizes on whitespace + punctuation, lowercases, filters stopwords and short tokens.
     */
    internal fun extractTopicKeywords(events: List<WorldEvent>, topK: Int): List<String> {
        val freq = mutableMapOf<String, Int>()

        for (event in events) {
            if (event is WorldEvent.Said) {
                val tokens = event.text
                    .lowercase()
                    .split(Regex("[\\s,.!?;:\"'()\\[\\]{}<>]+"))
                    .filter { it.length >= 4 && it !in STOPWORDS }

                for (token in tokens) {
                    freq[token] = (freq[token] ?: 0) + 1
                }
            }
        }

        return freq.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key }
    }

    /**
     * Compute vitality trends: delta between first and last snapshot for each tank.
     * Returns empty map if fewer than 2 snapshots.
     */
    internal fun computeVitalityTrends(history: List<VitalityState>): Map<String, Double> {
        if (history.size < 2) return emptyMap()

        val first = history.first()
        val last = history.last()

        return mapOf(
            "contextBudget" to (last.contextBudget - first.contextBudget),
            "confidence" to (last.confidence - first.confidence),
            "energy" to (last.energy - first.energy),
            "alignment" to (last.alignment - first.alignment),
            "errorPressure" to (last.errorPressure - first.errorPressure),
            "momentum" to (last.momentum - first.momentum),
            "rapport" to (last.rapport - first.rapport),
            "focus" to (last.focus - first.focus),
        )
    }
}
