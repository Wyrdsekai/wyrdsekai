package org.wyrdsekai.app.engine.agent

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Records skill invocations and outcomes for a companion.
 *
 * Port of core/agent/SkillUsageTracker.java for the KMP phone client.
 * Simplified for phone: in-memory only, no persistence, no periodic
 * snapshots to FamilyLocker (that happens at sleep sync via Between).
 *
 * Used by:
 * - CapabilityContextBuilder: to surface proficiency info
 * - ProactivityPolicy: to know which skills work reliably
 * - CompanionEngine: to record every skill_execute result
 */
class SkillUsageTracker {

    /** A single skill invocation record. */
    data class SkillUsageRecord(
        val skillId: String,
        val success: Boolean,
        val latencyMs: Long,
        val timestamp: Instant,
    )

    /** Aggregated stats for a single skill. */
    data class SkillStats(
        val invocations: Int,
        val successes: Int,
        val failures: Int,
        val avgLatencyMs: Long,
    ) {
        val successRate: Double
            get() = if (invocations > 0) successes.toDouble() / invocations else 0.0
    }

    /** Default failure rate threshold for gap detection. */
    private val GAP_FAILURE_THRESHOLD = 0.50

    private val records = mutableMapOf<String, MutableList<SkillUsageRecord>>()
    private val notFoundSkills = mutableSetOf<String>()

    // --- Recording ---

    /**
     * Record a skill invocation.
     */
    fun record(skillId: String, success: Boolean, latencyMs: Long) {
        val entry = SkillUsageRecord(
            skillId = skillId,
            success = success,
            latencyMs = latencyMs,
            timestamp = Clock.System.now(),
        )
        records.getOrPut(skillId) { mutableListOf() }.add(entry)
    }

    /**
     * Record a skill that was requested but not found.
     */
    fun recordNotFound(skillId: String) {
        notFoundSkills.add(skillId)
    }

    // --- Queries ---

    /** Get aggregated stats for a skill, or null if never tracked. */
    fun stats(skillId: String): SkillStats? {
        val recs = records[skillId] ?: return null
        if (recs.isEmpty()) return null

        val total = recs.size
        val successes = recs.count { it.success }
        val avgLatency = if (recs.isNotEmpty()) {
            recs.map { it.latencyMs }.average().toLong()
        } else {
            0L
        }

        return SkillStats(
            invocations = total,
            successes = successes,
            failures = total - successes,
            avgLatencyMs = avgLatency,
        )
    }

    /** Get stats for all tracked skills, sorted by invocation count descending. */
    fun allStats(): Map<String, SkillStats> {
        val result = mutableMapOf<String, SkillStats>()
        for (skillId in records.keys) {
            stats(skillId)?.let { result[skillId] = it }
        }
        return result.entries
            .sortedByDescending { it.value.invocations }
            .associate { it.key to it.value }
    }

    /** Total number of invocations across all skills. */
    fun totalInvocations(): Int =
        records.values.sumOf { it.size }

    /** Number of unique skills tracked. */
    fun trackedSkillCount(): Int = records.size

    /**
     * Returns skill IDs that represent capability gaps:
     * - Skills with > 50% failure rate
     * - Skills that were requested but not found
     */
    fun gaps(): List<String> {
        val gapSet = mutableSetOf<String>()

        // Skills with high failure rate
        for ((skillId, recs) in records) {
            if (recs.isEmpty()) continue
            val failureRate = recs.count { !it.success }.toDouble() / recs.size
            if (failureRate > GAP_FAILURE_THRESHOLD) {
                gapSet.add(skillId)
            }
        }

        // Skills that were requested but not found
        gapSet.addAll(notFoundSkills)

        return gapSet.toList()
    }

    /**
     * Build a compact summary for assessment / context injection.
     * Lists top skills by usage and any gaps.
     */
    fun buildSummary(maxSkills: Int = 5): String {
        val sb = StringBuilder()
        val allStatsMap = allStats()

        if (allStatsMap.isNotEmpty()) {
            sb.append("Skill usage (").append(totalInvocations()).append(" total):\n")
            var count = 0
            for ((skillId, s) in allStatsMap) {
                if (count >= maxSkills) break
                val pct = kotlin.math.round(s.successRate * 100).toInt()
                sb.append("- ").append(skillId)
                    .append(": ").append(s.invocations).append(" uses, ")
                    .append(pct).append("% success\n")
                count++
            }
        }

        val gapList = gaps()
        if (gapList.isNotEmpty()) {
            sb.append("Gaps:\n")
            for (gap in gapList) {
                sb.append("- ").append(gap).append("\n")
            }
        }

        return if (sb.isEmpty()) "No skill usage recorded." else sb.toString()
    }
}
