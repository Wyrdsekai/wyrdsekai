/**
 * Resolving and APPLYING the phone mode.
 *
 * PhoneMode.ts is covered by the shared parity table. This file covers the
 * seam either side of it: reading the five inputs out of real app state, and
 * the one behaviour the decision actually changes — who owns the single remote
 * inference slot.
 *
 */
jest.mock('../../src/state/secureStorage', () => {
  const store = new Map<string, string>();
  return {
    __esModule: true,
    secureStorage: {
      getItem: jest.fn(async (key: string) => store.get(key) ?? null),
      setItem: jest.fn(async (key: string, value: string) => { store.set(key, value); }),
      removeItem: jest.fn(async (key: string) => { store.delete(key); }),
    },
    initSecureStorage: jest.fn(async () => {}),
    _store: store,
    _clear: () => store.clear(),
  };
});

import { useAppModeStore } from '../../src/state/appModeStore';
import {
  modeInputsFrom,
  collectModeInputs,
  resolvePhoneMode,
  applyModeToRouter,
  availableBackings,
  modeLabel,
  onDeviceModelViable,
} from '../../src/engine/mode/currentMode';
import { decideMode } from '../../src/engine/mode/PhoneMode';
import { InferenceRouter } from '../../src/inference/InferenceRouter';
import { LlamaService } from '../../src/inference/LlamaService';

import parity from '../../../parity/parity.json';
import type { ModeInputs } from '../../src/engine/mode/PhoneMode';
import type { RawModeState } from '../../src/engine/mode/currentMode';

const SecureStorageMock = require('../../src/state/secureStorage');
const secureStorage = SecureStorageMock.secureStorage;

/** A router whose local backend is never loaded — remote-slot logic only. */
function newRouter(): InferenceRouter {
  const llama = { isLoaded: () => false } as unknown as LlamaService;
  return new InferenceRouter(llama);
}

interface RawCase { name: string; raw: RawModeState; expect: ModeInputs }
const RAW_CASES = (parity as { modeInputs: { cases: RawCase[] } }).modeInputs.cases;

describe('modeInputsFrom (shared parity contract)', () => {
  it('the contract has cases', () => {
    expect(RAW_CASES.length).toBeGreaterThan(0);
  });

  for (const c of RAW_CASES) {
    it(c.name, () => {
      expect(modeInputsFrom(c.raw)).toEqual(c.expect);
    });
  }
});

describe('collectModeInputs', () => {
  beforeEach(() => {
    SecureStorageMock._clear();
    useAppModeStore.setState({
      mode: 'unset',
      relayUrl: null,
      householdId: null,
      preferredBacking: 'home',
      onDeviceModelOptIn: false,
    });
  });

  it('reads wantsOwnNode from the app-mode enum', async () => {
    useAppModeStore.setState({ mode: 'local' });
    expect((await collectModeInputs({ hasOnDeviceModel: false })).wantsOwnNode).toBe(true);
    useAppModeStore.setState({ mode: 'remote' });
    expect((await collectModeInputs({ hasOnDeviceModel: false })).wantsOwnNode).toBe(false);
  });

  it('first-run (mode unset) has not asked for a local node', async () => {
    expect((await collectModeInputs({ hasOnDeviceModel: false })).wantsOwnNode).toBe(false);
  });

  it('a zone id alone counts as a home zone', async () => {
    await secureStorage.setItem('@wyrd_zone_id', 'testzone');
    expect((await collectModeInputs({ hasOnDeviceModel: false })).hasHomeZone).toBe(true);
  });

  it('a relay URL alone counts as a home zone', async () => {
    await secureStorage.setItem('@wyrd_relay_url', 'wss://relay.example.org:4443');
    expect((await collectModeInputs({ hasOnDeviceModel: false })).hasHomeZone).toBe(true);
  });

  it('an API key WITHOUT a provider is not a usable cloud key', async () => {
    // There would be no URL to send it to — the request goes out unaddressed.
    await secureStorage.setItem('@wyrd_api_key', 'sk-test');
    expect((await collectModeInputs({ hasOnDeviceModel: false })).hasCloudKey).toBe(false);
    await secureStorage.setItem('@wyrd_api_provider', 'anthropic');
    expect((await collectModeInputs({ hasOnDeviceModel: false })).hasCloudKey).toBe(true);
  });

  it('carries the persisted backing preference through', async () => {
    useAppModeStore.setState({ preferredBacking: 'cloud' });
    expect((await collectModeInputs({ hasOnDeviceModel: false })).preferredBacking).toBe('cloud');
  });

  it('still records the own-node PREFERENCE from an invite pairing', async () => {
    // The invite path calls setLocalMode, so the preference is genuinely "my
    // own node". That is preserved here and is what the experimental gate
    // acts on — the gate withholds the mode, it does not rewrite the wish.
    useAppModeStore.setState({ mode: 'local' });
    await secureStorage.setItem('@wyrd_zone_id', 'testzone');
    const inputs = await collectModeInputs({ hasOnDeviceModel: true });
    expect(inputs.wantsOwnNode).toBe(true);
    expect(inputs.hasHomeZone).toBe(true);
    // ...and with the gate closed, that preference resolves to a terminal.
    expect(decideMode(inputs).mode).toBe(1);
  });
});

