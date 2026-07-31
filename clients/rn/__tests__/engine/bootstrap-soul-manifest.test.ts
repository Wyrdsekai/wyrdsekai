import {
  BOOTSTRAP_SOUL_MANIFEST,
  BOOTSTRAP_DID,
  isBootstrapManifest,
} from '../../src/engine/soul/BootstrapSoulManifest';
import { restoreProfile, restoreVitality } from '../../src/engine/soul/LocalForge';
import { serializeManifest, deserializeManifest } from '../../src/engine/soul/SoulManifest';

describe('BootstrapSoulManifest', () => {
  it('has bootstrap DID', () => {
    expect(BOOTSTRAP_SOUL_MANIFEST.did).toBe(BOOTSTRAP_DID);
  });

  it('has resident identity', () => {
    expect(BOOTSTRAP_SOUL_MANIFEST.residentIdentity).toBeTruthy();
    expect(BOOTSTRAP_SOUL_MANIFEST.residentIdentity).toContain('Wyrd');
  });

  it('has fragments', () => {
    const fragments = BOOTSTRAP_SOUL_MANIFEST.fragments ?? [];
    expect(fragments.length).toBeGreaterThanOrEqual(5);
    expect(fragments.some(f => f.category === 'personality')).toBe(true);
    expect(fragments.some(f => f.category === 'values')).toBe(true);
    expect(fragments.some(f => f.category === 'style')).toBe(true);
    expect(fragments.some(f => f.category === 'memory')).toBe(true);
  });

  it('has formative fragment', () => {
    const fragments = BOOTSTRAP_SOUL_MANIFEST.fragments ?? [];
    expect(fragments.some(f => f.formative)).toBe(true);
  });

  it('has genome', () => {
    expect(BOOTSTRAP_SOUL_MANIFEST.genome).toBeDefined();
    expect(BOOTSTRAP_SOUL_MANIFEST.genome!.name).toBe('empathic');
    expect(Object.keys(BOOTSTRAP_SOUL_MANIFEST.genome!.sensitivity ?? {}).length).toBeGreaterThan(0);
  });

  it('has calibration examples', () => {
    const cal = BOOTSTRAP_SOUL_MANIFEST.mirrorCalibration ?? [];
    expect(cal.length).toBeGreaterThanOrEqual(3);
    expect(cal.some(c => c.includes('grief'))).toBe(true);
    expect(cal.some(c => c.includes('manipulative'))).toBe(true);
  });

  it('has vitality tanks', () => {
    const tanks = BOOTSTRAP_SOUL_MANIFEST.vitalityTanks ?? {};
    expect(Object.keys(tanks).length).toBeGreaterThanOrEqual(12);
    expect(tanks.energy).toBeDefined();
    expect(tanks.valence).toBeDefined();
    expect(tanks.safety).toBeDefined();
  });

  it('uses phone retrieval k=1', () => {
    expect(BOOTSTRAP_SOUL_MANIFEST.retrievalK).toBe(1);
  });

  it('isBootstrapManifest detects bootstrap', () => {
    expect(isBootstrapManifest(BOOTSTRAP_SOUL_MANIFEST)).toBe(true);
  });

  it('isBootstrapManifest rejects foreign manifest', () => {
    const foreign = { ...BOOTSTRAP_SOUL_MANIFEST, did: 'did:key:someone-else' };
    expect(isBootstrapManifest(foreign)).toBe(false);
  });

  it('serializes and deserializes', () => {
    const json = serializeManifest(BOOTSTRAP_SOUL_MANIFEST);
    const restored = deserializeManifest(json);
    expect(restored.did).toBe(BOOTSTRAP_SOUL_MANIFEST.did);
    expect(restored.residentIdentity).toBe(BOOTSTRAP_SOUL_MANIFEST.residentIdentity);
    expect(restored.fragments?.length).toBe(BOOTSTRAP_SOUL_MANIFEST.fragments?.length);
    expect(restored.genome?.name).toBe(BOOTSTRAP_SOUL_MANIFEST.genome?.name);
  });

  it('restores profile from bootstrap', () => {
    const profile = restoreProfile(BOOTSTRAP_SOUL_MANIFEST);
    expect(profile.name).toBe('Wyrd');
    expect(profile.entityId).toBe('companion-wyrd');
    expect(profile.systemPrompt).toContain('Wyrd');
  });

  it('restores vitality from bootstrap', () => {
    const vitality = restoreVitality(BOOTSTRAP_SOUL_MANIFEST);
    expect(vitality.energy).toBe(1.0);
    expect(vitality.contextBudget).toBe(0.5);
  });

  it('all fragments have keywords', () => {
    for (const fragment of BOOTSTRAP_SOUL_MANIFEST.fragments ?? []) {
      expect((fragment.keywords ?? []).length).toBeGreaterThan(0);
    }
  });
});
