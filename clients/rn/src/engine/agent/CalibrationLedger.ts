/**
 * Fast feedback profile for proactivity calibration.
 * TypeScript port of core/agent/CalibrationLedger.java.
 *
 * Lives alongside a Bond (keyed by bond ID) — each human-agent relationship
 * has its own calibration.
 *
 * Immediate updates on calibration_feedback action.
 * Forge extraction distills feedback log into soul fragments during sleep.
 */

/** All valid prediction categories. */
const ALL_CATEGORIES = ['anomaly', 'pattern', 'forecast', 'topic', 'correlation'] as const;

/** Maximum number of feedback events kept in the ring buffer. */
const MAX_FEEDBACK_LOG = 20;

/** Adjustment delta per feedback event. */
const FEEDBACK_DELTA = 0.15;

/** A single calibration feedback event. */
export interface CalibrationFeedback {
  when: number;          // epoch ms
  type: string;          // timing | salience | intrusion | positive
  direction: string;     // sooner | later | higher | lower | good
  category: string | null;  // e.g. "anomaly", "pattern"
  trigger: string;       // human's original words
}

/**
 * Serializable snapshot for persistence / JSON transport.
 */
export interface CalibrationLedgerSnapshot {
  timingBias: Record<string, number>;
  salienceWeights: Record<string, number>;
  intrusionTolerance: number;
  positiveFeedbackCount: number;
  feedbackLog: CalibrationFeedback[];
}

export class CalibrationLedger {
  /** Per-category timing bias: -1.0 (tell sooner) to +1.0 (wait for idle). */
  private timingBias: Map<string, number>;

  /** Per-category salience weight: 0.0 (ignore) to 2.0 (amplify). */
  private salienceWeights: Map<string, number>;

  /** Overall intrusion tolerance: 0.0 (leave me alone) to 1.0 (tell me everything). */
  private intrusionTolerance: number;

  /** Count of positive calibration feedbacks (used for tier computation). */
  private positiveFeedbackCount: number;

  /** Ring buffer of recent feedback events. */
  private feedbackLog: CalibrationFeedback[];

  constructor(snapshot?: CalibrationLedgerSnapshot | null) {
    if (snapshot) {
      this.timingBias = new Map(Object.entries(snapshot.timingBias));
      this.salienceWeights = new Map(Object.entries(snapshot.salienceWeights));
      this.intrusionTolerance = snapshot.intrusionTolerance;
      this.positiveFeedbackCount = snapshot.positiveFeedbackCount;
      this.feedbackLog = [...snapshot.feedbackLog];
    } else {
      this.timingBias = new Map();
      this.salienceWeights = new Map();
      this.intrusionTolerance = 0.5;
      this.positiveFeedbackCount = 0;
      this.feedbackLog = [];
    }
  }

  // ── Immediate feedback application ─────────────────────────────────────

  /**
   * Apply a calibration feedback immediately.
   *
   * @param type      timing | salience | intrusion | positive
   * @param direction sooner | later | higher | lower | good
   * @param category  prediction category (null = applies globally)
   * @param trigger   human's original words (for Forge extraction)
   */
  applyFeedback(type: string, direction: string, category: string | null, trigger: string): void {
    const feedback: CalibrationFeedback = {
      when: Date.now(),
      type,
      direction,
      category,
      trigger,
    };
    this.feedbackLog.push(feedback);
    while (this.feedbackLog.length > MAX_FEEDBACK_LOG) {
      this.feedbackLog.shift();
    }

    switch (type) {
      case 'timing': {
        const adjustment = direction === 'sooner' ? -FEEDBACK_DELTA : FEEDBACK_DELTA;
        if (category != null) {
          this.mergeTiming(category, adjustment);
        } else {
          // Apply to all categories
          for (const cat of ALL_CATEGORIES) {
            this.mergeTiming(cat, adjustment);
          }
        }
        // Clamp all timing values
        for (const [k, v] of this.timingBias) {
          this.timingBias.set(k, Math.max(-1.0, Math.min(1.0, v)));
        }
        break;
      }
      case 'salience': {
        const adjustment = direction === 'higher' ? 0.2 : -0.2;
        if (category != null) {
          this.mergeSalience(category, adjustment);
        }
        for (const [k, v] of this.salienceWeights) {
          this.salienceWeights.set(k, Math.max(0.0, Math.min(2.0, v)));
        }
        break;
      }
      case 'intrusion': {
        if (direction === 'higher' || direction === 'good') {
          this.intrusionTolerance = Math.min(1.0, this.intrusionTolerance + 0.1);
        } else {
          this.intrusionTolerance = Math.max(0.0, this.intrusionTolerance - 0.1);
        }
        break;
      }
      case 'positive': {
        this.positiveFeedbackCount++;
        // Positive feedback slightly increases intrusion tolerance
        this.intrusionTolerance = Math.min(1.0, this.intrusionTolerance + 0.02);
        break;
      }
    }
  }

  // ── Queries (used by ProactivityJudgment) ──────────────────────────────

  /** Get timing bias for a category (-1 = tell sooner, +1 = wait). Default: 0. */
  getTimingBias(category: string): number {
    return this.timingBias.get(category) ?? 0.0;
  }

  /** Get salience weight for a category (0 = ignore, 2 = amplify). Default: 1. */
  getSalienceWeight(category: string): number {
    return this.salienceWeights.get(category) ?? 1.0;
  }

  getIntrusionTolerance(): number {
    return this.intrusionTolerance;
  }

  getPositiveFeedbackCount(): number {
    return this.positiveFeedbackCount;
  }

  /** Get recent feedback events for Forge extraction. */
  getRecentFeedback(): CalibrationFeedback[] {
    return [...this.feedbackLog];
  }

  /** Clear the feedback log after Forge extraction. */
  clearFeedbackLog(): void {
    this.feedbackLog = [];
  }

  // ── Serialization ──────────────────────────────────────────────────────

  toSnapshot(): CalibrationLedgerSnapshot {
    return {
      timingBias: Object.fromEntries(this.timingBias),
      salienceWeights: Object.fromEntries(this.salienceWeights),
      intrusionTolerance: this.intrusionTolerance,
      positiveFeedbackCount: this.positiveFeedbackCount,
      feedbackLog: [...this.feedbackLog],
    };
  }

  // ── Internal ───────────────────────────────────────────────────────────

  private mergeTiming(category: string, delta: number): void {
    const current = this.timingBias.get(category) ?? 0.0;
    this.timingBias.set(category, current + delta);
  }

  private mergeSalience(category: string, delta: number): void {
    const current = this.salienceWeights.get(category) ?? 1.0;
    this.salienceWeights.set(category, current + delta);
  }
}
