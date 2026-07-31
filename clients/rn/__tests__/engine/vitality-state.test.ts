import {
  initialVitality, tickVitality, clampedVitality, describeVitality,
  vitalityAppearance, withEnergy, withConfidence, withFocus,
  withErrorPressure, withRapport, withMomentum, withAlignment,
  VitalityState,
} from '../../src/engine/agent/VitalityState';

describe('VitalityState', () => {
  it('initial() has expected defaults', () => {
    const v = initialVitality();
    expect(v.contextBudget).toBe(0.5);
    expect(v.confidence).toBe(0.5);
    expect(v.energy).toBe(1.0);
    expect(v.alignment).toBe(0.3);
    expect(v.errorPressure).toBe(0.0);
    expect(v.momentum).toBe(0.0);
    expect(v.rapport).toBe(0.3);
    expect(v.focus).toBe(0.5);
  });

  it('tick applies natural recovery/decay', () => {
    const v = initialVitality();
    const after = tickVitality(v);
    expect(after.contextBudget).toBeGreaterThan(v.contextBudget);
    expect(after.energy).toBe(1.0); // Already at max, clamped
    expect(after.alignment).toBeLessThan(v.alignment);
    expect(after.momentum).toBe(0); // Already at min, clamped
    expect(after.focus).toBeLessThan(v.focus);
  });

  it('clamp keeps values in [0, 1]', () => {
    const v: VitalityState = {
      contextBudget: 1.5, confidence: -0.3, energy: 2.0, alignment: -1,
      errorPressure: 0.5, momentum: 0.5, rapport: 0.5, focus: 0.5,
    };
    const clamped = clampedVitality(v);
    expect(clamped.contextBudget).toBe(1.0);
    expect(clamped.confidence).toBe(0.0);
    expect(clamped.energy).toBe(1.0);
    expect(clamped.alignment).toBe(0.0);
  });

  it('withX functions set and clamp', () => {
    const v = initialVitality();
    expect(withEnergy(v, 0.3).energy).toBe(0.3);
    expect(withEnergy(v, 1.5).energy).toBe(1.0);
    expect(withConfidence(v, -0.1).confidence).toBe(0.0);
    expect(withFocus(v, 0.8).focus).toBe(0.8);
    expect(withErrorPressure(v, 0.4).errorPressure).toBe(0.4);
    expect(withRapport(v, 0.9).rapport).toBe(0.9);
    expect(withMomentum(v, 0.6).momentum).toBe(0.6);
    expect(withAlignment(v, 0.7).alignment).toBe(0.7);
  });

  it('describe() returns a non-empty string', () => {
    const desc = describeVitality(initialVitality());
    expect(desc).toContain('Current state:');
    expect(desc.length).toBeGreaterThan(10);
  });

  it('describe() mentions exhausted when energy is low', () => {
    const v = withEnergy(initialVitality(), 0.1);
    expect(describeVitality(v)).toContain('exhausted');
  });

  it('describe() mentions energetic when energy is high', () => {
    const v = withEnergy(initialVitality(), 0.9);
    expect(describeVitality(v)).toContain('energetic');
  });

  it('appearance() changes with vitality', () => {
    // initial focus=0.5, need >0.6 for "radiant and focused"
    expect(vitalityAppearance(withFocus(initialVitality(), 0.8))).toBe('radiant and focused');
    expect(vitalityAppearance(withEnergy(initialVitality(), 0.2))).toBe('dim and fading');
    expect(vitalityAppearance(withErrorPressure(initialVitality(), 0.7))).toBe('unsteady, flickering');
  });

  it('convergence — repeated ticks converge to equilibrium', () => {
    let v = initialVitality();
    for (let i = 0; i < 1000; i++) {
      v = tickVitality(v);
    }
    // Energy should converge toward max (1.0) since it only recovers
    expect(v.energy).toBe(1.0);
    // Error pressure should converge toward 0
    expect(v.errorPressure).toBe(0.0);
    // Momentum should converge toward 0
    expect(v.momentum).toBe(0.0);
  });
});
