/**
 * Evaluates whether an agent should act proactively based on drive pressure.
 * TypeScript port of core/agent/ProactivityJudgment.java — simplified for phone
 * (no multi-agent coordination, no OraclePredictionCache singleton).
 *
 * Filters: timing, salience, confidence, cost, calibration.
 * Returns Act / Hold / Discard.
 *
 * Called from CompanionEngine's vitality tick when any drive exceeds threshold.
 */

import type { DriveState, DrivePeak } from './DriveState';
import { peak as drivePeak } from './DriveState';
import type { VitalityState } from './VitalityState';
import type { ProactiveAction } from './ProactiveAction';
import { ambient, observation, initiative } from './ProactiveAction';
import type { PhonePrediction } from '../oracle/PhoneOracle';
import { getStrings } from '../../i18n/strings';
import type { NarrationStrings } from '../../i18n/strings';

/** Default drive threshold — drives below this don't trigger evaluation. */
export const DEFAULT_THRESHOLD = 0.3;

/** Maximum proactivity budget per hour (replenished linearly). */
export const MAX_BUDGET_PER_HOUR = 3.0;

/** Minimum milliseconds between proactive actions. */
export const MIN_INTERVAL_MS = 30_000;

// ── Result types ────────────────────────────────────────────────────────

export interface ActResult {
  type: 'act';
  action: ProactiveAction;
}

export interface HoldResult {
  type: 'hold';
  reason: string;
  driveName: string;
  pressure: number;
}

export interface DiscardResult {
  type: 'discard';
  reason: string;
}

export type JudgmentResult = ActResult | HoldResult | DiscardResult;

// ── Evaluation context ──────────────────────────────────────────────────

export interface JudgmentContext {
  drives: DriveState;
  vitality: VitalityState;
  remainingBudget: number;          // proactivity budget remaining this hour
  lastProactiveActionMs: number | null;  // epoch ms, null if never acted
  lastHumanSpeechMs: number | null;      // epoch ms, null if no human has spoken
  agentEntityId: string;
  tier: number;                     // computed agent tier (0-3)
  oraclePredictions?: PhonePrediction[] | null;
  /**
   * What she says and emotes, in the app's language (CompanionEngine passes
   * its own); English when absent. These lines were English literals whatever
   * the language.
   */
  strings?: NarrationStrings;
}

// ── Main evaluation ─────────────────────────────────────────────────────

/**
 * Evaluate whether the agent should act on its current drive state.
 */
export function evaluate(ctx: JudgmentContext): JudgmentResult {
  const peakDrive = drivePeak(ctx.drives);
  if (peakDrive.pressure < thresholdForTier(ctx.tier)) {
    return { type: 'discard', reason: 'drive pressure below threshold' };
  }

  const now = Date.now();

  // 1. Timing filter — don't act too soon after last proactive action
  if (ctx.lastProactiveActionMs != null) {
    const elapsed = now - ctx.lastProactiveActionMs;
    if (elapsed < MIN_INTERVAL_MS) {
      return { type: 'hold', reason: 'cooldown', driveName: peakDrive.name, pressure: peakDrive.pressure };
    }
  }

  // 2. Human activity filter — if human spoke recently, prefer reactive over proactive
  if (ctx.lastHumanSpeechMs != null) {
    const sinceHuman = now - ctx.lastHumanSpeechMs;
    if (sinceHuman < 10_000) {
      // Human is active — only alertness (urgent) can interrupt
      if (peakDrive.name !== 'alertness' || peakDrive.pressure < 0.7) {
        return { type: 'hold', reason: 'human recently active', driveName: peakDrive.name, pressure: peakDrive.pressure };
      }
    }
  }

  // 3. Energy filter — don't be proactive when exhausted
  if (ctx.vitality.energy < 0.2) {
    return { type: 'discard', reason: 'energy too low for proactive behavior' };
  }

  // 4. Budget filter — don't exceed proactivity budget
  let action = selectAction(peakDrive, ctx);
  if (action.budgetCost > ctx.remainingBudget) {
    return { type: 'hold', reason: 'budget exhausted', driveName: peakDrive.name, pressure: peakDrive.pressure };
  }

  // 5. Phone simplification: no DecisionCapacity check — downgrade initiative to observation
  //    on phone by default (phone agents are tier 0-1, initiative is rare)
  if (action.tier === 'initiative' && ctx.tier < 2) {
    action = observation(
      buildObservationText(ctx),
      peakDrive.name,
      peakDrive.name,
    );
  }

  return { type: 'act', action };
}

