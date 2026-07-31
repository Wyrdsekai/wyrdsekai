import type { ResourceProbe, ResourceSnapshot } from '../../src/engine/tier/TierConfig';
import { TierManager, type TierTransition, isPromotion, isDemotion } from '../../src/engine/tier/TierManager';

function makeProbe(overrides: Partial<ResourceSnapshot> = {}): ResourceProbe {
  return {
    snapshot: () => ({
      availableMemoryMb: 2000,
      totalMemoryMb: 4000,
      batteryPercent: 80,
      isCharging: false,
      thermalState: 'NOMINAL' as const,
      hasWifi: true,
      ...overrides,
    }),
  };
}

describe('TierManager', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('initialize sets initial tier', () => {
    const manager = new TierManager(makeProbe({ availableMemoryMb: 1500, hasWifi: false }));
    manager.initialize();
    expect(manager.currentTier).toBe('T1');
  });

  it('initialize with low resources sets T0', () => {
    const manager = new TierManager(makeProbe({ availableMemoryMb: 500 }));
    manager.initialize();
    expect(manager.currentTier).toBe('T0');
  });

  it('initialize with high resources sets T3', () => {
    const manager = new TierManager(makeProbe({
      availableMemoryMb: 4000, isCharging: true, hasWifi: true,
    }));
    manager.initialize();
    expect(manager.currentTier).toBe('T3');
  });

  it('forceTier overrides probe', () => {
    const manager = new TierManager(makeProbe({ availableMemoryMb: 500 }));
    manager.initialize();
    expect(manager.currentTier).toBe('T0');

    manager.forceTier('T2');
    expect(manager.currentTier).toBe('T2');
  });

  it('tier transitions emitted', () => {
    const manager = new TierManager(makeProbe({ availableMemoryMb: 1500, hasWifi: false }));
    const transitions: TierTransition[] = [];
    manager.onTransition(t => transitions.push(t));

    manager.initialize();
    // Initial transition T0 → T1
    expect(transitions.length).toBe(1);
    expect(transitions[0].from).toBe('T0');
    expect(transitions[0].to).toBe('T1');
    expect(transitions[0].reason).toBe('initial');

    manager.forceTier('T3');
    expect(transitions.length).toBe(2);
    expect(isPromotion(transitions[1])).toBe(true);

    manager.forceTier('T0');
    expect(transitions.length).toBe(3);
    expect(isDemotion(transitions[2])).toBe(true);
  });

  it('config updates with tier', () => {
    const manager = new TierManager(makeProbe());
    manager.initialize();

    manager.forceTier('T0');
    expect(manager.config.inferenceMode).toBe('REMOTE');
    expect(manager.config.maxRooms).toBe(0);

    manager.forceTier('T2');
    expect(manager.config.inferenceMode).toBe('LOCAL');
    expect(manager.config.maxRooms).toBe(4);
    expect(manager.config.betweenEnabled).toBe(true);
  });

  it('monitoring detects changes', () => {
    let currentMemory = 2000;
    const dynamicProbe: ResourceProbe = {
      snapshot: () => ({
        availableMemoryMb: currentMemory,
        totalMemoryMb: 4000,
        batteryPercent: 80,
        isCharging: false,
        thermalState: 'NOMINAL' as const,
        hasWifi: true,
      }),
    };

    const manager = new TierManager(dynamicProbe, 1000);
    const transitions: TierTransition[] = [];
    manager.onTransition(t => transitions.push(t));

    manager.initialize();
    manager.startMonitoring();

    // Simulate memory drop
    currentMemory = 500;
    jest.advanceTimersByTime(1500);
    expect(transitions.length).toBe(2);
    expect(transitions[1].to).toBe('T0');

    // Simulate memory recovery
    currentMemory = 3000;
    jest.advanceTimersByTime(1500);
    expect(transitions.length).toBe(3);
    expect(transitions[2].to).toBe('T2');

    manager.stopMonitoring();
  });

  it('no transition emitted when tier unchanged', () => {
    const manager = new TierManager(
      makeProbe({ availableMemoryMb: 1500, hasWifi: false }),
      1000,
    );
    const transitions: TierTransition[] = [];
    manager.onTransition(t => transitions.push(t));

    manager.initialize();
    manager.startMonitoring();

    // Advance several intervals — probe returns same resources
    jest.advanceTimersByTime(5000);
    expect(transitions.length).toBe(1); // Only the initial transition

    manager.stopMonitoring();
  });

  it('unsubscribe stops receiving transitions', () => {
    const manager = new TierManager(makeProbe());
    const transitions: TierTransition[] = [];
    const unsub = manager.onTransition(t => transitions.push(t));

    manager.initialize();
    expect(transitions.length).toBe(1);

    unsub();
    manager.forceTier('T3');
    expect(transitions.length).toBe(1); // No new transition received
  });
});
