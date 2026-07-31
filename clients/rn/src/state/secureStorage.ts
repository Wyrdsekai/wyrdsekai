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
 * # Encryption key bootstrap
 *
 * MMKV's `encryptionKey` is used as an AES-CFB key directly. The key itself
 * needs to live somewhere. v1 (this commit): we generate a 32-byte random key
 * once on first run and stash it in a separate *unencrypted* MMKV bootstrap
 * file. This means an attacker with `adb pull` on a rooted phone can still
 * grab the key — but it locks down the steady-state on-disk artifact
 * (encrypted MMKV file), defeats casual XML inspection, and defeats backups
 * that exclude the bootstrap file. v2 follow-up: replace the bootstrap with
 * a Keystore-backed random via `react-native-keychain` so the key never
 * leaves Android Keystore / iOS Keychain.
 *
 * # Hard cutover
 *
 * On first import, we wipe the legacy AsyncStorage keys for everything we
 * now own. This means existing logged-in users will be kicked back to the
 * pairing/login screen after the upgrade. SPEC: encryption work, 2026-05-11.
 */

import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';
import { MMKV } from 'react-native-mmkv';
// NOTE: react-native-fs is dynamically required inside initSecureStorage so
// its top-level `new NativeEventEmitter(NativeModules.RNFSManager)` does not
// crash the entire JS bundle on platforms where the RNFS native module isn't
// registered (iOS New Architecture, RN 0.83 — RNFS 2.20.0 hasn't shipped a
// TurboModule spec). The seed-import is an e2e-test affordance; production
// users hit the Welcome flow regardless.

const BOOTSTRAP_ID = 'wyrd-secure-bootstrap';
const STORE_ID = 'wyrd-secure';
const KEY_ENCRYPTION_KEY = '__enc_key_v1';

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

function getOrCreateEncryptionKey(): string {
  const bootstrap = new MMKV({ id: BOOTSTRAP_ID });
  let key = bootstrap.getString(KEY_ENCRYPTION_KEY);
  if (key) return key;
  // 32 bytes of random as base64-no-pad ≈ 43 chars.
  const bytes = new Uint8Array(32);
  if (typeof globalThis !== 'undefined' && (globalThis as any).crypto?.getRandomValues) {
    (globalThis as any).crypto.getRandomValues(bytes);
  } else {
    // Last-resort: Math.random is NOT crypto-safe; we log loudly and proceed
    // so the app remains functional, but treat this as a critical bug.
    // eslint-disable-next-line no-console
    console.error('[secureStorage] crypto.getRandomValues unavailable — falling back to Math.random');
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  // base64url encode without padding
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  key = btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  bootstrap.set(KEY_ENCRYPTION_KEY, key);
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
 * Drop the legacy plaintext AsyncStorage entries for the keys we now own.
 * Called once per cold start (cheap to re-run — AsyncStorage.removeItem
 * is idempotent).
 *
 * Production: hard cutover. Any tokens/pins that were sitting in
 * AsyncStorage are gone after this; the user re-pairs / re-logs in.
 *
 * Debug builds (`__DEV__`): one-time migration. The e2e probe scripts seed
 * credentials by writing to AsyncStorage/RKStorage; we copy them into the
 * encrypted store BEFORE deleting so the new APK respects test fixtures.
 * Tradeoff: a malicious debug user could plant credentials this way, but
 * debug builds aren't signed for release distribution anyway.
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
    for (const k of LEGACY_KEYS_TO_DROP) {
      const v = await AsyncStorage.getItem(k);
      if (v != null && store.getString(k) == null) {
        store.set(k, v);
      }
    }
    const allKeys = await AsyncStorage.getAllKeys();
    const trustKeys = allKeys.filter((k) => k.startsWith('@wyrd_trust_'));
    for (const k of trustKeys) {
      const v = await AsyncStorage.getItem(k);
      if (v != null && store.getString(k) == null) {
        store.set(k, v);
      }
    }
    // NOTE: we do NOT wipe legacy AsyncStorage values. Some code paths
    // (StandaloneNodeContext, SettingsScreen) still read AsyncStorage
    // directly. Until those are migrated to secureStorage too, we'd
    // break them. The encryption win for v1 is "credentials now also
    // live in encrypted MMKV alongside AsyncStorage", not full wipe.
    // Real hard cutover lands in a follow-up after all readers move.
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
