/**
 * TemperamentSeed parity — the TS port must match the Java ground truth exactly.
 *
 * `fixtures/temperament-parity.json` is GENERATED FROM THE JAVA implementation
 * (core/.../soul/TemperamentSeed.java + VoiceProfile.fromTemperament) — see the
 * variance-work notes 2026-07-17. If Java changes, regenerate the fixture; a
 * mismatch here means a phone-born particular would differ in kind from a
 * server-born one, which is the exact clone/drift bug this port exists to close.
 */
import * as fs from 'fs';
import * as path from 'path';
import {
  makeSeed, randomSeed, voiceRegister, nearestPreset, isViable,
  serializeSeed, deserializeSeed, PRESETS, toArray,
} from '../../src/engine/soul/TemperamentSeed';

interface FixtureEntry {
  name: string;
  axes: number[];
  cadence: string;
  habit: string;
  warmth: string;
  nearestPreset: string;
  nearestDistance: number;
  viable: boolean;
}

const fixture: FixtureEntry[] = JSON.parse(
  fs.readFileSync(path.join(__dirname, '..', 'fixtures', 'temperament-parity.json'), 'utf8'),
);

function seedOf(axes: number[]) {
  return makeSeed({
    sociability: axes[0], curiosity: axes[1], vigilance: axes[2],
    industry: axes[3], restlessness: axes[4], warmth: axes[5],
  });
}

describe('TemperamentSeed — Java parity (fixture-driven)', () => {
  it('has fixture entries', () => {
    expect(fixture.length).toBeGreaterThanOrEqual(16);
  });

  it.each(fixture.map((e) => [e.name, e] as const))(
    'matches Java for %s',
    (_name, e) => {
      const s = seedOf(e.axes);
      const reg = voiceRegister(s);
      expect(reg.cadence).toBe(e.cadence);
      expect(reg.habit).toBe(e.habit);
      expect(reg.warmth).toBe(e.warmth);
      const np = nearestPreset(s);
      expect(np.preset).toBe(e.nearestPreset);
      expect(np.distance).toBeCloseTo(e.nearestDistance, 5);
      expect(isViable(s)).toBe(e.viable);
    },
  );
});

describe('TemperamentSeed — sampling contract (mirrors Java random())', () => {
  it('samples within [0.10, 0.90] and always viable', () => {
    for (let i = 0; i < 500; i++) {
      const s = randomSeed();
      for (const v of toArray(s)) {
        expect(v).toBeGreaterThanOrEqual(0.1 - 1e-9);
        expect(v).toBeLessThanOrEqual(0.9 + 1e-9);
      }
      expect(isViable(s)).toBe(true);
    }
  });

  it('two births are distinct particulars (clone-factory regression)', () => {
    const a = randomSeed();
    const b = randomSeed();
    // Probability of a collision on 6 continuous axes is ~0; identical seeds
    // means the RNG plumbing regressed to a constant — the exact old bug.
    expect(toArray(a)).not.toEqual(toArray(b));
  });

  it('presets are viable anchors and label as themselves', () => {
    for (const [name, p] of Object.entries(PRESETS)) {
      expect(isViable(p)).toBe(true);
      expect(nearestPreset(p).preset).toBe(name);
      expect(nearestPreset(p).distance).toBeCloseTo(0, 9);
    }
  });
});

describe('TemperamentSeed — persistence round-trip', () => {
  it('serialize/deserialize preserves the particular', () => {
    const s = randomSeed();
    const back = deserializeSeed(serializeSeed(s));
    expect(back).not.toBeNull();
    for (let i = 0; i < 6; i++) {
      expect(toArray(back!)[i]).toBeCloseTo(toArray(s)[i], 5);
    }
  });

  it('rejects garbage rather than birthing a corrupt particular', () => {
    expect(deserializeSeed(null)).toBeNull();
    expect(deserializeSeed('')).toBeNull();
    expect(deserializeSeed('not json')).toBeNull();
    expect(deserializeSeed('[1,2]')).toBeNull();
    expect(deserializeSeed('["a","b","c","d","e","f"]')).toBeNull();
  });
});
