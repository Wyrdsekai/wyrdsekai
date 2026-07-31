/**
 * appModeStore — Zustand store for first-run mode selection.
 *
 * Persists the user's choice of local vs remote mode and their
 * chosen companion name. Read on app launch to determine the initial
 * navigation route.
 *
 * Storage is `secureStorage` (encrypted MMKV) — credentials and household
 * identity belong here. The legacy AsyncStorage values for these same keys
 * are deleted by `initSecureStorage()` on first cold start (hard cutover).
 */
import { create } from 'zustand';
import { secureStorage as AsyncStorage } from './secureStorage';
import type { Backing } from '../engine/mode/PhoneMode';

const KEY_MODE = '@wyrd_app_mode';
const KEY_COMPANION_NAME = '@wyrd_companion_name';
const KEY_HOME_NAME = '@wyrd_home_name';
const KEY_FIRST_RUN_COMPLETE = '@wyrd_first_run_complete';
const KEY_LAST_SOUL_SYNC_TIME = '@wyrd_last_soul_sync_time';
const KEY_SOUL_MANIFEST_VERSION = '@wyrd_soul_manifest_version';
const KEY_INFERENCE_URL = '@wyrd_inference_url';
const KEY_PAIRING_TOKEN = '@wyrd_pairing_token';
const KEY_HOUSEHOLD_ID = '@wyrd_household_id';
const KEY_HOUSEHOLD_NAME = '@wyrd_household_name';
const KEY_SERVER_DID = '@wyrd_server_did';
const KEY_NATS_URL = '@wyrd_nats_url';
const KEY_RELAY_URL = '@wyrd_relay_url';
const KEY_RELAY_TOKEN = '@wyrd_relay_token';
const KEY_AUTH_TOKEN = '@wyrd_auth_token';
const KEY_USER_ID = '@wyrd_user_id';
const KEY_USER_ROLE = '@wyrd_user_role';
const KEY_PREFERRED_BACKING = '@wyrd_preferred_backing';
const KEY_ON_DEVICE_MODEL_OPT_IN = '@wyrd_on_device_model_opt_in';

interface AppModeState {
  /**
   * The first mode axis, persisted since first run: does the phone run its own
   * node ('local') or is it a terminal onto a home zone ('remote')?
   *
   * This is HALF of the mode. It does not distinguish modes 2/3/4/5, and
   * reading it as if it did is the §0b defect. Use `resolvePhoneMode()` in
   * engine/mode/currentMode.ts for the actual mode.
   */
  mode: 'unset' | 'local' | 'remote';
  companionName: string;
  /** Persisted home room name. Defaults to 'Home'. */
  homeName: string;
  firstRunComplete: boolean;
  /** Whether loadFromStorage has finished. */
  loaded: boolean;

  /** Last successful soul sync time (epoch millis), or null if never synced. */
  lastSoulSyncTime: number | null;
  /** Last synced soul manifest version, or null if never synced. */
  soulManifestVersion: number | null;
  /** Saved inference endpoint URL, or null if not configured. */
  inferenceUrl: string | null;

  /** Pairing credentials from server pairing flow. */
  pairingToken: string | null;
  householdId: string | null;
  householdName: string | null;
  serverDid: string | null;
  natsUrl: string | null;
  relayUrl: string | null;
  relayToken: string | null;

  /** Auth credentials from login/register. */
  authToken: string | null;
  userId: string | null;
  userRole: string | null;

  /**
   * The second mode axis: when the phone runs its own node AND has a home
   * zone, does heavy work go to the household GPU (mode 4) or a cloud API
   * (mode 5)?
   *
   * The user's call, never inferred from hardware — a capable laptop-class
   * device may legitimately prefer either. Defaults to 'home': someone who
   * paired with a household generally wants to use it.
   */
  preferredBacking: Backing;

  /**
   * EXPERIMENTAL opt-in: run the companion's model on this device.
   *
   * Off by default. Current phone hardware cannot serve a 4B at reading speed,
   * and on iOS a 4B often cannot be loaded at all — so the shipped default is
   * mode 1 or 2, where the thinking happens on the household or a cloud API.
   * Someone with a heavily specced tablet can turn it on and get modes 3/4/5.
   */
  onDeviceModelOptIn: boolean;

