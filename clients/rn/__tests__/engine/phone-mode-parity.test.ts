/**
 * Mode selection, driven by the SHARED parity contract.
 *
 * Cases live in clients/parity/parity.json → phoneMode so RN and KMP are held
 * to one table.
 */
import parity from '../../../parity/parity.json';
import { decideMode, runsLocalNode, wantsOnDeviceModel } from '../../src/engine/mode/PhoneMode';
import type { Backing, PhoneMode } from '../../src/engine/mode/PhoneMode';

interface Case {
  name: string;
  onDeviceModelViable: boolean;
  wantsOwnNode: boolean;
  hasHomeZone: boolean;
  hasCloudKey: boolean;
  hasOnDeviceModel: boolean;
  preferredBacking: Backing;
  expectMode: PhoneMode | null;
}

const CASES = (parity as { phoneMode: { cases: Case[] } }).phoneMode.cases;

describe('phone mode selection (shared parity contract)', () => {
  it('the contract has cases', () => {
    expect(CASES.length).toBeGreaterThan(0);
  });

  for (const c of CASES) {
    it(c.name, () => {
      const decision = decideMode({
        onDeviceModelViable: c.onDeviceModelViable,
        wantsOwnNode: c.wantsOwnNode,
        hasHomeZone: c.hasHomeZone,
        hasCloudKey: c.hasCloudKey,
        hasOnDeviceModel: c.hasOnDeviceModel,
        preferredBacking: c.preferredBacking,
      });
      expect(decision.mode).toBe(c.expectMode);
      // An undecided state must explain itself — it becomes user-facing copy.
      expect(decision.reason.length).toBeGreaterThan(0);
    });
  }

  it('only mode 1 skips the local node', () => {
    expect(runsLocalNode(1)).toBe(false);
    for (const m of [2, 3, 4, 5] as PhoneMode[]) {
      expect(runsLocalNode(m)).toBe(true);
    }
  });

  it('only the experimental modes download a model', () => {
    // Modes 1 and 2 are the defaults precisely BECAUSE they need nothing on
    // the device. Downloading for either spends a user's storage and battery
    // on something that never gets asked a question.
    expect(wantsOnDeviceModel(1)).toBe(false);
    expect(wantsOnDeviceModel(2)).toBe(false);
    for (const m of [3, 4, 5] as PhoneMode[]) {
      expect(wantsOnDeviceModel(m)).toBe(true);
    }
  });

  it('the default reaches only modes 1 and 2', () => {
    // The safety property in one assertion: with viability off, no combination
    // of inputs can put a model on the phone.
    for (const wantsOwnNode of [true, false]) {
      for (const hasHomeZone of [true, false]) {
        for (const hasCloudKey of [true, false]) {
          for (const hasOnDeviceModel of [true, false]) {
            const d = decideMode({
              onDeviceModelViable: false,
              wantsOwnNode, hasHomeZone, hasCloudKey, hasOnDeviceModel,
              preferredBacking: 'home',
            });
            expect([null, 1, 2]).toContain(d.mode);
          }
        }
      }
    }
  });

  it('"has a home zone" alone never decides the mode', () => {
    // The §0b defect in one assertion: same home zone, different products.
    const base = {
      onDeviceModelViable: true, hasHomeZone: true, hasCloudKey: false, hasOnDeviceModel: true,
    } as const;
    const terminal = decideMode({ ...base, wantsOwnNode: false, preferredBacking: 'home' });
    const ownNode = decideMode({ ...base, wantsOwnNode: true, preferredBacking: 'home' });
    expect(terminal.mode).toBe(1);
    expect(ownNode.mode).toBe(4);
  });
});
