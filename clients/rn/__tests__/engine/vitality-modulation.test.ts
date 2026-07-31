import { computeModulation } from '../../src/engine/agent/VitalityModulation';
import { initialVitality, withEnergy, withConfidence, withMomentum, withFocus, withErrorPressure } from '../../src/engine/agent/VitalityState';
import { NEXUS_COMPANION } from '../../src/engine/agent/AgentProfile';

describe('VitalityModulation', () => {
  it('default modulation with initial vitality', () => {
    const mod = computeModulation(initialVitality(), NEXUS_COMPANION);
    expect(mod.maxResponseTokens).toBeGreaterThan(0);
    expect(mod.maxResponseTokens).toBeLessThanOrEqual(NEXUS_COMPANION.maxResponseTokens);
    expect(mod.temperature).toBeGreaterThan(0);
    expect(mod.debounceDelayMs).toBeGreaterThan(0);
    expect(mod.conversationHistorySize).toBeGreaterThanOrEqual(5);
  });

  it('low energy reduces max tokens', () => {
    const lowEnergy = withEnergy(initialVitality(), 0.1);
    const mod = computeModulation(lowEnergy, NEXUS_COMPANION);
    const defaultMod = computeModulation(initialVitality(), NEXUS_COMPANION);
    expect(mod.maxResponseTokens).toBeLessThan(defaultMod.maxResponseTokens);
  });

  it('low confidence increases temperature', () => {
    const lowConf = withConfidence(initialVitality(), 0.1);
    const mod = computeModulation(lowConf, NEXUS_COMPANION);
    const defaultMod = computeModulation(initialVitality(), NEXUS_COMPANION);
    expect(mod.temperature).toBeGreaterThan(defaultMod.temperature);
  });

  it('high error pressure reduces temperature', () => {
    const highError = withErrorPressure(withConfidence(initialVitality(), 0.3), 0.7);
    const noError = withConfidence(initialVitality(), 0.3);
    const modHigh = computeModulation(highError, NEXUS_COMPANION);
    const modLow = computeModulation(noError, NEXUS_COMPANION);
    expect(modHigh.temperature).toBeLessThan(modLow.temperature);
  });

  it('high momentum reduces debounce delay', () => {
    const highMom = withMomentum(initialVitality(), 0.9);
    const mod = computeModulation(highMom, NEXUS_COMPANION);
    const defaultMod = computeModulation(initialVitality(), NEXUS_COMPANION);
    expect(mod.debounceDelayMs).toBeLessThan(defaultMod.debounceDelayMs);
  });

  it('high focus increases conversation history size', () => {
    const highFocus = withFocus(initialVitality(), 0.9);
    const lowFocus = withFocus(initialVitality(), 0.1);
    const modHigh = computeModulation(highFocus, NEXUS_COMPANION);
    const modLow = computeModulation(lowFocus, NEXUS_COMPANION);
    expect(modHigh.conversationHistorySize).toBeGreaterThan(modLow.conversationHistorySize);
  });
});
