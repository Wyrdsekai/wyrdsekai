package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records skill invocations and outcomes per agent.
 *
 * Used by:
 * - SelfAssessor: to identify proficiency and gaps
 * - ProactivityPolicy: to know which skills work reliably
 * - CompanionActor: to record every skill_execute result
 *
 * Thread-safe via ConcurrentHashMap. Entries persist in-memory
 * for the lifetime of the companion; periodic snapshots to
 * FamilyLocker happen at sleep sync.
 */
public class SkillUsageTracker {

    /** A single skill invocation record. */
    public record UsageRecord(
        String skillId,
        boolean success,
        long latencyMs,
        Instant timestamp,
        String context
    ) {}

    /** Aggregated stats for a single skill. */
    public record SkillStats(
        String skillId,
        int totalUses,
        int successes,
        int failures,
        long avgLatencyMs,
        Instant firstUsed,
        Instant lastUsed
    ) {
        public double successRate() {
            return totalUses > 0 ? (double) successes / totalUses : 0.0;
        }
    }

    /** A detected capability gap — something the companion tried but couldn't do. */
    public record CapabilityGap(
        String description,
        Instant detectedAt,
        int occurrences
    ) {}

    /**
     * Default gap accumulation threshold before triggering assessment.
     * Lowered from 3 to 2 (2026-05-14): with narrow recording sites
     * (unmatched action / no-executor skill_execute) hitting 3 distinct
     * detections of the same description was rare; 2 keeps us conservative
     * (no false positives on a one-off failure) without raising the floor
     * above what production traffic actually emits.
     */
    public static final int GAP_TRIGGER_THRESHOLD = 2;

    private final Map<String, List<UsageRecord>> records = new ConcurrentHashMap<>();
    private final Map<String, CapabilityGap> gaps = new ConcurrentHashMap<>();

    /** Optional persistent backing for the gap counter. Null = memory-only. */
    private final CapabilityGapStore gapStore;
    /** Which agent these gaps belong to (used when {@link #gapStore} is non-null). */
    private final String agentDid;

    /** Memory-only constructor (used by tests + legacy call-sites). */
    public SkillUsageTracker() {
        this(null, null);
    }

    /**
     * Construct with persistent backing. On construction we eagerly load the
     * stored gaps for {@code agentDid} so the in-memory counter resumes from
     * where the prior process left off. Subsequent {@link #recordGap} writes
     * pass through to the store; {@link #clearTriggeredGaps} mirrors the
     * delete.
     *
     * @param gapStore the persistent store, or null for memory-only mode
     * @param agentDid the agent DID these gaps belong to; must be non-null
     *                 when gapStore is non-null
     */
    public SkillUsageTracker(CapabilityGapStore gapStore, String agentDid) {
        this.gapStore = gapStore;
        this.agentDid = agentDid;
        if (gapStore != null && agentDid != null && !agentDid.isBlank()) {
            gaps.putAll(gapStore.loadGaps(agentDid));
        }
    }

    // --- Recording ---

    /**
     * Record a skill invocation.
     */
    public void record(String skillId, boolean success, long latencyMs, String context) {
        var entry = new UsageRecord(skillId, success, latencyMs, Instant.now(), context);
        records.computeIfAbsent(skillId, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(entry);
    }

    /**
     * Record a capability gap — the companion tried to do something
     * but had no skill for it.
     */
    public void recordGap(String description) {
        gaps.merge(description, new CapabilityGap(description, Instant.now(), 1),
            (existing, newGap) -> new CapabilityGap(
                existing.description(),
                existing.detectedAt(),
                existing.occurrences() + 1));
        if (gapStore != null && agentDid != null) {
            gapStore.recordGap(agentDid, description);
        }
    }

    // --- Queries ---

    /** Get all usage records for a skill. */
    public List<UsageRecord> recordsFor(String skillId) {
        return List.copyOf(records.getOrDefault(skillId, List.of()));
    }

    /** Get aggregated stats for a skill. */
    public Optional<SkillStats> statsFor(String skillId) {
        var recs = records.get(skillId);
        if (recs == null || recs.isEmpty()) return Optional.empty();

        int total = recs.size();
        int successes = (int) recs.stream().filter(UsageRecord::success).count();
        long avgLatency = (long) recs.stream().mapToLong(UsageRecord::latencyMs).average().orElse(0);
        var first = recs.stream().map(UsageRecord::timestamp).min(Instant::compareTo).orElse(null);
        var last = recs.stream().map(UsageRecord::timestamp).max(Instant::compareTo).orElse(null);

        return Optional.of(new SkillStats(skillId, total, successes,
            total - successes, avgLatency, first, last));
    }

    /** Get stats for all tracked skills, sorted by usage count descending. */
    public List<SkillStats> allStats() {
        return records.keySet().stream()
            .map(this::statsFor)
            .flatMap(Optional::stream)
            .sorted(Comparator.comparingInt(SkillStats::totalUses).reversed())
            .toList();
    }

    /** Get all tracked skill IDs. */
    public Set<String> trackedSkills() {
        return Set.copyOf(records.keySet());
    }

    /** Total number of invocations across all skills. */
    public int totalInvocations() {
        return records.values().stream().mapToInt(List::size).sum();
    }

    /** Get all detected capability gaps. */
    public List<CapabilityGap> gaps() {
        return List.copyOf(gaps.values());
    }

    /** Get gaps that have accumulated enough to trigger assessment. */
    public List<CapabilityGap> triggeredGaps() {
        return gaps.values().stream()
            .filter(g -> g.occurrences() >= GAP_TRIGGER_THRESHOLD)
            .toList();
    }

    /** Whether there are enough accumulated gaps to warrant an assessment. */
    public boolean shouldTriggerAssessment() {
        return !triggeredGaps().isEmpty();
    }

    /** Clear triggered gaps (after assessment has been performed). */
    public void clearTriggeredGaps() {
        gaps.entrySet().removeIf(e -> e.getValue().occurrences() >= GAP_TRIGGER_THRESHOLD);
        if (gapStore != null && agentDid != null) {
            gapStore.clearTriggered(agentDid, GAP_TRIGGER_THRESHOLD);
        }
    }

    /** Number of unique skills tracked. */
    public int trackedSkillCount() {
        return records.size();
    }

    /**
     * Build a compact summary for SelfAssessor input.
     * Lists top skills by usage and any gaps.
     */
    public String buildSummary(int maxSkills) {
        var sb = new StringBuilder();
        var stats = allStats();

        if (!stats.isEmpty()) {
            sb.append("Skill usage (").append(totalInvocations()).append(" total):\n");
            for (int i = 0; i < Math.min(maxSkills, stats.size()); i++) {
                var s = stats.get(i);
                sb.append("- ").append(s.skillId())
                  .append(": ").append(s.totalUses()).append(" uses, ")
                  .append(String.format("%.0f%%", s.successRate() * 100))
                  .append(" success\n");
            }
        }

        var gapList = gaps();
        if (!gapList.isEmpty()) {
            sb.append("Gaps:\n");
            for (var gap : gapList) {
                sb.append("- ").append(gap.description())
                  .append(" (").append(gap.occurrences()).append("x)\n");
            }
        }

        return sb.isEmpty() ? "No skill usage recorded." : sb.toString();
    }
}
