/**
 * D6: keys, tokens and pins in platform secure
 * storage. The encrypted store's key lives in the Keychain (expo-secure-store);
 * an older build's key is moved there out of the plain bootstrap file once, and
 * plaintext AsyncStorage copies are moved in and erased.
 */
type Files = Map<string, Map<string, string>>;
let files: Files;
let keychain: Map<string, string>;
let asyncStore: Map<string, string>;
let keychainOptions: unknown[];
let opened: Array<{ id: string; key?: string }>;

function mockModules(opts?: { keychain?: boolean }) {
  jest.doMock('react-native-mmkv', () => ({
    MMKV: class {
      private readonly m: Map<string, string>;
      readonly encryptionKey?: string;
      constructor(o: { id: string; encryptionKey?: string }) {
        if (!files.has(o.id)) files.set(o.id, new Map());
        this.m = files.get(o.id)!;
        this.encryptionKey = o.encryptionKey;
        opened.push({ id: o.id, key: o.encryptionKey });
      }
      getString(k: string) { return this.m.get(k); }
      set(k: string, v: string) { this.m.set(k, String(v)); }
      delete(k: string) { this.m.delete(k); }
      clearAll() { this.m.clear(); }
      getAllKeys() { return [...this.m.keys()]; }
    },
  }));
  if (opts?.keychain === false) {
    jest.doMock('expo-secure-store', () => { throw new Error('not linked'); });
  } else {
    jest.doMock('expo-secure-store', () => ({
      AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY: 'AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY',
      getItem: (k: string, o: unknown) => { keychainOptions.push(o); return keychain.get(k) ?? null; },
      setItem: (k: string, v: string, o: unknown) => { keychainOptions.push(o); keychain.set(k, v); },
    }));
  }
  jest.doMock('@react-native-async-storage/async-storage', () => ({
    __esModule: true,
    default: {
      getItem: async (k: string) => asyncStore.get(k) ?? null,
      getAllKeys: async () => [...asyncStore.keys()],
      multiRemove: async (ks: string[]) => { for (const k of ks) asyncStore.delete(k); },
    },
  }));
  jest.doMock('expo-file-system', () => { throw new Error('no files in tests'); });
}

function load(opts?: { keychain?: boolean }) {
  let mod: typeof import('../../src/state/secureStorage') | undefined;
  jest.isolateModules(() => {
    mockModules(opts);
    mod = require('../../src/state/secureStorage');
  });
  return mod!;
}

beforeEach(() => {
  files = new Map();
  keychain = new Map();
  asyncStore = new Map();
  keychainOptions = [];
  opened = [];
  jest.resetModules();
  jest.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('secureStorage key in the Keychain', () => {
  it('a fresh install makes its key in the Keychain and nothing in the plain bootstrap file', () => {
    const { getOrCreateEncryptionKey, KEYCHAIN_KEY_NAME } = load();
    const key = getOrCreateEncryptionKey();
    expect(key).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(keychain.get(KEYCHAIN_KEY_NAME)).toBe(key);
    expect(files.get('wyrd-secure-bootstrap')?.size ?? 0).toBe(0);
    expect(keychainOptions).toContainEqual({ keychainAccessible: 'AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY' });
    // Stable across starts.
    expect(load().getOrCreateEncryptionKey()).toBe(key);
  });

  it("moves an older build's key out of the plain bootstrap file, keeping the data readable", () => {
    files.set('wyrd-secure-bootstrap', new Map([['__enc_key_v1', 'OLD-KEY']]));
    const { getOrCreateEncryptionKey, KEYCHAIN_KEY_NAME } = load();
    expect(getOrCreateEncryptionKey()).toBe('OLD-KEY');
    expect(keychain.get(KEYCHAIN_KEY_NAME)).toBe('OLD-KEY');
    expect(files.get('wyrd-secure-bootstrap')!.size).toBe(0);
  });

  it('erases a leftover plain copy even when the Keychain already holds the key', () => {
    files.set('wyrd-secure-bootstrap', new Map([['__enc_key_v1', 'OLD-KEY']]));
    keychain.set('wyrd_secure_store_key_v2', 'OLD-KEY');
    expect(load().getOrCreateEncryptionKey()).toBe('OLD-KEY');
    expect(files.get('wyrd-secure-bootstrap')!.size).toBe(0);
  });

  it('opens the store with the Keychain key', async () => {
    const { secureStorage, KEYCHAIN_KEY_NAME } = load();
    await secureStorage.setItem('@wyrd_mcp_session_token', 't');
    expect(files.get('wyrd-secure')!.get('@wyrd_mcp_session_token')).toBe('t');
    expect(keychain.get(KEYCHAIN_KEY_NAME)).toBeTruthy();
    expect(opened).toContainEqual({ id: 'wyrd-secure', key: keychain.get(KEYCHAIN_KEY_NAME) });
    expect(await secureStorage.keys()).toEqual(['@wyrd_mcp_session_token']);
  });

  it('a build without the Keychain module still starts, on the old bootstrap key', () => {
    const errors = jest.spyOn(console, 'error').mockImplementation(() => {});
    files.set('wyrd-secure-bootstrap', new Map([['__enc_key_v1', 'OLD-KEY']]));
    expect(load({ keychain: false }).getOrCreateEncryptionKey()).toBe('OLD-KEY');
    expect(errors).toHaveBeenCalled();
    errors.mockRestore();
  });
});

describe('initSecureStorage moves plaintext AsyncStorage copies in and erases them', () => {
  it('moves tokens, passwords and pins, keeps what the store already has, erases the rest', async () => {
    asyncStore.set('@wyrd_mcp_password', 'pw');
    asyncStore.set('@wyrd_relay_token', 'relay-tok');
    asyncStore.set('@wyrd_trust_relay.example', '{"host":"relay.example"}');
    asyncStore.set('@wyrd_zone_pw_home', 'zone-pw');
    asyncStore.set('@wyrd_mcp_session_token', 'stale');
    asyncStore.set('@wyrd_soul:did', 'user data stays');
    const { initSecureStorage, secureStorage } = load();
    await secureStorage.setItem('@wyrd_mcp_session_token', 'current');
    await initSecureStorage();

    expect(await secureStorage.getItem('@wyrd_mcp_password')).toBe('pw');
    expect(await secureStorage.getItem('@wyrd_relay_token')).toBe('relay-tok');
    expect(await secureStorage.getItem('@wyrd_trust_relay.example')).toBe('{"host":"relay.example"}');
    expect(await secureStorage.getItem('@wyrd_zone_pw_home')).toBe('zone-pw');
    expect(await secureStorage.getItem('@wyrd_mcp_session_token')).toBe('current');
    expect([...asyncStore.keys()]).toEqual(['@wyrd_soul:did']);
  });
});