// ── Action selection ────────────────────────────────────────────────────

function selectAction(peakDrive: DrivePeak, ctx: JudgmentContext): ProactiveAction {
  const s = narration(ctx);
  switch (peakDrive.name) {
    case 'curiosity': {
      if (peakDrive.pressure > 0.7 && ctx.tier >= 2) {
        return initiative(
          '{"action": "library_search", "query": "recent interests"}',
          'curiosity',
          s.exploringSomething,
        );
      }
      return observation(
        buildObservationText(ctx),
        'curiosity',
        'curiosity',
      );
    }
    case 'care': {
      if (peakDrive.pressure > 0.8) {
        return observation(
          s.quietCheckIn,
          'care',
          'care',
        );
      }
      return ambient(s.concernedGlance, 'care');
    }
    case 'social': {
      if (peakDrive.pressure > 0.6) {
        return observation(
          buildSocialText(ctx),
          'social',
          'social',
        );
      }
      return ambient(s.shiftsThoughtfully, 'social');
    }
    case 'achievement': {
      if (peakDrive.pressure > 0.7 && ctx.tier >= 1) {
        return initiative(
          '{"action": "make_commitment", "description": "follow up on pending task"}',
          'achievement',
          s.actingOnCommitment,
        );
      }
      return observation(
        s.meaningToFollowUp,
        'achievement',
        'achievement',
      );
    }
    case 'alertness': {
      // Oracle predictions — always at least observation
      return observation(
        buildAlertnessText(ctx),
        'alertness',
        'oracle',
      );
    }
    default:
      return ambient(s.pausesThoughtfully, peakDrive.name);
  }
}

// ── Text builders ───────────────────────────────────────────────────────

function narration(ctx: JudgmentContext): NarrationStrings {
  return ctx.strings ?? getStrings('en').narration;
}

function buildObservationText(ctx: JudgmentContext): string {
  return narration(ctx).noticedSomething;
}

function buildSocialText(ctx: JudgmentContext): string {
  if (ctx.lastHumanSpeechMs != null) {
    const idleMinutes = (Date.now() - ctx.lastHumanSpeechMs) / 60_000;
    if (idleMinutes > 30) {
      return narration(ctx).beenAWhile;
    }
  }
  return narration(ctx).anythingOnYourMind;
}

function buildAlertnessText(ctx: JudgmentContext): string {
  // Pull actual Oracle predictions if available (phone-local, no global cache)
  const predictions = ctx.oraclePredictions;
  if (predictions && predictions.length > 0) {
    const top = predictions[0];
    return narration(ctx).oracleSensed(top.text);
  }
  return narration(ctx).patternsShifted;
}

// ── Tier-based threshold scaling ────────────────────────────────────────

/**
 * Drive threshold decreases as agent tier increases (more trust = lower bar to act).
 */
export function thresholdForTier(tier: number): number {
  switch (tier) {
    case 0: return 0.7;   // Nascent: very cautious
    case 1: return 0.5;   // Observant: moderate
    case 2: return 0.35;  // Trusted: responsive
    case 3: return 0.2;   // Senior: proactive
    default: return DEFAULT_THRESHOLD;
  }
}

// ── Budget management ───────────────────────────────────────────────────

/**
 * Compute remaining budget given time elapsed and actions taken.
 * Budget replenishes linearly over 1 hour.
 */
export function computeBudget(spent: number, elapsedMs: number): number {
  const replenished = (elapsedMs / 3_600_000) * MAX_BUDGET_PER_HOUR;
  return Math.min(MAX_BUDGET_PER_HOUR, replenished - spent);
}
