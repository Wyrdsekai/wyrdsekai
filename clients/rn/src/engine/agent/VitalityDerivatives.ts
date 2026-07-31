/**
 * Derivative tracking for VitalityState.
 * TypeScript port of KMP's VitalityDerivatives.kt.
 * Tracks velocity (rate of change) and acceleration (rate of change of velocity)
 * using exponential moving average (alpha=0.3) for smoothing.
 */

import type { VitalityState } from './VitalityState';

export interface VitalityDerivatives {
  energyVelocity: number;
  confidenceVelocity: number;
  focusVelocity: number;
  errorPressureVelocity: number;
  rapportVelocity: number;
  momentumVelocity: number;
  energyAcceleration: number;
  confidenceAcceleration: number;
  focusAcceleration: number;
  errorPressureAcceleration: number;
}

const ALPHA = 0.3;

function smooth(prev: number, current: number): number {
  return prev * (1 - ALPHA) + current * ALPHA;
}

export function zeroDerivatives(): VitalityDerivatives {
  return {
    energyVelocity: 0, confidenceVelocity: 0, focusVelocity: 0,
    errorPressureVelocity: 0, rapportVelocity: 0, momentumVelocity: 0,
    energyAcceleration: 0, confidenceAcceleration: 0,
    focusAcceleration: 0, errorPressureAcceleration: 0,
  };
}

export function computeDerivatives(
  prev: VitalityState,
  current: VitalityState,
  prevDerivatives: VitalityDerivatives,
): VitalityDerivatives {
  const eV = smooth(prevDerivatives.energyVelocity, current.energy - prev.energy);
  const cV = smooth(prevDerivatives.confidenceVelocity, current.confidence - prev.confidence);
  const fV = smooth(prevDerivatives.focusVelocity, current.focus - prev.focus);
  const epV = smooth(prevDerivatives.errorPressureVelocity, current.errorPressure - prev.errorPressure);
  const rV = smooth(prevDerivatives.rapportVelocity, current.rapport - prev.rapport);
  const mV = smooth(prevDerivatives.momentumVelocity, current.momentum - prev.momentum);

  const eA = smooth(prevDerivatives.energyAcceleration, eV - prevDerivatives.energyVelocity);
  const cA = smooth(prevDerivatives.confidenceAcceleration, cV - prevDerivatives.confidenceVelocity);
  const fA = smooth(prevDerivatives.focusAcceleration, fV - prevDerivatives.focusVelocity);
  const epA = smooth(prevDerivatives.errorPressureAcceleration, epV - prevDerivatives.errorPressureVelocity);

  return { energyVelocity: eV, confidenceVelocity: cV, focusVelocity: fV, errorPressureVelocity: epV, rapportVelocity: rV, momentumVelocity: mV, energyAcceleration: eA, confidenceAcceleration: cA, focusAcceleration: fA, errorPressureAcceleration: epA };
}

/** Describe notable trends for the LLM prompt. */
export function describeTrends(d: VitalityDerivatives): string {
  const parts: string[] = [];
  const threshold = 0.01;

  addTrend(parts, 'energy', d.energyVelocity, d.energyAcceleration, threshold);
  addTrend(parts, 'confidence', d.confidenceVelocity, d.confidenceAcceleration, threshold);
  addTrend(parts, 'focus', d.focusVelocity, d.focusAcceleration, threshold);
  addTrend(parts, 'error pressure', d.errorPressureVelocity, d.errorPressureAcceleration, threshold);

  return parts.length === 0 ? '' : `Trends: ${parts.join(' ')}`;
}

function addTrend(
  parts: string[],
  name: string,
  velocity: number,
  acceleration: number,
  threshold: number,
): void {
  if (Math.abs(velocity) < threshold) return;

  const direction = velocity > 0 ? 'rising' : 'falling';
  const rate = Math.abs(velocity) > 0.03 ? 'rapidly ' : '';
  let trend = `${name} ${rate}${direction}`;

  if (Math.abs(acceleration) > threshold) {
    trend += acceleration > 0 ? ' (accelerating)' : ' (decelerating)';
  }
  parts.push(trend + '.');
}
