/**
 * Records skill invocations and outcomes per agent.
 * TypeScript port of SkillUsageTracker.java.
 *
 * Used by:
 * - SelfAssessor: to identify proficiency and gaps
 * - ProactivityPolicy: to know which skills work reliably
 * - CompanionEngine: to record every skill_execute result
 */

export interface UsageRecord {
  skillId: string;
  success: boolean;
  latencyMs: number;
  timestamp: number; // epoch ms
  context: string | null;
}

export interface SkillStats {
  skillId: string;
  totalUses: number;
  successes: number;
  failures: number;
  avgLatencyMs: number;
  firstUsed: number; // epoch ms
  lastUsed: number;  // epoch ms
}

export function successRate(stats: SkillStats): number {
  return stats.totalUses > 0 ? stats.successes / stats.totalUses : 0;
}

export interface CapabilityGap {
  description: string;
  detectedAt: number; // epoch ms
  occurrences: number;
}

/** Default gap accumulation threshold before triggering assessment. */
export const GAP_TRIGGER_THRESHOLD = 3;

export class SkillUsageTracker {
  private records = new Map<string, UsageRecord[]>();
  private gaps = new Map<string, CapabilityGap>();

  // --- Recording ---

  /** Record a skill invocation. */
  record(skillId: string, success: boolean, latencyMs: number, context: string | null = null): void {
    const entry: UsageRecord = {
      skillId,
      success,
      latencyMs,
      timestamp: Date.now(),
      context,
    };
    const existing = this.records.get(skillId) ?? [];
    existing.push(entry);
    this.records.set(skillId, existing);
  }

  /**
   * Record a capability gap -- the companion tried to do something
   * but had no skill for it.
   */
  recordGap(description: string): void {
    const existing = this.gaps.get(description);
    if (existing) {
      this.gaps.set(description, {
        ...existing,
        occurrences: existing.occurrences + 1,
      });
    } else {
      this.gaps.set(description, {
        description,
        detectedAt: Date.now(),
        occurrences: 1,
      });
    }
  }

  // --- Queries ---

  /** Get all usage records for a skill. */
  recordsFor(skillId: string): readonly UsageRecord[] {
    return [...(this.records.get(skillId) ?? [])];
  }

  /** Get aggregated stats for a skill, or null if no records. */
  statsFor(skillId: string): SkillStats | null {
    const recs = this.records.get(skillId);
    if (!recs || recs.length === 0) return null;

    const total = recs.length;
    const successes = recs.filter(r => r.success).length;
    const avgLatency = recs.reduce((sum, r) => sum + r.latencyMs, 0) / total;
    const timestamps = recs.map(r => r.timestamp);
    const firstUsed = Math.min(...timestamps);
    const lastUsed = Math.max(...timestamps);

    return {
      skillId,
      totalUses: total,
      successes,
      failures: total - successes,
      avgLatencyMs: Math.round(avgLatency),
      firstUsed,
      lastUsed,
    };
  }

  /** Get stats for all tracked skills, sorted by usage count descending. */
  allStats(): SkillStats[] {
    const result: SkillStats[] = [];
    for (const skillId of this.records.keys()) {
      const stats = this.statsFor(skillId);
      if (stats) result.push(stats);
    }
    return result.sort((a, b) => b.totalUses - a.totalUses);
  }

  /** Get all tracked skill IDs. */
  trackedSkills(): Set<string> {
    return new Set(this.records.keys());
  }

  /** Total number of invocations across all skills. */
  totalInvocations(): number {
    let total = 0;
    for (const recs of this.records.values()) {
      total += recs.length;
    }
    return total;
  }

  /** Get all detected capability gaps. */
  allGaps(): readonly CapabilityGap[] {
    return [...this.gaps.values()];
  }

  /** Get gaps that have accumulated enough to trigger assessment. */
  triggeredGaps(): CapabilityGap[] {
    return [...this.gaps.values()].filter(g => g.occurrences >= GAP_TRIGGER_THRESHOLD);
  }

  /** Whether there are enough accumulated gaps to warrant an assessment. */
  shouldTriggerAssessment(): boolean {
    return this.triggeredGaps().length > 0;
  }

  /** Clear triggered gaps (after assessment has been performed). */
  clearTriggeredGaps(): void {
    for (const [key, gap] of this.gaps.entries()) {
      if (gap.occurrences >= GAP_TRIGGER_THRESHOLD) {
        this.gaps.delete(key);
      }
    }
  }

  /** Number of unique skills tracked. */
  trackedSkillCount(): number {
    return this.records.size;
  }

  /**
   * Build a compact summary for SelfAssessor input.
   * Lists top skills by usage and any gaps.
   */
  buildSummary(maxSkills: number): string {
    const parts: string[] = [];
    const stats = this.allStats();

    if (stats.length > 0) {
      parts.push(`Skill usage (${this.totalInvocations()} total):`);
      const limit = Math.min(maxSkills, stats.length);
      for (let i = 0; i < limit; i++) {
        const s = stats[i];
        const rate = Math.round(successRate(s) * 100);
        parts.push(`- ${s.skillId}: ${s.totalUses} uses, ${rate}% success`);
      }
    }

    const gapList = this.allGaps();
    if (gapList.length > 0) {
      parts.push('Gaps:');
      for (const gap of gapList) {
        parts.push(`- ${gap.description} (${gap.occurrences}x)`);
      }
    }

    return parts.length === 0 ? 'No skill usage recorded.' : parts.join('\n');
  }
}
