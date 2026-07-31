/**
 * Five motivational drives that create internal impulse for proactive behavior.
 * TypeScript port of core/agent/DriveState.java.
 *
 * Each drive accumulates pressure (0.0-1.0) over time and spikes on relevant events.
 * When pressure crosses a threshold, the agent considers acting via ProactivityJudgment.
 *
 * Drives are separate from VitalityState to avoid breaking existing call sites.
 * VitalityState = how the agent FEELS. DriveState = what the agent WANTS TO DO.
 */

export interface DriveState {
  /** Fueled by unusual events, Oracle anomalies, new data. */
  curiosity: number;
  /** Fueled by human silence, stress signals, time-of-day patterns. */
  care: number;
  /** Fueled by elapsed time since last interaction, rapport level. */
  social: number;
  /** Fueled by pending commitments, Oracle actionable predictions. */
  achievement: number;
  /** Fueled by Oracle predictions, system events, zone broadcasts. */
  alertness: number;
}

export interface DrivePeak {
  name: string;
  pressure: number;
}

// ── Passive accumulation rates (per second) ─────────────────────────────

const CURIOSITY_RATE   = 0.0003; // ~18 min to 0.3 threshold
const CARE_RATE        = 0.0002; // ~25 min to 0.3
const SOCIAL_RATE      = 0.0004; // ~12 min to 0.3
const ACHIEVEMENT_RATE = 0.0001; // ~50 min to 0.3 (slow burn)
const ALERTNESS_RATE   = 0.0001; // mostly event-driven

function clamp(v: number): number {
  return Math.max(0.0, Math.min(1.0, v));
}

/** All drives at zero — freshly relieved or newly created agent. */
export function initialDriveState(): DriveState {
  return { curiosity: 0.0, care: 0.0, social: 0.0, achievement: 0.0, alertness: 0.0 };
}

/**
 * Passive tick — drives accumulate slowly over time.
 * Called every vitality tick (1 second).
 */
export function tickDrives(d: DriveState): DriveState {
  return {
    curiosity:   clamp(d.curiosity   + CURIOSITY_RATE),
    care:        clamp(d.care        + CARE_RATE),
    social:      clamp(d.social      + SOCIAL_RATE),
    achievement: clamp(d.achievement + ACHIEVEMENT_RATE),
    alertness:   clamp(d.alertness   + ALERTNESS_RATE),
  };
}

// ── Event spikes ────────────────────────────────────────────────────────

export function spikeCuriosity(d: DriveState, amount: number): DriveState {
  return { ...d, curiosity: clamp(d.curiosity + amount) };
}

export function spikeCare(d: DriveState, amount: number): DriveState {
  return { ...d, care: clamp(d.care + amount) };
}

export function spikeSocial(d: DriveState, amount: number): DriveState {
  return { ...d, social: clamp(d.social + amount) };
}

export function spikeAchievement(d: DriveState, amount: number): DriveState {
  return { ...d, achievement: clamp(d.achievement + amount) };
}

export function spikeAlertness(d: DriveState, amount: number): DriveState {
  return { ...d, alertness: clamp(d.alertness + amount) };
}

// ── Relief (after acting on a drive) ────────────────────────────────────

export function relieveCuriosity(d: DriveState): DriveState {
  return { ...d, curiosity: 0.0 };
}

export function relieveCare(d: DriveState): DriveState {
  return { ...d, care: 0.0 };
}

export function relieveSocial(d: DriveState): DriveState {
  return { ...d, social: 0.0 };
}

export function relieveAchievement(d: DriveState): DriveState {
  return { ...d, achievement: 0.0 };
}

export function relieveAlertness(d: DriveState): DriveState {
  return { ...d, alertness: 0.0 };
}

// ── Queries ─────────────────────────────────────────────────────────────

/** Returns the name and pressure of the highest drive. */
export function peak(d: DriveState): DrivePeak {
  let name = 'curiosity';
  let max = d.curiosity;
  if (d.care > max) { name = 'care'; max = d.care; }
  if (d.social > max) { name = 'social'; max = d.social; }
  if (d.achievement > max) { name = 'achievement'; max = d.achievement; }
  if (d.alertness > max) { name = 'alertness'; max = d.alertness; }
  return { name, pressure: max };
}

/** Whether any drive exceeds the given threshold. */
export function anyAbove(d: DriveState, threshold: number): boolean {
  return d.curiosity > threshold || d.care > threshold || d.social > threshold
    || d.achievement > threshold || d.alertness > threshold;
}
