import {
  type ResourceSnapshot,
  TIER_ORDER,
  configForTier,
  recommendTier,
  tierIndex,
} from '../../src/engine/tier/TierConfig';

describe('TierConfig', () => {
  it('tier configs are consistent with spec', () => {
    // T0: remote inference, no non-essential rooms
    const t0 = configForTier('T0');
    expect(t0.maxRooms).toBe(0);
    expect(t0.inferenceMode).toBe('REMOTE');
    expect(t0.betweenEnabled).toBe(false);

    // T1: one room, local inference
    const t1 = configForTier('T1');
    expect(t1.maxRooms).toBe(1);
    expect(t1.inferenceMode).toBe('LOCAL');
    expect(t1.betweenEnabled).toBe(false);

    // T2: multi-room, Between enabled
    const t2 = configForTier('T2');
    expect(t2.maxRooms).toBe(4);
    expect(t2.betweenEnabled).toBe(true);

    // T3: full peer
    const t3 = configForTier('T3');
    expect(t3.maxRooms).toBe(16);
    expect(t3.maxConcurrentInference).toBe(2);
    expect(t3.betweenEnabled).toBe(true);
  });

  it('context tokens scale with tier', () => {
    const tokens = TIER_ORDER.map(t => configForTier(t).maxContextTokens);
    for (let i = 1; i < tokens.length; i++) {
      expect(tokens[i]).toBeGreaterThanOrEqual(tokens[i - 1]);
    }
  });

  it('tier index is ordered', () => {
    expect(tierIndex('T0')).toBe(0);
    expect(tierIndex('T1')).toBe(1);
    expect(tierIndex('T2')).toBe(2);
    expect(tierIndex('T3')).toBe(3);
  });
});

describe('ResourceSnapshot recommendTier', () => {
  const snap = (overrides: Partial<ResourceSnapshot> = {}): ResourceSnapshot => ({
    availableMemoryMb: 2000,
    totalMemoryMb: 4000,
    batteryPercent: 80,
    isCharging: false,
    thermalState: 'NOMINAL',
    hasWifi: true,
    ...overrides,
  });

  it('critical thermal forces T0', () => {
    expect(recommendTier(snap({
      availableMemoryMb: 4000,
      batteryPercent: 100,
      isCharging: true,
      thermalState: 'CRITICAL',
    }))).toBe('T0');
  });

  it('low battery not charging forces T0', () => {
    expect(recommendTier(snap({
      availableMemoryMb: 4000,
      batteryPercent: 5,
      isCharging: false,
    }))).toBe('T0');
  });

  it('charging with wifi and high memory gives T3', () => {
    expect(recommendTier(snap({
      availableMemoryMb: 4000,
      batteryPercent: 80,
      isCharging: true,
      hasWifi: true,
    }))).toBe('T3');
  });

  it('wifi with good resources gives T2', () => {
    expect(recommendTier(snap({
      availableMemoryMb: 2500,
      batteryPercent: 60,
      isCharging: false,
      hasWifi: true,
    }))).toBe('T2');
  });

  it('moderate resources gives T1', () => {
    expect(recommendTier(snap({
      availableMemoryMb: 1500,
      batteryPercent: 40,
      isCharging: false,
      hasWifi: false,
    }))).toBe('T1');
  });

  it('low memory gives T0', () => {
    expect(recommendTier(snap({
      availableMemoryMb: 800,
      batteryPercent: 40,
      isCharging: false,
    }))).toBe('T0');
  });

  it('low battery while charging still allows T1', () => {
    expect(recommendTier(snap({
      availableMemoryMb: 2000,
      batteryPercent: 5,
      isCharging: true,
      hasWifi: false,
    }))).toBe('T1');
  });
});
