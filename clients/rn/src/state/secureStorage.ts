/**
 * secureStorage — encrypted, MMKV-backed drop-in for AsyncStorage for the
 * subset of phone state that we don't want sitting in plaintext.
 *
 * What's stored here (credentials + identity, not user data):
 *   • auth/MCP tokens, pairing tokens, relay tokens, session creds
 *   • household identity (DID, NATS URL, household ID/name)
 *   • TLS trust pins (HouseholdTrust)
 *
 * Everything else stays on AsyncStorage — the soul manifest, event journal,
 * vitality state, and Study notes are persistent user data, not secrets, and
 * the encrypted-store overhead would not buy anything there.
 *
 * # Encryption key
 *
 * MMKV's `encryptionKey` is used as an AES-CFB key directly. Since 0.5.0 the
 * key lives in the platform's secure storage — the iOS Keychain / Android
 * Keystore-backed store, via expo-secure-store — readable after the first
 * unlock and only on this device (it is not in backups, so a backup of the
 * MMKV file alone cannot be read). D6.
 *
 * Before 0.5.0 the key sat in a separate *unencrypted* MMKV bootstrap file
 * next to the data. On the first start of 0.5.0 that key is moved into the
 * Keychain and erased from the bootstrap file (the data stays readable; nobody
 * has to pair again).
 *
 * # Legacy AsyncStorage copies
 *
 * Tokens, passwords and pins that older builds wrote to plain AsyncStorage are
 * copied into this store once and then erased from AsyncStorage.
 */

import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';
import { MMKV } from 'react-native-mmkv';
import { toBase64Url } from '../crypto/bytes';
import { randomBytes } from '../crypto/random';
// NOTE: react-native-fs is dynamically required inside initSecureStorage so
// its top-level `new NativeEventEmitter(NativeModules.RNFSManager)` does not
// crash the entire JS bundle on platforms where the RNFS native module isn't
// registered (iOS New Architecture, RN 0.83 — RNFS 2.20.0 hasn't shipped a
// TurboModule spec). The seed-import is an e2e-test affordance; production
// users hit the Welcome flow regardless.

const BOOTSTRAP_ID = 'wyrd-secure-bootstrap';
const STORE_ID = 'wyrd-secure';
/** Where builds before 0.5.0 kept the key, in the plain bootstrap file. */
const KEY_ENCRYPTION_KEY = '__enc_key_v1';
/** The key's name in the Keychain / Keystore-backed store. */
export const KEYCHAIN_KEY_NAME = 'wyrd_secure_store_key_v2';

interface KeychainModule {
  getItem(key: string, options?: object): string | null;
  setItem(key: string, value: string, options?: object): void;
  AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY: unknown;
}

/** expo-secure-store, or null where it is not linked (web, tests). */
function keychain(): KeychainModule | null {
  try {
    const mod = require('expo-secure-store') as KeychainModule;
    return typeof mod?.getItem === 'function' ? mod : null;
  } catch {
    return null;
  }
}

