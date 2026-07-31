/**
 * Controls when and how a companion may proactively use skills
 * without explicit human request.
 * TypeScript port of ProactivityPolicy.java.
 *
 * The policy defines which skill patterns are proactive-eligible,
 * vitality thresholds, and a windowed rate limit. The companion's
 * LLM decides whether to act; this policy gates whether the
 * proactivity context is injected into Layer 2.7.
 */

export interface ProactivityPolicyConfig {
  proactivePatterns: string[];
  minEnergy: number;
  minConfidence: number;
  maxPerWindow: number;
  windowSizeMs: number;
}

/** Phone defaults: higher thresholds, less autonomy. */
export function phoneDefaultConfig(patterns: string[]): ProactivityPolicyConfig {
  return {
    proactivePatterns: [...patterns],
    minEnergy: 0.6,
    minConfidence: 0.5,
    maxPerWindow: 2,
    windowSizeMs: 600_000, // 10 minutes
  };
}

/** Server defaults: lower thresholds, more autonomy. */
export function serverDefaultConfig(patterns: string[]): ProactivityPolicyConfig {
  return {
    proactivePatterns: [...patterns],
    minEnergy: 0.4,
    minConfidence: 0.5,
    maxPerWindow: 3,
    windowSizeMs: 600_000,
  };
}

/** Disabled policy -- no proactive skills. */
export function disabledConfig(): ProactivityPolicyConfig {
  return {
    proactivePatterns: [],
    minEnergy: 1.0,
    minConfidence: 1.0,
    maxPerWindow: 0,
    windowSizeMs: 600_000,
  };
}

/**
 * Tracks proactive actions within a rolling window.
 */
export class ProactivityTracker {
  private actionsInWindow = 0;
  private windowStart = Date.now();

  constructor(private readonly config: ProactivityPolicyConfig) {}

  /** Whether proactive skills should be shown given current vitality. */
  isActive(energy: number, confidence: number): boolean {
    if (this.config.proactivePatterns.length === 0) return false;
    return energy >= this.config.minEnergy && confidence >= this.config.minConfidence;
  }

  /** Whether a specific skill ID matches any proactive pattern. */
  matchesPattern(skillId: string): boolean {
    if (!skillId || this.config.proactivePatterns.length === 0) return false;
    return this.config.proactivePatterns.some(pattern => globMatch(pattern, skillId));
  }

  /** Whether we can act proactively (within window budget and vitality). */
  canActProactively(energy: number, confidence: number): boolean {
    if (!this.isActive(energy, confidence)) return false;
    this.resetWindowIfExpired();
    return this.actionsInWindow < this.config.maxPerWindow;
  }

  /** How many proactive actions remain in the current window. */
  remainingInWindow(): number {
    this.resetWindowIfExpired();
    return Math.max(0, this.config.maxPerWindow - this.actionsInWindow);
  }

  /**
   * Record a proactive action.
   * @returns true if within budget, false if window budget is exhausted
   */
  recordAction(): boolean {
    this.resetWindowIfExpired();
    this.actionsInWindow++;
    return this.actionsInWindow <= this.config.maxPerWindow;
  }

  /**
   * Build the proactivity section for Layer 2.7 capability context.
   * @returns Proactivity context string, or null if inactive
   */
  buildContextSection(energy: number, confidence: number): string | null {
    if (!this.isActive(energy, confidence)) return null;
    const remaining = this.remainingInWindow();
    if (remaining <= 0) return null;

    const lines: string[] = [];
    lines.push('## Proactive Skills (you may use these unprompted when context suggests it)');
    for (const pattern of this.config.proactivePatterns) {
      lines.push(`- ${pattern}`);
    }
    lines.push(
      `Budget: ${remaining} of ${this.config.maxPerWindow} proactive actions remaining this window.`,
    );
    return lines.join('\n') + '\n';
  }

  // --- Internal ---

  private resetWindowIfExpired(): void {
    const now = Date.now();
    if (now - this.windowStart > this.config.windowSizeMs) {
      this.windowStart = now;
      this.actionsInWindow = 0;
    }
  }
}

/** Simple glob matching: "*" at end matches any suffix. */
function globMatch(pattern: string, skillId: string): boolean {
  if (pattern === skillId) return true;
  if (pattern.endsWith('*')) {
    const prefix = pattern.slice(0, -1);
    return skillId.startsWith(prefix);
  }
  return false;
}
