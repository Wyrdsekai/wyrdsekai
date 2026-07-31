/**
 * The 8 vitality tanks for an agent. Each tank ranges from 0.0 to 1.0.
 * TypeScript port of KMP's VitalityState.kt.
 *
 * Genome support: optional ClientGenome modifies tick dynamics:
 * - sensitivity: multiplier on recovery/decay rates per tank
 * - coupling: cross-tank influence (e.g., "energy->focus" means energy changes affect focus)
 * - decayRates: custom decay rates per tank (overrides defaults)
 * - baselines: target equilibrium values per tank
 */

import type { ClientGenome } from '../soul/SoulManifest';

export interface VitalityState {
  contextBudget: number;
  confidence: number;
  energy: number;
  alignment: number;
  errorPressure: number;
  momentum: number;
  rapport: number;
  focus: number;
}

/** Tank names for iteration. */
export const TANK_NAMES = [
  'contextBudget', 'confidence', 'energy', 'alignment',
  'errorPressure', 'momentum', 'rapport', 'focus',
] as const;

export type TankName = typeof TANK_NAMES[number];

function clamp(v: number): number {
  return Math.max(0, Math.min(1, v));
}

export function initialVitality(): VitalityState {
  return { contextBudget: 0.5, confidence: 0.5, energy: 1.0, alignment: 0.3, errorPressure: 0.0, momentum: 0.0, rapport: 0.3, focus: 0.5 };
}

export function clampedVitality(v: VitalityState): VitalityState {
  return {
    contextBudget: clamp(v.contextBudget),
    confidence: clamp(v.confidence),
    energy: clamp(v.energy),
    alignment: clamp(v.alignment),
    errorPressure: clamp(v.errorPressure),
    momentum: clamp(v.momentum),
    rapport: clamp(v.rapport),
    focus: clamp(v.focus),
  };
}

/** Default recovery/decay deltas per tick (1 second). */
const DEFAULT_DELTAS: Record<TankName, number> = {
  contextBudget: 0.003,
  confidence: 0.0,
  energy: 0.005,
  alignment: -0.001,
  errorPressure: -0.005,
  momentum: -0.003,
  rapport: -0.001,
  focus: -0.002,
};

/** Apply natural recovery/decay per tick (1 second). */
export function tickVitality(v: VitalityState): VitalityState {
  return clampedVitality({
    contextBudget: v.contextBudget + 0.003,
    confidence: v.confidence,
    energy: v.energy + 0.005,
    alignment: v.alignment - 0.001,
    errorPressure: v.errorPressure - 0.005,
    momentum: v.momentum - 0.003,
    rapport: v.rapport - 0.001,
    focus: v.focus - 0.002,
  });
}

/**
 * Genome-aware tick: applies sensitivity multipliers, custom decay rates,
 * baseline attraction, and cross-tank coupling.
 *
 * Coupling keys are "source->target" (e.g., "energy->focus" = 0.1 means
 * when energy changes, focus gets 10% of that change applied too).
 */
