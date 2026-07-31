import { zeroDerivatives, computeDerivatives, describeTrends } from '../../src/engine/agent/VitalityDerivatives';
import { initialVitality, withEnergy, withConfidence } from '../../src/engine/agent/VitalityState';

describe('VitalityDerivatives', () => {
  it('zero derivatives are all zero', () => {
    const d = zeroDerivatives();
    expect(d.energyVelocity).toBe(0);
    expect(d.confidenceVelocity).toBe(0);
    expect(d.energyAcceleration).toBe(0);
  });

  it('computes velocity from state changes', () => {
    const prev = initialVitality();
    const current = withEnergy(prev, prev.energy - 0.1);
    const d = computeDerivatives(prev, current, zeroDerivatives());
    expect(d.energyVelocity).toBeLessThan(0); // Energy dropped
  });

  it('computes acceleration from velocity changes', () => {
    const state1 = initialVitality();
    const state2 = withEnergy(state1, state1.energy - 0.05);
    const d1 = computeDerivatives(state1, state2, zeroDerivatives());

    const state3 = withEnergy(state2, state2.energy - 0.10);
    const d2 = computeDerivatives(state2, state3, d1);

    // Energy is falling faster, so acceleration should be negative
    expect(d2.energyAcceleration).toBeLessThan(0);
  });

  it('describeTrends returns empty for no change', () => {
    const d = zeroDerivatives();
    expect(describeTrends(d)).toBe('');
  });

  it('describeTrends describes falling energy', () => {
    const prev = initialVitality();
    const current = withEnergy(prev, prev.energy - 0.1);
    const d = computeDerivatives(prev, current, zeroDerivatives());
    const trends = describeTrends(d);
    expect(trends).toContain('energy');
    expect(trends).toContain('falling');
  });
});