// Legacy AsyncStorage keys we used to own (pre-secureStorage). On first
// secureStorage init we remove these — see `migrateLegacyOnce` below.
// MUST stay in sync with the const KEY_* in appModeStore.ts + any other
// callers that read AsyncStorage directly (seed_phone_session.sh too).
const LEGACY_KEYS_TO_DROP = [
  '@wyrd_app_mode',
  '@wyrd_companion_name',
  '@wyrd_home_name',
  '@wyrd_first_run_complete',
  '@wyrd_last_soul_sync_time',
  '@wyrd_soul_manifest_version',
  '@wyrd_inference_url',
  '@wyrd_pairing_token',
  '@wyrd_household_id',
  '@wyrd_household_name',
  '@wyrd_server_did',
  '@wyrd_nats_url',
  '@wyrd_relay_url',
  '@wyrd_relay_token',
  '@wyrd_auth_token',
  '@wyrd_user_id',
  '@wyrd_user_role',
  '@wyrd_mcp_username',
  '@wyrd_mcp_password',
  '@wyrd_mcp_session_token',
  '@wyrd_server_url',
  // Identity/topology keys touched by StandaloneNodeContext.
  // Added 2026-05-11 as part of completing the v1→v2 migration —
  // these were missed in v1 because StandaloneNodeContext + SettingsScreen
  // still read AsyncStorage directly, so the "drop after migrate" step
  // never applied to them. Now that those readers move to secureStorage,
  // include them so legacy users get migrated cleanly.
  '@wyrd_node_id',
  '@wyrd_companion_did',
  '@wyrd_between_url',
  '@wyrd_token',
  // SettingsScreen owned keys — API keys are sensitive material and belong
  // in encrypted storage. Migrated alongside the StandaloneNodeContext sweep.
  '@wyrd_api_key',
  '@wyrd_api_provider',
  '@wyrd_api_base_url',
  '@wyrd_debug_mode',
  // Phone-local first-run flag — keeping it adjacent to the other
  // first_run_complete-style state. Treat as part of the same lifecycle bag.
  '@wyrd_starter_provisioned',
  // held relays list + zone bank (JSON blobs). Per-zone
  // passwords (@wyrd_zone_pw_*) are matched by prefix in initSecureStorage,
  // alongside the @wyrd_trust_* pins.
  '@wyrd_held_relays',
  '@wyrd_zone_bank',
];

let storeInstance: MMKV | null = null;
let migrationDone = false;

function newEncryptionKey(): string {
  // 32 bytes from the platform CSPRNG as base64url ≈ 43 chars. randomBytes
  // throws when there is no secure source: a guessable key is no key.
  return toBase64Url(randomBytes(32));
}

/**
 * The store's key: from the Keychain; moved there from the plain bootstrap
 * file of an older build (once); or made new. Without the secure-storage
 * module (a build that did not link it) the old bootstrap file is used with a
 * loud error, so the app still starts — that is a broken build, not a mode.
 */
export function getOrCreateEncryptionKey(): string {
  const bootstrap = new MMKV({ id: BOOTSTRAP_ID });
  const legacy = bootstrap.getString(KEY_ENCRYPTION_KEY);
  const kc = keychain();
  if (!kc) {
    // eslint-disable-next-line no-console
    console.error('[secureStorage] expo-secure-store is not linked — the store key is NOT in the Keychain');
    if (legacy) return legacy;
    const key = newEncryptionKey();
    bootstrap.set(KEY_ENCRYPTION_KEY, key);
    return key;
  }
  const opts = { keychainAccessible: kc.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY };
  let key = kc.getItem(KEYCHAIN_KEY_NAME, opts);
  if (!key) {
    // Keep the old key (the data was written with it) but move it; else a new one.
    key = legacy ?? newEncryptionKey();
    kc.setItem(KEYCHAIN_KEY_NAME, key, opts);
  }
  // Only once the Keychain holds it: erase the plain copy.
  if (legacy) {
    bootstrap.delete(KEY_ENCRYPTION_KEY);
    bootstrap.clearAll();
  }
  return key;
}

function getStore(): MMKV {
  if (storeInstance) return storeInstance;
  // react-native-mmkv throws "'encryptionKey' is not supported on Web!" — the
  // web backend is localStorage, which can't be encrypted at rest anyway. On
  // web, fall back to a plain (unencrypted) store so the app still boots;
  // native (Android/iOS) keeps the AES-CFB encrypted store. (preferencesStore
  // and householdStore already branch on Platform.OS === 'web' the same way.)
  storeInstance =
    Platform.OS === 'web'
      ? new MMKV({ id: STORE_ID })
      : new MMKV({ id: STORE_ID, encryptionKey: getOrCreateEncryptionKey() });
  return storeInstance;
}

/**
 * Move the legacy plaintext AsyncStorage entries for the keys we now own into
 * this store, then erase them from AsyncStorage. Called once per cold start
 * (cheap to re-run: nothing is left to move after the first time). Values
 * already in this store win over the old copies.
 *
 * The e2e probe scripts seed credentials through a seed file (step 1) or by
 * writing to AsyncStorage/RKStorage (step 2); both land here.
 */