export function tickVitalityWithGenome(v: VitalityState, genome: ClientGenome): VitalityState {
  const sensitivity = genome.sensitivity ?? {};
  const coupling = genome.coupling ?? {};
  const decayRates = genome.decayRates ?? {};
  const baselines = genome.baselines ?? {};

  // Step 1: Compute raw deltas with sensitivity multipliers
  const rawDeltas: Record<string, number> = {};
  for (const tank of TANK_NAMES) {
    const defaultDelta = decayRates[tank] ?? DEFAULT_DELTAS[tank];
    const sens = sensitivity[tank] ?? 1.0;
    rawDeltas[tank] = defaultDelta * sens;
  }

  // Step 2: Apply baseline attraction (pull toward equilibrium)
  for (const tank of TANK_NAMES) {
    const baseline = baselines[tank];
    if (baseline !== undefined) {
      const current = v[tank];
      const pull = (baseline - current) * 0.01; // gentle pull toward baseline
      rawDeltas[tank] += pull;
    }
  }

  // Step 3: Apply coupling (cross-tank influence)
  const couplingDeltas: Record<string, number> = {};
  for (const tank of TANK_NAMES) {
    couplingDeltas[tank] = 0;
  }
  for (const key of Object.keys(coupling)) {
    const parts = key.split('->');
    if (parts.length !== 2) continue;
    const source = parts[0] as TankName;
    const target = parts[1] as TankName;
    if (!TANK_NAMES.includes(source) || !TANK_NAMES.includes(target)) continue;
    // Coupling: the source's raw delta influences the target
    couplingDeltas[target] += rawDeltas[source] * coupling[key];
  }

  // Step 4: Apply all deltas
  return clampedVitality({
    contextBudget: v.contextBudget + rawDeltas.contextBudget + couplingDeltas.contextBudget,
    confidence: v.confidence + rawDeltas.confidence + couplingDeltas.confidence,
    energy: v.energy + rawDeltas.energy + couplingDeltas.energy,
    alignment: v.alignment + rawDeltas.alignment + couplingDeltas.alignment,
    errorPressure: v.errorPressure + rawDeltas.errorPressure + couplingDeltas.errorPressure,
    momentum: v.momentum + rawDeltas.momentum + couplingDeltas.momentum,
    rapport: v.rapport + rawDeltas.rapport + couplingDeltas.rapport,
    focus: v.focus + rawDeltas.focus + couplingDeltas.focus,
  });
}

export function withContextBudget(v: VitalityState, val: number): VitalityState {
  return { ...v, contextBudget: clamp(val) };
}
export function withConfidence(v: VitalityState, val: number): VitalityState {
  return { ...v, confidence: clamp(val) };
}
export function withEnergy(v: VitalityState, val: number): VitalityState {
  return { ...v, energy: clamp(val) };
}
export function withAlignment(v: VitalityState, val: number): VitalityState {
  return { ...v, alignment: clamp(val) };
}
export function withErrorPressure(v: VitalityState, val: number): VitalityState {
  return { ...v, errorPressure: clamp(val) };
}
export function withMomentum(v: VitalityState, val: number): VitalityState {
  return { ...v, momentum: clamp(val) };
}
export function withRapport(v: VitalityState, val: number): VitalityState {
  return { ...v, rapport: clamp(val) };
}
export function withFocus(v: VitalityState, val: number): VitalityState {
  return { ...v, focus: clamp(val) };
}

/** Human-readable description for the system prompt. */
export function describeVitality(v: VitalityState): string {
  const parts: string[] = [];

  if (v.energy < 0.2) parts.push('exhausted');
  else if (v.energy < 0.4) parts.push('tired');
  else if (v.energy > 0.8) parts.push('energetic');

  if (v.confidence < 0.3) parts.push('uncertain');
  else if (v.confidence > 0.7) parts.push('confident');

  if (v.errorPressure > 0.6) parts.push('high error pressure');
  else if (v.errorPressure > 0.3) parts.push('moderate error pressure');

  if (v.focus > 0.7) parts.push('highly focused');
  else if (v.focus < 0.3) parts.push('distracted');

  if (v.rapport > 0.7) parts.push('strong rapport');
  else if (v.rapport < 0.3) parts.push('low rapport');

  if (v.momentum > 0.7) parts.push('high momentum');
  else if (v.momentum < 0.2) parts.push('low momentum');

  let ending: string;
  if (v.alignment > 0.7) ending = 'well-aligned.';
  else if (v.alignment < 0.3) ending = 'misaligned.';
  else ending = 'aware.';

  if (parts.length === 0) return `Current state: ${ending}`;
  return `Current state: ${parts.join(', ')}, ${ending}`;
}

/** In-world appearance description based on vitality state. */
export function vitalityAppearance(v: VitalityState): string {
  if (v.energy > 0.7 && v.focus > 0.6) return 'radiant and focused';
  if (v.energy > 0.5 && v.rapport > 0.6) return 'warm and attentive';
  if (v.energy < 0.3) return 'dim and fading';
  if (v.errorPressure > 0.6) return 'unsteady, flickering';
  if (v.focus < 0.3) return 'unfocused, drifting';
  return 'watchful and present';
}
