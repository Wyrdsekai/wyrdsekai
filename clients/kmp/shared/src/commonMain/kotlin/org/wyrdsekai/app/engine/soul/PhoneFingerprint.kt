package org.wyrdsekai.app.engine.soul

import kotlinx.serialization.Serializable

/**
 * Lightweight behavioral fingerprint for phone-side extraction.
 * Port of core/soul/BehavioralFingerprint.java, trimmed for phone constraints.
 *
 * Heuristic fields are filled by [HeuristicExtractor] (free, instant).
 * LLM-derived fields (topicAffinities, stylisticMarkers, emotionalPatterns)
 * are filled by a future Wave 2 LLM pass.
 */
@Serializable
data class PhoneFingerprint(
    /** Action type frequencies, e.g. "say" -> 0.72, "move" -> 0.15. */
    val actionDistribution: Map<String, Double> = emptyMap(),
    /** Average word count of agent responses. */
    val averageResponseLength: Double = 0.0,
    /** Average response latency in seconds. */
    val averageLatency: Double = 0.0,
    /** Top non-stopword keywords from conversation. */
    val topicKeywords: List<String> = emptyList(),
    /** Vitality tank trends: tank name -> total delta over observation window. */
    val vitalityTrends: Map<String, Double> = emptyMap(),
    // --- Filled by LLM pass (Wave 2): ---
    /** Topic affinities: topic -> weight 0-1. */
    val topicAffinities: Map<String, Double> = emptyMap(),
    /** Characteristic phrases and speech patterns. */
    val stylisticMarkers: List<String> = emptyList(),
    /** Emotional responsiveness: emotion -> responsiveness 0-1. */
    val emotionalPatterns: Map<String, Double> = emptyMap(),
) {
    companion object {
        fun empty(): PhoneFingerprint = PhoneFingerprint()

        /**
         * Weighted merge of two fingerprints for sleep-cycle consolidation.
         * new = existing * (1 - alpha) + fresh * alpha.
         *
         * @param existing The historical fingerprint
         * @param fresh    The newly extracted fingerprint
         * @param alpha    Weight for fresh data (default 0.3 = 30% new, 70% historical)
         */
        fun merge(existing: PhoneFingerprint, fresh: PhoneFingerprint, alpha: Double = 0.3): PhoneFingerprint {
            return PhoneFingerprint(
                actionDistribution = mergeMaps(existing.actionDistribution, fresh.actionDistribution, alpha),
                averageResponseLength = existing.averageResponseLength * (1 - alpha) + fresh.averageResponseLength * alpha,
                averageLatency = existing.averageLatency * (1 - alpha) + fresh.averageLatency * alpha,
                topicKeywords = mergeKeywords(existing.topicKeywords, fresh.topicKeywords),
                vitalityTrends = mergeMaps(existing.vitalityTrends, fresh.vitalityTrends, alpha),
                topicAffinities = mergeMaps(existing.topicAffinities, fresh.topicAffinities, alpha),
                stylisticMarkers = mergeKeywords(existing.stylisticMarkers, fresh.stylisticMarkers),
                emotionalPatterns = mergeMaps(existing.emotionalPatterns, fresh.emotionalPatterns, alpha),
            )
        }

        private fun mergeMaps(a: Map<String, Double>, b: Map<String, Double>, alpha: Double): Map<String, Double> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val result = LinkedHashMap(a)
            for ((key, freshVal) in b) {
                result[key] = result[key]?.let { old -> old * (1 - alpha) + freshVal * alpha } ?: freshVal
            }
            return result
        }

        private fun mergeKeywords(a: List<String>, b: List<String>): List<String> {
            if (b.isEmpty()) return a
            if (a.isEmpty()) return b
            val seen = LinkedHashSet(a)
            seen.addAll(b)
            return seen.toList()
        }
    }
}