export async function initSecureStorage(): Promise<void> {
  if (migrationDone) return;
  migrationDone = true;
  const store = getStore();
  try {
    // 1. e2e seed-file import (preferred for tests).
    //
    // The probe runner writes a JSON file at
    //   /data/data/<pkg>/files/wyrd-seed.json
    // because the AsyncStorage RKStorage backend silently drops keys
    // it doesn't read during boot (verified by isolation test
    // 2026-05-11: sqlite3 inserts 11 rows, only 5 survive after
    // app launch). Bypassing AsyncStorage entirely is the only
    // reliable way to seed. KMP uses the same pattern with
    // wyrdsekai_prefs_seed.xml.
    //
    // Format: a flat JSON object whose keys map 1:1 to MMKV keys.
    // After import we delete the file so it doesn't override
    // user state on subsequent launches.
    // Use expo-file-system (works on iOS New Arch + Android). RNFS was the
    // previous backend but doesn't ship a TurboModule spec, so on iOS New Arch
    // its top-level `new NativeEventEmitter(NativeModules.RNFSManager)` throws
    // Invariant Violation at bundle load. expo-file-system is already a
    // transitive dep of Expo and is Fabric-compatible.
    try {
      const { File, Paths } = require('expo-file-system');
      const seedFile = new File(Paths.document, 'wyrd-seed.json');
      if (seedFile.exists) {
        const raw = await seedFile.text();
        const parsed = JSON.parse(raw);
        let imported = 0;
        if (parsed && typeof parsed === 'object') {
          for (const [k, v] of Object.entries(parsed)) {
            if (typeof v === 'string') { store.set(k, v); imported++; }
            else if (typeof v === 'boolean') { store.set(k, v); imported++; }
            else if (typeof v === 'number') { store.set(k, v); imported++; }
          }
        }
        // eslint-disable-next-line no-console
        console.warn('[secureStorage] seed-imported', imported, 'keys');
        try { seedFile.delete(); } catch { /* idempotent */ }
      }
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn('[secureStorage] seed-import unavailable', String(e));
    }

    // 2. AsyncStorage→MMKV migration (always-on, no-op if values absent).
    // Bundle is built with --dev false so __DEV__ is false even in e2e
    // test builds; gating on __DEV__ silently broke the e2e seed flow.
    // Every reader of these keys now goes through secureStorage, so the
    // plain AsyncStorage copies are erased once they are copied (D6).
    const allKeys = await AsyncStorage.getAllKeys();
    const legacy = [
      ...LEGACY_KEYS_TO_DROP,
      ...allKeys.filter((k) => k.startsWith('@wyrd_trust_') || k.startsWith('@wyrd_zone_pw_')),
    ].filter((k) => allKeys.includes(k));
    for (const k of legacy) {
      const v = await AsyncStorage.getItem(k);
      if (v != null && store.getString(k) == null) {
        store.set(k, v);
      }
    }
    if (legacy.length > 0) await AsyncStorage.multiRemove(legacy);
  } catch {
    // Best-effort — not fatal if the legacy store is already gone.
  }
}

/**
 * AsyncStorage-compatible async API. Backed by encrypted MMKV.
 * Async to keep the call sites identical; MMKV itself is sync.
 */
export const secureStorage = {
  async getItem(key: string): Promise<string | null> {
    const v = getStore().getString(key);
    return v == null ? null : v;
  },
  async setItem(key: string, value: string): Promise<void> {
    getStore().set(key, value);
  },
  async removeItem(key: string): Promise<void> {
    getStore().delete(key);
  },
  async clear(): Promise<void> {
    getStore().clearAll();
  },
  async keys(): Promise<string[]> {
    return getStore().getAllKeys();
  },
  // Sync helpers for call sites that don't want the await dance.
  syncGet(key: string): string | null {
    const v = getStore().getString(key);
    return v == null ? null : v;
  },
  syncSet(key: string, value: string): void {
    getStore().set(key, value);
  },
  syncDelete(key: string): void {
    getStore().delete(key);
  },
};
