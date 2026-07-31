import { PlatformResourceProbe } from '../../../src/engine/tier/PlatformResourceProbe';
import type { ResourceSnapshot } from '../../../src/engine/tier/TierConfig';

describe('PlatformResourceProbe', () => {
  it('returns a valid snapshot with all required fields', () => {
    const probe = new PlatformResourceProbe();
    const snap: ResourceSnapshot = probe.snapshot();

    expect(snap).toHaveProperty('availableMemoryMb');
    expect(snap).toHaveProperty('totalMemoryMb');
    expect(snap).toHaveProperty('batteryPercent');
    expect(snap).toHaveProperty('isCharging');
    expect(snap).toHaveProperty('thermalState');
    expect(snap).toHaveProperty('hasWifi');
  });

  it('returns memory values in reasonable ranges', () => {
    const probe = new PlatformResourceProbe();
    const snap = probe.snapshot();

    // Default values (no native APIs available in Node test env)
    expect(snap.availableMemoryMb).toBeGreaterThan(0);
    expect(snap.totalMemoryMb).toBeGreaterThanOrEqual(snap.availableMemoryMb);
    expect(snap.availableMemoryMb).toBeLessThanOrEqual(32768); // 32GB upper bound
  });

  it('returns battery percent between 0 and 100', () => {
    const probe = new PlatformResourceProbe();
    const snap = probe.snapshot();

    expect(snap.batteryPercent).toBeGreaterThanOrEqual(0);
    expect(snap.batteryPercent).toBeLessThanOrEqual(100);
  });

  it('returns a valid thermal state', () => {
    const probe = new PlatformResourceProbe();
    const snap = probe.snapshot();

    expect(['NOMINAL', 'FAIR', 'SERIOUS', 'CRITICAL']).toContain(snap.thermalState);
  });

  it('accepts native resource overrides', () => {
    const probe = new PlatformResourceProbe();
    probe.updateNativeResources({
      availableMemoryMb: 6000,
      totalMemoryMb: 8000,
      thermalState: 'FAIR',
      hasWifi: false,
    });

    const snap = probe.snapshot();
    expect(snap.availableMemoryMb).toBe(6000);
    expect(snap.totalMemoryMb).toBe(8000);
    expect(snap.thermalState).toBe('FAIR');
    expect(snap.hasWifi).toBe(false);
  });

  it('accepts battery updates', () => {
    const probe = new PlatformResourceProbe();
    probe.updateBattery(0.45, true);

    const snap = probe.snapshot();
    expect(snap.batteryPercent).toBe(45);
    expect(snap.isCharging).toBe(true);
  });

  it('inference activity affects thermal heuristic', () => {
    const probe = new PlatformResourceProbe();
    // Low battery, not charging, inference active => SERIOUS
    probe.updateBattery(0.12, false);
    probe.setInferenceActive(true);

    const snap = probe.snapshot();
    expect(snap.thermalState).toBe('SERIOUS');
  });

  it('thermal stays NOMINAL when charging', () => {
    const probe = new PlatformResourceProbe();
    probe.updateBattery(0.12, true); // charging
    probe.setInferenceActive(true);

    // Charging overrides the battery-based heuristic (isCharging=true, so threshold not met)
    const snap = probe.snapshot();
    expect(snap.thermalState).toBe('NOMINAL');
  });

  it('returns consistent snapshots across multiple calls', () => {
    const probe = new PlatformResourceProbe();
    const snap1 = probe.snapshot();
    const snap2 = probe.snapshot();

    // Without state changes, snapshots should be identical
    expect(snap1.availableMemoryMb).toBe(snap2.availableMemoryMb);
    expect(snap1.batteryPercent).toBe(snap2.batteryPercent);
    expect(snap1.thermalState).toBe(snap2.thermalState);
  });
});
