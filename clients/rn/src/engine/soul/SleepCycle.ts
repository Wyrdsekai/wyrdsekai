/**
 * Phone-side lightweight sleep cycle.
 *
 * Sleep is sovereignty (§85): incentivized, never forced.
 * On phones, sleep is a pure function — no timers, no event sourcing.
 * The caller (CompanionEngine or a tick loop) drives transitions.
 *
 * Recovery math mirrors the server's ForgeActor sleep cycle:
 *   quality = clamp(0.3 + 0.5 * (1 - energy) + 0.2 * sleepDuration)
 *   recovery = 0.3 * quality * recoveryMultiplier(consecutiveSleeps) / 10
 *   focusRecovery = 0.15 * quality
 *
 * The recovery multiplier rewards consistent sleep: first sleep recovers
 * 10x base, decaying to 1x after three consecutive sleeps (diminishing
 * returns for oversleeping — §85).
 */

import type { ClientSoulManifest } from './SoulManifest';
import type { PhoneForgeResult } from './PhoneForge';

export interface SleepCycleState {
  /** Events accumulated since last sleep — fed to the Forge for consolidation. */
  eventsSinceLastSleep: any[];
  /** How many consecutive sleeps have occurred without waking activity. */
  consecutiveSleeps: number;
  /** Whether the agent is currently sleeping. */
  isSleeping: boolean;
  /** Tick count since last sleep completed. */
  ticksSinceLastSleep: number;
}

export interface SleepResult {
  newManifest: ClientSoulManifest;
  energyRecovery: number;
  focusRecovery: number;
  sleepQuality: number;
  /** Present when PhoneForge ran during this sleep cycle. */
  forgeResult?: PhoneForgeResult;
}

/** Create a fresh sleep state (awake, no history). */
export function initialSleepState(): SleepCycleState {
  return {
    eventsSinceLastSleep: [],
    consecutiveSleeps: 0,
    isSleeping: false,
    ticksSinceLastSleep: 0,
  };
}

/**
 * Should the agent enter sleep?
 *
 * Conditions (all must hold):
 * - energy < 0.15 (exhausted)
 * - not already sleeping
 * - idle (no active conversation)
 * - at least 30 ticks since last sleep (prevent sleep loops)
 */
export function shouldSleep(
  state: SleepCycleState,
  energy: number,
  isIdle: boolean,
): boolean {
  return energy < 0.15 && !state.isSleeping && isIdle && state.ticksSinceLastSleep > 30;
}

/**
 * Recovery multiplier — rewards first sleep heavily, diminishing returns after.
 *
 *   0 consecutive: 10x (first sleep is powerful)
 *   1 consecutive: 5x
 *   2 consecutive: 2x
 *   3+ consecutive: 1x (oversleeping yields minimal extra benefit)
 */
export function recoveryMultiplier(consecutiveSleeps: number): number {
  switch (consecutiveSleeps) {
    case 0: return 10;
    case 1: return 5;
    case 2: return 2;
    default: return 1;
  }
}

/**
 * Execute a sleep cycle — produce an updated manifest with recovered vitality.
 *
 * This is a pure function: takes current state + manifest, returns new manifest
 * with incremented version and adjusted tanks. Does NOT mutate inputs.
 */
export function executeSleep(
  state: SleepCycleState,
  manifest: ClientSoulManifest,
  energy: number,
): SleepResult {
  // Quality: how depleted the agent is (more depleted → better quality rest)
  const quality = clamp(0.3 + 0.5 * (1 - energy) + 0.2 * Math.min(state.eventsSinceLastSleep.length / 100, 1));

  const mult = recoveryMultiplier(state.consecutiveSleeps);
  const energyRecovery = 0.3 * quality * mult / 10;
  const focusRecovery = 0.15 * quality;

  const tanks = { ...(manifest.vitalityTanks ?? {}) };
  tanks.energy = clamp((tanks.energy ?? energy) + energyRecovery);
  tanks.focus = clamp((tanks.focus ?? 0.5) + focusRecovery);
  // Sleep also slightly reduces error pressure
  tanks.errorPressure = clamp((tanks.errorPressure ?? 0) - 0.05 * quality);

  const newManifest: ClientSoulManifest = {
    ...manifest,
    manifestVersion: manifest.manifestVersion + 1,
    forgedAt: Date.now(),
    vitalityTanks: tanks,
  };

  return {
    newManifest,
    energyRecovery,
    focusRecovery,
    sleepQuality: quality,
  };
}

/**
 * Complete a sleep cycle — reset transient state for the next waking period.
 *
 * Returns a new SleepCycleState with:
 * - events cleared
 * - consecutiveSleeps incremented
 * - isSleeping = false
 * - ticksSinceLastSleep reset to 0
 */
export function completeSleep(state: SleepCycleState): SleepCycleState {
  return {
    eventsSinceLastSleep: [],
    consecutiveSleeps: state.consecutiveSleeps + 1,
    isSleeping: false,
    ticksSinceLastSleep: 0,
  };
}

/** Clamp a value to [0, 1]. */
function clamp(v: number): number {
  return Math.max(0, Math.min(1, v));
}