describe('the experimental gate', () => {
  beforeEach(() => {
    SecureStorageMock._clear();
    useAppModeStore.setState({
      mode: 'local', relayUrl: null, householdId: null, preferredBacking: 'home',
      onDeviceModelOptIn: false,
    });
  });

  it('by default an invite-paired phone is a terminal, not mode 4', async () => {
    // The shipped default: own-node preference plus a home zone used to mean
    // mode 4 and a 2.5GB download. Phones cannot serve that at reading speed.
    await secureStorage.setItem('@wyrd_zone_id', 'testzone');
    const d = await resolvePhoneMode({ hasOnDeviceModel: false });
    expect(d.mode).toBe(1);
  });

  it('by default a downloaded model does NOT promote the phone', async () => {
    // Having the file is not permission to rely on it.
    await secureStorage.setItem('@wyrd_zone_id', 'testzone');
    const d = await resolvePhoneMode({ hasOnDeviceModel: true });
    expect(d.mode).toBe(1);
  });

  it('opting in unlocks mode 4', async () => {
    useAppModeStore.setState({ onDeviceModelOptIn: true });
    await secureStorage.setItem('@wyrd_zone_id', 'testzone');
    const d = await resolvePhoneMode({ hasOnDeviceModel: true });
    expect(d.mode).toBe(4);
  });

  it('by default a phone with only a cloud key is mode 2', async () => {
    await secureStorage.setItem('@wyrd_api_key', 'sk-test');
    await secureStorage.setItem('@wyrd_api_provider', 'anthropic');
    const d = await resolvePhoneMode({ hasOnDeviceModel: false });
    expect(d.mode).toBe(2);
  });

  it('by default a phone with nothing configured says so, and says it is experimental', async () => {
    const d = await resolvePhoneMode({ hasOnDeviceModel: true });
    expect(d.mode).toBeNull();
    expect(d.reason).toMatch(/experimental/i);
  });

  it('viability is decided by the policy seam, not read raw', () => {
    // The future edit is here and nowhere else: when throughput can be
    // measured it joins this function, and decideMode is untouched.
    expect(onDeviceModelViable({ optIn: false })).toBe(false);
    expect(onDeviceModelViable({ optIn: true })).toBe(true);
  });
});

describe('availableBackings', () => {
  const base = {
    onDeviceModelViable: true,
    wantsOwnNode: true,
    hasHomeZone: false,
    hasCloudKey: false,
    hasOnDeviceModel: true,
    preferredBacking: 'home' as const,
  };

  it('offers nothing when neither backing exists', () => {
    expect(availableBackings(base)).toEqual([]);
  });

  it('offers only what is actually configured', () => {
    expect(availableBackings({ ...base, hasHomeZone: true })).toEqual(['home']);
    expect(availableBackings({ ...base, hasCloudKey: true })).toEqual(['cloud']);
  });

  it('offers both only when both can answer', () => {
    expect(availableBackings({ ...base, hasHomeZone: true, hasCloudKey: true }))
      .toEqual(['home', 'cloud']);
  });
});

describe('applyModeToRouter — the remote slot', () => {
  let router: InferenceRouter;
  beforeEach(() => { router = newRouter(); });

  it('pins the remote slot in mode 5 only', () => {
    for (const m of [1, 2, 3, 4] as const) {
      applyModeToRouter(m, router);
      expect(router.isRemotePinned()).toBe(false);
    }
    applyModeToRouter(5, router);
    expect(router.isRemotePinned()).toBe(true);
  });

  it('an undecided mode does not pin', () => {
    applyModeToRouter(null, router);
    expect(router.isRemotePinned()).toBe(false);
  });

  it('re-applying a different mode RELEASES a previous pin', () => {
    // Switching 5 → 4 in settings has to actually let the household back in.
    applyModeToRouter(5, router);
    applyModeToRouter(4, router);
    expect(router.isRemotePinned()).toBe(false);
  });
});

describe('setRemoteUrlIfBetter', () => {
  let router: InferenceRouter;
  beforeEach(() => { router = newRouter(); });

  it('takes a discovered endpoint when unpinned', () => {
    router.setRemoteUrl('https://api.anthropic.com');
    expect(router.setRemoteUrlIfBetter('http://198.51.100.10:8200')).toBe(true);
    expect(router.getRemoteUrl()).toBe('http://198.51.100.10:8200');
  });

  it('DECLINES and leaves the cloud endpoint intact when pinned', () => {
    // The shipped bug: a mode-5 phone opening the app at home had its cloud
    // API endpoint replaced by LAN discovery, and the caller persisted the
    // replacement — so it survived the next launch too.
    router.setRemoteUrl('https://api.anthropic.com');
    applyModeToRouter(5, router);
    expect(router.setRemoteUrlIfBetter('http://198.51.100.10:8200')).toBe(false);
    expect(router.getRemoteUrl()).toBe('https://api.anthropic.com');
  });

  it('takes the endpoint again once the pin is released', () => {
    router.setRemoteUrl('https://api.anthropic.com');
    applyModeToRouter(5, router);
    expect(router.setRemoteUrlIfBetter('http://198.51.100.10:8200')).toBe(false);
    applyModeToRouter(4, router);
    expect(router.setRemoteUrlIfBetter('http://198.51.100.10:8200')).toBe(true);
    expect(router.getRemoteUrl()).toBe('http://198.51.100.10:8200');
  });
});

describe('modeLabel', () => {
  it('names every mode and says so plainly when there is none', () => {
    const seen = new Set<string>();
    for (const m of [1, 2, 3, 4, 5] as const) {
      const label = modeLabel(m);
      expect(label.length).toBeGreaterThan(0);
      seen.add(label);
    }
    // Distinct labels — two modes sharing copy is how 1 and 4 got conflated.
    expect(seen.size).toBe(5);
    expect(modeLabel(null)).toMatch(/setup/i);
  });
});