  setLocalMode: (companionName: string) => Promise<void>;
  setRemoteMode: () => Promise<void>;
  /** Drop the home-zone relay leg → run local-only (keeps the local Study mirror). */
  disconnectHomeZone: (companionName: string) => Promise<void>;
  /** Mirrors KMP "Switch to remote server": drops local mode, keeps firstRunComplete=true,
   *  caller should navigate to FirstRunScreen for re-pairing. */
  resetToFirstRun: () => Promise<void>;
  loadFromStorage: () => Promise<void>;
  setLastSoulSync: (time: number, version: number) => void;
  setInferenceUrl: (url: string) => void;
  setHomeName: (name: string) => void;
  setPairingCredentials: (creds: {
    token: string;
    householdId: string;
    householdName: string;
    serverDid: string;
    natsUrl: string;
    serverUrl: string;
    relayUrl?: string | null;
    relayToken?: string | null;
  }) => void;
  setAuth: (token: string, userId: string, role: string) => void;
  clearAuth: () => void;
  /** Choose where heavy work goes when both a household and a cloud API exist. */
  setPreferredBacking: (backing: Backing) => Promise<void>;
  /** Turn the EXPERIMENTAL on-device model on or off. */
  setOnDeviceModelOptIn: (enabled: boolean) => Promise<void>;
}

export const useAppModeStore = create<AppModeState>((set) => ({
  mode: 'unset',
  companionName: 'Wyrd',
  homeName: 'Home',
  firstRunComplete: false,
  loaded: false,
  lastSoulSyncTime: null,
  soulManifestVersion: null,
  inferenceUrl: null,
  pairingToken: null,
  householdId: null,
  householdName: null,
  serverDid: null,
  natsUrl: null,
  relayUrl: null,
  relayToken: null,
  authToken: null,
  userId: null,
  userRole: null,

  preferredBacking: 'home',
  onDeviceModelOptIn: false,

  setOnDeviceModelOptIn: async (enabled: boolean) => {
    await AsyncStorage.setItem(KEY_ON_DEVICE_MODEL_OPT_IN, enabled ? 'true' : 'false');
    set({ onDeviceModelOptIn: enabled });
  },

  setPreferredBacking: async (backing: Backing) => {
    await AsyncStorage.setItem(KEY_PREFERRED_BACKING, backing);
    set({ preferredBacking: backing });
  },

  setLocalMode: async (companionName: string) => {
    const name = companionName.trim() || 'Wyrd';
    await AsyncStorage.setItem(KEY_MODE, 'local');
    await AsyncStorage.setItem(KEY_COMPANION_NAME, name);
    await AsyncStorage.setItem(KEY_FIRST_RUN_COMPLETE, 'true');
    set({ mode: 'local', companionName: name, firstRunComplete: true });
  },

  setRemoteMode: async () => {
    await AsyncStorage.setItem(KEY_MODE, 'remote');
    await AsyncStorage.setItem(KEY_FIRST_RUN_COMPLETE, 'true');
    set({ mode: 'remote', firstRunComplete: true });
  },

  // Cut the home-zone (relay) leg — the phone runs purely local afterwards. The
  // local Study is KEPT (it's the last-synced mirror of the home zone); only the
  // live connection is dropped, so nothing authored offline is lost.
  disconnectHomeZone: async (companionName: string) => {
    const name = companionName.trim() || 'Wyrd';
    await AsyncStorage.removeItem(KEY_RELAY_URL);
    await AsyncStorage.removeItem(KEY_NATS_URL);
    await AsyncStorage.removeItem(KEY_RELAY_TOKEN);
    await AsyncStorage.removeItem('@wyrd_zone_id');
    await AsyncStorage.setItem(KEY_MODE, 'local');
    await AsyncStorage.setItem(KEY_COMPANION_NAME, name);
    set({ mode: 'local', companionName: name, relayUrl: null, natsUrl: null, relayToken: null });
  },

  resetToFirstRun: async () => {
    await AsyncStorage.setItem(KEY_MODE, '');
    set({ mode: 'unset' });
  },

  setLastSoulSync: (time: number, version: number) => {
    set({ lastSoulSyncTime: time, soulManifestVersion: version });
    AsyncStorage.setItem(KEY_LAST_SOUL_SYNC_TIME, String(time)).catch(() => {});
    AsyncStorage.setItem(KEY_SOUL_MANIFEST_VERSION, String(version)).catch(() => {});
  },

  setInferenceUrl: (url: string) => {
    set({ inferenceUrl: url });
    AsyncStorage.setItem(KEY_INFERENCE_URL, url).catch(() => {});
  },

  setHomeName: (name: string) => {
    const trimmed = name.trim() || 'Home';
    set({ homeName: trimmed });
    AsyncStorage.setItem(KEY_HOME_NAME, trimmed).catch(() => {});
  },

  setPairingCredentials: (creds) => {
    set({
      pairingToken: creds.token,
      householdId: creds.householdId,
      householdName: creds.householdName,
      serverDid: creds.serverDid,
      natsUrl: creds.natsUrl,
      relayUrl: creds.relayUrl ?? null,
      relayToken: creds.relayToken ?? null,
    });
    AsyncStorage.setItem(KEY_PAIRING_TOKEN, creds.token).catch(() => {});
    AsyncStorage.setItem(KEY_HOUSEHOLD_ID, creds.householdId).catch(() => {});
    AsyncStorage.setItem(KEY_HOUSEHOLD_NAME, creds.householdName).catch(() => {});
    AsyncStorage.setItem(KEY_SERVER_DID, creds.serverDid).catch(() => {});
    AsyncStorage.setItem(KEY_NATS_URL, creds.natsUrl).catch(() => {});
    AsyncStorage.setItem(KEY_INFERENCE_URL, creds.serverUrl).catch(() => {});
    if (creds.relayUrl) AsyncStorage.setItem(KEY_RELAY_URL, creds.relayUrl).catch(() => {});
    if (creds.relayToken) AsyncStorage.setItem(KEY_RELAY_TOKEN, creds.relayToken).catch(() => {});
  },

  setAuth: (token: string, userId: string, role: string) => {
    set({ authToken: token, userId, userRole: role });
    AsyncStorage.setItem(KEY_AUTH_TOKEN, token).catch(() => {});
    AsyncStorage.setItem(KEY_USER_ID, userId).catch(() => {});
    AsyncStorage.setItem(KEY_USER_ROLE, role).catch(() => {});
  },

  clearAuth: () => {
    set({ authToken: null, userId: null, userRole: null });
    AsyncStorage.removeItem(KEY_AUTH_TOKEN).catch(() => {});
    AsyncStorage.removeItem(KEY_USER_ID).catch(() => {});
    AsyncStorage.removeItem(KEY_USER_ROLE).catch(() => {});
  },

  loadFromStorage: async () => {
    try {
      const [
        mode, name, homeName, complete, syncTime, manifestVer, inferUrl,
        pairToken, hhId, hhName, sDid, nUrl, rUrl, rToken,
        aToken, uId, uRole, backing, onDeviceOptIn,
      ] = await Promise.all([
        AsyncStorage.getItem(KEY_MODE),
        AsyncStorage.getItem(KEY_COMPANION_NAME),
        AsyncStorage.getItem(KEY_HOME_NAME),
        AsyncStorage.getItem(KEY_FIRST_RUN_COMPLETE),
        AsyncStorage.getItem(KEY_LAST_SOUL_SYNC_TIME),
        AsyncStorage.getItem(KEY_SOUL_MANIFEST_VERSION),
        AsyncStorage.getItem(KEY_INFERENCE_URL),
        AsyncStorage.getItem(KEY_PAIRING_TOKEN),
        AsyncStorage.getItem(KEY_HOUSEHOLD_ID),
        AsyncStorage.getItem(KEY_HOUSEHOLD_NAME),
        AsyncStorage.getItem(KEY_SERVER_DID),
        AsyncStorage.getItem(KEY_NATS_URL),
        AsyncStorage.getItem(KEY_RELAY_URL),
        AsyncStorage.getItem(KEY_RELAY_TOKEN),
        AsyncStorage.getItem(KEY_AUTH_TOKEN),
        AsyncStorage.getItem(KEY_USER_ID),
        AsyncStorage.getItem(KEY_USER_ROLE),
        AsyncStorage.getItem(KEY_PREFERRED_BACKING),
        AsyncStorage.getItem(KEY_ON_DEVICE_MODEL_OPT_IN),
      ]);
      set({
        mode: (mode as 'local' | 'remote') ?? 'unset',
        companionName: name ?? 'Wyrd',
        homeName: homeName ?? 'Home',
        firstRunComplete: complete === 'true',
        lastSoulSyncTime: syncTime ? Number(syncTime) : null,
        soulManifestVersion: manifestVer ? Number(manifestVer) : null,
        inferenceUrl: inferUrl ?? null,
        pairingToken: pairToken ?? null,
        householdId: hhId ?? null,
        householdName: hhName ?? null,
        serverDid: sDid ?? null,
        natsUrl: nUrl ?? null,
        relayUrl: rUrl ?? null,
        relayToken: rToken ?? null,
        authToken: aToken ?? null,
        userId: uId ?? null,
        userRole: uRole ?? null,
        // Anything unrecognised (or absent) means the user never chose, and
        // 'home' is the right default for someone who paired with a household.
        preferredBacking: backing === 'cloud' ? 'cloud' : 'home',
        // Anything but an explicit 'true' means off — an experimental feature
        // must never be enabled by a malformed or partial value.
        onDeviceModelOptIn: onDeviceOptIn === 'true',
        loaded: true,
      });
    } catch {
      // Storage read failed — treat as first run
      set({
        mode: 'unset',
        companionName: 'Wyrd',
        homeName: 'Home',
        firstRunComplete: false,
        lastSoulSyncTime: null,
        soulManifestVersion: null,
        inferenceUrl: null,
        pairingToken: null,
        householdId: null,
        householdName: null,
        serverDid: null,
        natsUrl: null,
        relayUrl: null,
        relayToken: null,
        authToken: null,
        userId: null,
        userRole: null,
        loaded: true,
      });
    }
  },
}));
