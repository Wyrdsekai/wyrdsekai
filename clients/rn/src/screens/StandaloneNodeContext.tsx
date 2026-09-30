/**
 * StandaloneNodeContext — React context managing a local PhoneNode lifecycle.
 *
 * Creates a PhoneNode backed by AsyncStorage persistence plus the
 * InferenceRouter from InferenceContext. Subscribes to PhoneNodeEvents
 * and feeds standaloneNodeStore.
 *
 * On mount, restores saved state (prose, room, tier) from AsyncStorage so
 * the user sees content immediately while PhoneNode starts. On unmount and
 * periodically (every 30s), persists current state.
 *
 * Usage: wrap StandaloneRoomScreen in <StandaloneNodeProvider>.
 */
import React, { createContext, useContext, useEffect, useRef } from 'react';
import { Platform } from 'react-native';
import { PhoneNode } from '../engine/PhoneNode';
import type { PhoneNodeEvent } from '../engine/PhoneNode';
import { AsyncStorageEventJournal } from '../engine/persistence/AsyncStorageEventJournal';
import { AsyncStorageVitalityStore } from '../engine/persistence/AsyncStorageVitalityStore';
import { AsyncStorageSoulManifestStore } from '../engine/persistence/AsyncStorageSoulManifestStore';
import { useStandaloneNodeStore } from '../state/standaloneNodeStore';
import { useInference } from '../inference/InferenceContext';
import type { BetweenClient } from '../engine/between/BetweenClient';
import { discoverInference, bestEndpoint } from '../engine/discovery/InferenceDiscovery';
import { BOOTSTRAP_DID } from '../engine/soul/BootstrapSoulManifest';
import { createNamedBootstrap } from '../engine/soul/NamedBootstrapManifest';
import { EquipmentService } from '../engine/item/EquipmentService';
import { SkillUsageTracker } from '../engine/agent/SkillUsageTracker';
import { CompanionCapabilityBridge } from '../engine/agent/CompanionCapabilityBridge';
import { AsyncStorageItemStore } from '../engine/persistence/LocalItemStore';
import { provisionStarterKit } from '../engine/item/StarterKitProvisioner';
import { SoulSyncManager } from '../engine/soul/SoulSyncManager';
import { OfflineQueue } from '../engine/agent/OfflineQueue';
import { SqliteStudyStore } from '../engine/study/SqliteStudyStore';
import { useAppModeStore } from '../state/appModeStore';
import { secureStorage } from '../state/secureStorage';
import { RelayTunnelHolder } from '../engine/transit';

/**
 * Credential / topology keys live in `secureStorage` (MMKV, encrypted).
 * User data (event journal, soul manifest, vitality history, items) stays
 * in `resolvedStorage` (AsyncStorage / RKStorage) — that boundary is
 * intentional: encrypt secrets, leave bulky user content on the fast
 * SQLite-backed store. See secureStorage.ts header.
 *
 * Before this split (2026-05-11), all reads went through AsyncStorage
 * directly. Once initSecureStorage started populating MMKV from JSON-seed
 * imports, the StandaloneNodeContext probe-and-login flow silently broke
 * for fresh installs (sUrl was null because AsyncStorage had nothing —
 * the seed only landed in MMKV).
 */
const credStorage = secureStorage;

/** Minimal AsyncStorage interface — resolved at runtime. */
interface AsyncStorageLike {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

let resolvedStorage: AsyncStorageLike | null = null;
try {
  const mod = require('@react-native-async-storage/async-storage');
  resolvedStorage = mod.default ?? mod;
} catch {
  // Fallback: in-memory map (tests, web)
  const map = new Map<string, string>();
  resolvedStorage = {
    getItem: async (k) => map.get(k) ?? null,
    setItem: async (k, v) => { map.set(k, v); },
    removeItem: async (k) => { map.delete(k); },
  };
}

/** Interval for periodic state persistence (ms). */
const PERSIST_INTERVAL_MS = 30_000;

/** How long the home network may take to answer before the phone uses the relay. */
const HOME_BUS_CONNECT_MS = 6_000;

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timed out after ${ms} ms`)), ms);
    p.then(
      (v) => { clearTimeout(t); resolve(v); },
      (e) => { clearTimeout(t); reject(e); },
    );
  });
}

/** Say `text` in the prose stream once per phone (a flag in secure storage). */
async function noticeOnce(key: string, text: string, say: (text: string) => void): Promise<void> {
  const flag = `@wyrd_notice_${key}`;
  if (await credStorage.getItem(flag)) return;
  await credStorage.setItem(flag, '1');
  say(text);
}

/**
 * Try to connect to household Between network via NATS.
 *
 * On success, wires Between into PhoneNode via setBetween() as this phone's
 * own bus login (`busUser`): Study sync under that name; the home refuses the
 * rest of the LAN machinery to a phone login, so it is not started.
 *
 * Only on the home network: the home bus (the invite's `home_bus`, see
 * resolveHomeBus) pinned to the household CA from the invite, with this phone's
 * own home-bus credentials ( W2, D1/D3). Tried briefly
 * away from home it simply does not answer. Never plain ws://, and not through
 * the relay: Between there is plain pub/sub that the relay and the zone's other
 * phones can read (see planBetweenLeg). A phone paired before 0.5.0 is told once
 * how to pair again for the home network. On the bus the phone uses only what
 * its login may (Study sync addressed to it — PhoneNode.setBetween `busUser`).
 *
 * Returns the BetweenClient (NatsBetweenAdapter) on success, or null if not
 * configured or unavailable.
 */
async function connectBetweenIfConfigured(
  storage: AsyncStorageLike,
  node: PhoneNode,
  say: (text: string) => void,
): Promise<BetweenClient | null> {
  const zoneId = await credStorage.getItem('@wyrd_zone_id');
  const relayUrl = await credStorage.getItem('@wyrd_relay_url');
  const onRelay = !!relayUrl && /^wss?:\/\//.test(relayUrl);
  const { useZoneBankStore } = await import('../state/zoneBankStore');
  if (!useZoneBankStore.getState().loaded) await useZoneBankStore.getState().loadFromStorage();
  const { planBetweenLeg } = await import('../server/homeBus');
  const plan = await planBetweenLeg(zoneId, await credStorage.getItem('@wyrd_between_url'), onRelay);
  if (plan.notice) {
    const { securityText } = await import('../security/securityText');
    await noticeOnce(plan.notice, securityText()[plan.notice], say);
  }
  const bus = plan.bus;
  if (!bus) return null;

  const nodeId = (await credStorage.getItem('@wyrd_node_id')) ?? `rn-${Date.now()}`;
  const householdId = (await credStorage.getItem('@wyrd_household_id')) ?? 'default';
  const companionDid = (await credStorage.getItem('@wyrd_companion_did')) ?? `did:wyrd:companion:${nodeId}`;
  const serverUrl = await credStorage.getItem('@wyrd_server_url');
  const deviceToken = await credStorage.getItem('@wyrd_pairing_token');
  const accountUserId = await credStorage.getItem('@wyrd_user_id');
  const sessionToken = await credStorage.getItem('@wyrd_mcp_session_token');

  // Dynamic import — avoids bundling nats.ws on native platforms
  const { NatsBetweenAdapter } = await import('../engine/between/NatsBetweenAdapter');
  const { pinHomeCa } = await import('../server/HouseholdTrust');
  await pinHomeCa(bus.url, bus.homeCaFp);
  const client = new NatsBetweenAdapter();
  try {
    await withTimeout(client.connect(bus.url, { user: bus.user, pass: bus.pass }), HOME_BUS_CONNECT_MS);
  } catch {
    // The home answered and refused this phone's bus login (the phone was
    // removed, or its login changed): say once how to pair again. Anything
    // else (away from home, timeout) stays quiet.
    if (client.loginRefused) {
      const { securityText } = await import('../security/securityText');
      await noticeOnce('lanPairAgain', securityText().lanPairAgain, say);
    }
    await client.disconnect().catch(() => {});
    return null;
  }
  node.setBetween({
    client, nodeId, householdId, companionDid, serverUrl, deviceToken, zoneId, accountUserId, sessionToken,
    viaRelay: false, busUser: bus.user,
  });
  return client;
}

interface StandaloneNodeServices {
  phoneNode: PhoneNode;
}

const StandaloneCtx = createContext<StandaloneNodeServices | null>(null);

/** Hook to access the standalone PhoneNode. Must be within StandaloneNodeProvider. */
export const useStandaloneNode = (): StandaloneNodeServices => {
  const ctx = useContext(StandaloneCtx);
  if (!ctx) throw new Error('useStandaloneNode must be used within StandaloneNodeProvider');
  return ctx;
};

export const StandaloneNodeProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const { inferenceRouter } = useInference();
  const store = useStandaloneNodeStore;

  const nodeRef = useRef<PhoneNode | null>(null);
  const betweenRef = useRef<BetweenClient | null>(null);
  const storage = resolvedStorage!;

  // Build PhoneNode once
  if (!nodeRef.current) {
    const journal = new AsyncStorageEventJournal(storage);
    const vitalityStore = new AsyncStorageVitalityStore(storage);
    const soulManifestStore = new AsyncStorageSoulManifestStore(storage);

    // Create TierManager with platform resource probe for dynamic tier detection
    const { PlatformResourceProbe } = require('../engine/tier/PlatformResourceProbe');
    const { TierManager } = require('../engine/tier/TierManager');
    const probe = new PlatformResourceProbe();
    const tierManager = new TierManager(probe);
    tierManager.initialize();
    tierManager.startMonitoring();

    const node = new PhoneNode(
      journal,
      vitalityStore,
      inferenceRouter,
      tierManager,
      soulManifestStore,
    );

    // Wire Study persistence (expo-sqlite + FTS5 for proper full-text search)
    node.studyStore = new SqliteStudyStore();

    nodeRef.current = node;
  }

  useEffect(() => {
    const node = nodeRef.current!;

    // Wire server credentials BEFORE node starts (must complete before node.start()).
    // Credentials live in secureStorage (MMKV) — populated either by the JSON-seed
    // e2e harness, by appModeStore.setAuth during normal pairing, or by the
    // Welcome "I have an API key" path.
    const wireCredentials = async () => {
      const sUrl = await credStorage.getItem('@wyrd_inference_url');
      const dToken = await credStorage.getItem('@wyrd_pairing_token');
      const authToken = await credStorage.getItem('@wyrd_auth_token');
      const mcpSession = await credStorage.getItem('@wyrd_mcp_session_token');
      if (sUrl) node.serverUrl = sUrl;
      if (dToken) node.deviceToken = dToken;
      // The signed-in person's session: room visits use it (the person arrives
      // as themselves); the device token only when no one is signed in.
      node.sessionToken = mcpSession ?? authToken ?? null;

      // Cloud-API path (Welcome "I have an API key"): restore the saved
      // provider+key and apply to inferenceRouter so the companion's first
      // inference call ships with the correct auth header. Without this,
      // the wizard accepts the key but the request fires without
      // x-api-key/Bearer and the API returns 401 → companion has no reply.
      const apiProvider = await credStorage.getItem('@wyrd_api_provider');
      const apiKey = await credStorage.getItem('@wyrd_api_key');
      if (apiProvider && apiKey && sUrl) {
        const authType = apiProvider === 'anthropic' ? 'x-api-key' : 'bearer';
        inferenceRouter.setRemoteUrl(sUrl);
        inferenceRouter.setRemoteAuth(authType, apiKey);
        // Cloud OpenAI-compat endpoints require a `model` field — without it
        // Anthropic returns 400 "model: Field required" and the companion
        // never replies. A saved `@wyrd_api_model` (Settings) wins; otherwise
        // fall back to a sane default per provider.
        const savedModel = await credStorage.getItem('@wyrd_api_model');
        const defaultModel =
          apiProvider === 'anthropic' ? 'claude-sonnet-4-6'
          : apiProvider === 'openai' ? 'gpt-4o-mini'
          : apiProvider === 'openrouter' ? 'anthropic/claude-sonnet-4'
          : null;
        inferenceRouter.setRemoteModel(savedModel ?? defaultModel);
      }
    };

    // Probe the configured URL: if it hosts a wyrdsekai server, auto-register
    // an anonymous phone account and log in via MCP so tell/library/journal
    // can route through the server's REST API (with full cross-zone +
    // library_card scripted item access). Idempotent — re-uses saved creds
    // when present.
    //
    // URL preference: explicit `@wyrd_server_url` first (lets the user point
    // inference at llama-server :8200 while the server REST lives at :7070),
    // then `@wyrd_inference_url` as a single-URL fallback for users whose
    // server proxies `/v1/chat/completions` itself.

    const setupServerClient = async (): Promise<void> => {
     // Diagnostic step trace. Failure paths always surface a "setup-error:"
     // prose line so a user / tester can see what broke. Success steps log to
     // console (visible via Maestro logcat / dev tools) but NOT to prose, so
     // production users don't see a "[setup] start ..." spam wall. Override
     // by setting WYRD_SETUP_VERBOSE_PROSE=1 in the build env if you want the
     // chatter back for debugging.
     const verboseProse = typeof process !== 'undefined'
       && process.env?.WYRD_SETUP_VERBOSE_PROSE === '1';
     const step = (msg: string) => {
       // eslint-disable-next-line no-console
       console.log(`[setup] ${msg}`);
       const isError = msg.startsWith('setup-error');
       if (isError || verboseProse) {
         try { store.getState().addProse({ speaker: 'system', text: `[setup] ${msg}` }); } catch {}
       }
     };
     try {
      step('start');
      // Mode 1 (home zone, warm login THIS session): the Servers screen already
      // stood up the authenticated relay tunnel (RelayTunnelHolder) from the
      // openZone client and persisted the session token. This local-node relay
      // client is then redundant — and worse, its zone-discovery / probe over the
      // SHARED relay_phone NATS account raises a harmless `Authorization Violation`
      // that surfaces as a scary `setup-error` prose line. Skip it; the tunnel
      // carries the real session. (Cold restart: the holder is empty, so this
      // still runs and re-establishes the tunnel itself — see the tunnel block
      // below.)
      try {
        const { RelayTunnelHolder } = await import('../engine/transit');
        if (RelayTunnelHolder.get()) {
          step('relay tunnel already up (Servers login) — skipping redundant local-node relay client');
          return;
        }
      } catch { /* transit import optional — fall through to full setup */ }
      // the relay comes only from a wyrdphone:// invite:
      // the relay the invite saved (@wyrd_relay_url + its credentials), else a
      // relay the bank holds for this zone (also from an invite). It is never
      // derived from the home's address and never taken from a pairing reply.
      // An older build's LAN pairing could leave a non-websocket address under
      // @wyrd_relay_url (the zone leg, e.g. nats://host:4222) — only ws/wss
      // forms are usable, and the bank's relay replaces such a value.
      const savedRelayUrl = await credStorage.getItem('@wyrd_relay_url');
      let inviteRelay: { wsUrl: string; user: string | null; pass: string | null } | null =
        savedRelayUrl && /^wss?:\/\//.test(savedRelayUrl)
          ? {
              wsUrl: savedRelayUrl,
              user: await credStorage.getItem('@wyrd_nats_user'),
              pass: await credStorage.getItem('@wyrd_nats_pass'),
            }
          : null;
      if (!inviteRelay) {
        const cachedZone = await credStorage.getItem('@wyrd_zone_id');
        const { useZoneBankStore: bankStore } = await import('../state/zoneBankStore');
        if (!bankStore.getState().loaded) await bankStore.getState().loadFromStorage();
        const held = cachedZone ? bankStore.getState().relaysForZone(cachedZone)[0] : undefined;
        if (held) inviteRelay = { wsUrl: held.wsUrl, user: held.natsUser, pass: held.natsPass };
      }
      const inviteRelayUrl = inviteRelay?.wsUrl ?? null;
      // Tier-1: when a home-zone (relay) leg IS configured but we can't bring it
      // up, tell the user plainly instead of silently rendering a local Study.
      // Pure-local users (no relay leg) stay silent — falling to the local Study
      // is their normal path, not a fault.
      const reportHomeZoneUnreachable = (reason: string) => {
        if (!inviteRelayUrl) return;
        // eslint-disable-next-line no-console
        console.warn(`[setup] home zone unreachable — ${reason}`);
        try {
          store.getState().addProse({
            speaker: 'system',
            text: "Couldn't reach your home zone — check your connection and try again. "
              + 'Your Study is running locally in the meantime.',
          });
        } catch { /* store may not be ready */ }
      };
      const explicit = await credStorage.getItem('@wyrd_server_url');
      const sUrl = explicit
        ?? (await credStorage.getItem('@wyrd_inference_url'))
        ?? (inviteRelayUrl ? inviteRelayUrl.replace(/^wss:/, 'https:').replace(/^ws:/, 'http:') : null);
      if (!sUrl) { step('no server URL — stay local'); return; }
      // Pure API-key cloud mode: no real server (@wyrd_server_url) and no relay
      // invite — sUrl fell back to the cloud inference endpoint
      // (@wyrd_inference_url, e.g. https://api.anthropic.com). Inference goes
      // DIRECT to the provider via the InferenceRouter (wired in wireCredentials),
      // so there's nothing to attach here. Skip the leg entirely in this mode.
      if (!explicit && !inviteRelayUrl && (await credStorage.getItem('@wyrd_api_key'))) {
        step('API-key cloud mode — inference is direct to the provider; skipping relay/server leg');
        return;
      }
      // No relay from an invite: the home is reached on the home network only
      // (the home bus, connectBetweenIfConfigured). Nothing to set up here.
      if (!inviteRelayUrl) {
        step('no relay from an invite — the home is reached on the home network only');
        return;
      }
      step(`serverUrl=${sUrl}`);

      // iOS note (2026-05-11): household CA must be installed in the system
      // trust store (Settings → General → VPN & Device Management on a real
      // device, or `xcrun simctl keychain add-root-cert` on Simulator) for
      // HTTPS to https://relay-node to validate. With CA installed, NSURLSession
      // accepts the chain via system trust — no native pinning module needed
      // for the e2e probe. Production iOS still wants #732 for
      // cryptographic-pin enforcement (defence against compromised CA), but
      // for now system trust + the outer try/catch is enough.
      if (Platform.OS === 'ios' && sUrl.startsWith('https://')) {
        // eslint-disable-next-line no-console
        console.warn('[setupServerClient] iOS https — relying on system trust store for', sUrl);
        // Skip probeAndTrust: the JS-level TOFU flow tries to fetch
        // http://host/ca.crt which we don't need (CA already installed
        // out-of-band) and the legacy native trust call expects the
        // Android HouseholdTrust module to exist.
      }

      // System-trust probe for `https://` URLs (a relay with a public
      // certificate). Pins for self-signed relays and for the home come only
      // from invites (restoreNativePins at start); there is no prompt to trust
      // an unknown certificate (, D6).
      if (sUrl.startsWith('https://') && Platform.OS !== 'ios') {
        try {
          const { probeAndTrust } = await import('../server/HouseholdTrust');
          await probeAndTrust(sUrl, {});
        } catch (err) {
          // probeAndTrust threw: no system trust for this host.
          // Surface but don't crash — fall through; the ServerClient probe
          // will fail and we'll stay in local-only mode.
          const msg = err instanceof Error ? err.message : String(err);
          store.getState().addProse({
            speaker: 'system',
            text: `TLS trust setup skipped (${msg}). Cross-zone features stay local.`,
          });
        }
      }

      // NATS is THE transport. The phone
      // connects to the invite's relay, probes the zone via
      // `wyrd.zone.{zone}.auth.status`, registers/logs in via the same
      // NATS subjects, and uses NATS for all subsequent calls. No HTTP.
      // The invite's relay URL is used verbatim (it carries the real port).
      const natsRelayUrl = inviteRelayUrl;
      // "home" is reserved (collides with the
      // furnishing concept). WyrdConfig.zoneId() refuses it on
      // the server. Phones learn their zone from the server's auth.* reply
      // and cache it as `@wyrd_zone_id`. We start with the cached value if
      // present; otherwise probe with a temporary subject scope of `_unknown`
      // and rely on the auth.status reply to tell us the actual zone (then
      // reconnect with the right scope).
      let natsZoneId =
        (await credStorage.getItem('@wyrd_zone_id')) ||
        (typeof process !== 'undefined' && process.env?.WYRD_NATS_ZONE_ID) ||
        '_unknown';
      // Relay transport credentials come from the INVITE (wyrdphone:// payload
      // carries nats_user/nats_password). There is deliberately no compiled-in
      // fallback: relays now generate their own infrastructure secrets on first
      // run (OSS hardening 2026-07-25), so a baked default could only ever be
      // (a) wrong, or (b) a shipped secret for every relay that kept it.
      const natsUser = inviteRelay?.user ?? null;
      const natsPass = inviteRelay?.pass ?? null;
      if (!natsUser || !natsPass) {
        step('setup-error: no relay credentials — scan or paste your invite again');
        reportHomeZoneUnreachable('relay credentials missing from this device');
        return;
      }
      const savedUser = await credStorage.getItem('@wyrd_mcp_username');
      const savedPass = await credStorage.getItem('@wyrd_mcp_password');
      const savedTok = await credStorage.getItem('@wyrd_mcp_session_token');
      step(`wss=${natsRelayUrl} zone=${natsZoneId} user=${natsUser} savedUser=${savedUser ? 'YES' : 'no'}`);

      step('importing NatsServerClient');
      const { NatsServerClient } = await import('../server/NatsServerClient');
      step('NatsServerClient imported');
      // The home's tunnel key from its invite: every request and the tunnel
      // are sealed to it ( W3).
      const { useZoneBankStore } = await import('../state/zoneBankStore');
      if (!useZoneBankStore.getState().loaded) await useZoneBankStore.getState().loadFromStorage();
      const homeKeyFor = (zone: string) => useZoneBankStore.getState().getZoneTrust(zone)?.zk ?? null;
      const nc = new NatsServerClient({
        relayUrl: natsRelayUrl,
        zoneId: natsZoneId,
        user: natsUser,
        password: natsPass,
        zk: natsZoneId === '_unknown' ? null : homeKeyFor(natsZoneId),
      });
      // If we don't have a cached zone yet, ask the server (zone-agnostic
      // discovery subject). "home" never wins — it's reserved.
      //
      // IMPORTANT: do NOT persist the discovered zone here. On a multi-node
      // mesh, `wyrd.discover.zone` has multiple responders (no NATS queue
      // group) and the race winner isn't deterministic — we might land on a
      // node where our credentials don't exist. Persist only AFTER auth
      // succeeds (see below), so we lock in the zone that actually accepted
      // our token. See task #742.
      if (natsZoneId === '_unknown') {
        step('discovering zone via wyrd.discover.zone');
        try {
          const discoveredZone = await nc.discoverZone();
          if (!discoveredZone || discoveredZone === 'home') {
            step(`setup-error: zone discovery returned ${discoveredZone ?? 'null'}`);
            return;
          }
          nc.setZoneId(discoveredZone, homeKeyFor(discoveredZone));
          natsZoneId = discoveredZone;
          step(`zone discovered: ${discoveredZone} (not persisted until auth confirms)`);
        } catch (discErr) {
          // No household server answered discovery — a normal offline/standalone
          // condition, not a user-facing fault. Log at debug level (console only,
          // NOT a "setup-error:" prose line) and surface the REAL cause: NATS
          // client errors often reject with an empty message or a bare object,
          // which used to render as "zone discovery threw — undefined" (task #30).
          const describe = (err: unknown): string => {
            if (err instanceof Error && err.message) return err.message;
            if (typeof err === 'string' && err) return err;
            const rec = err as { message?: unknown; code?: unknown; name?: unknown } | null | undefined;
            if (rec?.message) return String(rec.message);
            if (rec?.code != null) return `code=${String(rec.code)}`;
            try {
              const json = JSON.stringify(err);
              if (json && json !== '{}') return json;
            } catch { /* circular — fall through */ }
            return rec?.name ? String(rec.name) : 'unknown error';
          };
          step(`zone discovery failed — staying local (${describe(discErr)})`);
          reportHomeZoneUnreachable(`zone discovery failed: ${describe(discErr)}`);
          return;
        }
      }
      if (!nc.hasHomeKey()) {
        // Paired before 0.5.0: nothing may cross the relay unsealed.
        step('setup-error: no home key for this zone — pair again');
        const { securityText } = await import('../security/securityText');
        store.getState().addProse({ speaker: 'system', text: securityText().pairAgain });
        await nc.disconnect();
        return;
      }
      step('calling probe() — auth.status on zone-scoped subject');
      let status: { hasUsers: boolean; openRegistration: boolean } | null = null;
      try {
        status = await nc.probe();
      } catch (probeErr) {
        const msg = probeErr instanceof Error ? probeErr.message : String(probeErr);
        step(`setup-error: probe threw — ${msg}`);
        return;
      }
      if (!status) {
        step('setup-error: probe returned null (relay unreachable or rejected)');
        return;
      }
      step(`probe OK: hasUsers=${status.hasUsers} openReg=${status.openRegistration}`);
      try {
        if (savedUser && savedPass) {
          step('calling login() with saved creds');
          await nc.login(savedUser, savedPass);
          step('login OK');
        } else {
          const companionName = store.getState().companionName || 'Wyrd';
          // Closed-registration zones (the common case for established
          // households) need an invite code. If the user has pasted one
          // into Settings (`@wyrd_invite_code`, e.g., "topaz sand
          // thunder yarn bone cypress"), redeem via NATS. Otherwise fall
          // back to open-register (works only on fresh zones).
          const inviteCode = await credStorage.getItem('@wyrd_invite_code');
          let creds: { username: string; password: string };
          if (!status.openRegistration && inviteCode) {
            ({ creds } = await nc.redeemInvite(inviteCode, companionName));
            // Consume the invite — single-use; clear it so we don't try again.
            await credStorage.removeItem('@wyrd_invite_code');
          } else if (status.openRegistration) {
            ({ creds } = await nc.registerAndLogin(companionName));
          } else {
            throw new Error(
              'Household requires an invitation. Paste the 6-word invite code in Settings.',
            );
          }
          await credStorage.setItem('@wyrd_mcp_username', creds.username);
          await credStorage.setItem('@wyrd_mcp_password', creds.password);
        }
        const tok = nc.getToken();
        if (tok) {
          await credStorage.setItem('@wyrd_mcp_session_token', tok);
          node.sessionToken = tok;
        }
        // This phone's own home-bus credentials for the home network (D3).
        const { ensureHomeBusCredentials } = await import('../server/homeBus');
        void ensureHomeBusCredentials(nc, natsZoneId).catch(() => {});
        // Persist the zone we just authenticated against. Skipping discovery
        // on the next cold start avoids the multi-responder race that bit us
        // in task #742 (probe landed on β; α-minted token was rejected).
        const existingZone = await credStorage.getItem('@wyrd_zone_id');
        if (existingZone !== natsZoneId) {
          await credStorage.setItem('@wyrd_zone_id', natsZoneId);
          step(`zone ${natsZoneId} persisted (auth confirmed)`);
        }
        store.getState().setServerClient(nc);
        // publish the authenticated relay connection as a
        // raw pub/sub BetweenClient so StandaloneRoomScreen can tunnel a FULL
        // session over wyrd.tunnel.{zone}.* (mirrors the KMP RelayTunnelHolder
        // wire). The session token we just minted auths the zone loopback /ws.
        try {
          const { RelayTunnelHolder } = await import('../engine/transit');
          const tunnelBetween = nc.asBetweenClient();
          if (tunnelBetween) {
            RelayTunnelHolder.set(tunnelBetween);
          } else {
            reportHomeZoneUnreachable('relay tunnel could not be established');
          }
        } catch (tunnelErr) {
          // Best-effort overlay, but for a home-zone user a dead tunnel means the
          // real zone won't render — say so rather than silently show local.
          const tmsg = tunnelErr instanceof Error ? tunnelErr.message : String(tunnelErr);
          reportHomeZoneUnreachable(`relay tunnel error: ${tmsg}`);
        }
        store.getState().addProse({
          speaker: 'system',
          text: `Connected to your server via relay (${natsRelayUrl}, zone=${natsZoneId}).`,
        });
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        store.getState().addProse({
          speaker: 'system',
          text: `Could not register with server (${msg}). Tell/library/journal will stay local.`,
        });
        if (savedTok) {
          await credStorage.removeItem('@wyrd_mcp_session_token');
          if (node.sessionToken === savedTok) node.sessionToken = null;
        }
        // Drop the unconnected NATS client so we don't hold a dead handle.
        await nc.disconnect();
      }
     } catch (outer) {
      // Backstop for failures outside the inner try (dynamic-import,
      // probeAndTrust, ServerClient construction). Without this they
      // surface as unhandled rejections that RN reports as the generic
      // NativeEventEmitter Invariant Violation on iOS — see 2026-05-11
      // session debug notes. Stay local-only on any failure.
      const msg = outer instanceof Error ? outer.message : String(outer);
      // eslint-disable-next-line no-console
      console.warn('[setupServerClient] outer catch:', msg);
      try {
        store.getState().addProse({
          speaker: 'system',
          text: `Server setup error (${msg}). Tell/library/journal will stay local.`,
        });
      } catch { /* store may not be ready */ }
     }
    };

    // Subscribe to PhoneNode events → feed Zustand store.
    //
    // TUNNEL GATE (2026-07-25): while the relay tunnel is live, the REAL zone
    // owns the terminal — its S2C frames render via StandaloneRoomScreen's
    // renderS2C into this same store. The offline PhoneNode keeps running
    // underneath (it must: study sync, tier tracking, instant fallback), and
    // its world frames raced the zone's. Concretely: node.start() finishes
    // AFTER the tunnel's `look` reply whenever model init is slow, so the
    // local Study snapshot (Present: Wyrd) overwrote the zone room the user
    // had just logged into. Drop the local node's WORLD frames while a tunnel
    // is up; keep tier_changed (device inference state, not room state).
    const unsub = node.onEvent((event: PhoneNodeEvent) => {
      const tunneled = RelayTunnelHolder.get() != null;
      switch (event.type) {
        case 'prose':
          if (tunneled) break;
          store.getState().addProse({ speaker: event.speaker, text: event.text });
          break;
        case 'room_changed':
          if (tunneled) break;
          store.getState().applyRoomSnapshot(event.snapshot);
          store.getState().addProse({
            speaker: 'narrator',
            text: `${event.snapshot.name}\n${event.snapshot.description}`,
          });
          break;
        case 'state_changed':
          if (tunneled) break;
          store.getState().addProse({ speaker: 'narrator', text: `~ ${event.description}` });
          break;
        case 'tier_changed':
          store.getState().setCurrentTier(event.to);
          // Say it out loud. A demotion closes rooms and moves the player
          // home; doing that silently reads as the app losing your world.
          if (event.notice && !tunneled) {
            store.getState().addProse({ speaker: 'system', text: event.notice });
          }
          break;
        case 'error':
          if (tunneled) break;
          store.getState().addProse({
            speaker: 'system',
            text: `Error [${event.code}]: ${event.message}`,
          });
          break;
      }
    });

    // Restore persisted state first (prose visible immediately)
    store.getState().restoreState(storage).then(async (persisted) => {
      // Wire credentials before starting (fixes "not paired" race condition)
      await wireCredentials();

      // Resolve the mode now that credentials are in place, and apply it to
      // the router BEFORE inference discovery runs below. Order matters: the
      // pin has to exist before discovery offers an endpoint, or a mode-5
      // phone loses its cloud endpoint on the first launch at home.
      //
      try {
        const { resolvePhoneMode, applyModeToRouter } = await import('../engine/mode/currentMode');
        const decision = await resolvePhoneMode({
          hasOnDeviceModel: inferenceRouter.canInferLocally(),
        });
        applyModeToRouter(decision.mode, inferenceRouter);
      } catch {
        // Mode resolution is an optimisation over the routing chains, which
        // are already correct on their own. Never block boot on it.
      }

      // Probe configured URL and register a server session in parallel —
      // intentionally not awaited so a slow/unreachable server doesn't
      // block local room boot. Errors prose-log themselves.
      //
      // iOS-hardening (2026-05-11): wrap in a top-level catch as a backstop.
      // Some failure modes inside probeAndTrust / dynamic-import / fetch
      // surface as unhandled JS errors that RN's error formatter then turns
      // into the generic `Invariant Violation: new NativeEventEmitter()
      // requires a non-null argument` and kills the bundle. Catching here
      // keeps the local-only room alive even when remote-server bootstrap
      // is broken on this device (e.g. iOS sim with no native pinning yet).
      setupServerClient().catch((err) => {
        const msg = err instanceof Error ? err.message : String(err);
        // eslint-disable-next-line no-console
        console.warn('[StandaloneNodeContext] setupServerClient failed:', msg);
        try {
          store.getState().addProse({
            speaker: 'system',
            text: `Server bootstrap skipped (${msg}). Tell/library/journal will stay local.`,
          });
        } catch { /* store may not be ready */ }
      });
      // Start the node
      store.getState().setNodeState('starting');
      node.start().then(() => {
        store.getState().setNodeState(node.state);

        // Emit initial room snapshot — UNLESS the relay tunnel already owns
        // the terminal. node.start() (model init) routinely finishes after a
        // warm relay login, and this unconditional apply was how the local
        // Study (Present: Wyrd) stomped the zone room post-login (2026-07-25).
        const snapshot = RelayTunnelHolder.get() == null ? node.look() : null;
        if (snapshot) {
          store.getState().applyRoomSnapshot(snapshot);
          // Only add room description prose if not restoring (avoid duplication)
          if (!persisted) {
            store.getState().addProse({
              speaker: 'narrator',
              text: `${snapshot.name}\n${snapshot.description}`,
            });
          }
        }

        // Track companion state and apply named bootstrap if needed
        if (node.companion) {
          store.getState().setCompanionState('idle');

          // Replace generic bootstrap with named bootstrap if companion was named.
          //
          // BORN AS A PARTICULAR (2026-07-17): the bootstrap now derives its
          // personality from a free-sampled TemperamentSeed with server-identical
          // semantics — every phone birth is a distinct individual, not the old
          // "warm, practical, curious" clone. The seed persists in secureStorage so
          // the SAME particular survives reload (the phone twin of the server's
          // seed-recoverable-from-genome guarantee). Sample once, keep for life.
          const companionName = store.getState().companionName || 'Wyrd';
          const currentManifest = node.companion.getSoulManifest();
          if (currentManifest?.did === BOOTSTRAP_DID) {
            (async () => {
              const { randomSeed, serializeSeed, deserializeSeed } =
                await import('../engine/soul/TemperamentSeed');
              let seed = deserializeSeed(await secureStorage.getItem('@wyrd_temperament_seed'));
              if (!seed) {
                seed = randomSeed();
                await secureStorage.setItem('@wyrd_temperament_seed', serializeSeed(seed));
              }
              await node.companion!.loadSoul(createNamedBootstrap(companionName, seed));
            })().catch(() => {
              // Non-fatal — companion still works with generic bootstrap
            });
          }

          // --- Capability wiring: item store, equipment, starter kit ---
          const itemStore = new AsyncStorageItemStore(storage as any);
          const equipmentService = new EquipmentService();
          const usageTracker = new SkillUsageTracker();

          // Provision starter kit on first boot
          credStorage.getItem('@wyrd_starter_provisioned').then(async (provisioned) => {
            if (!provisioned) {
              const companionDid = node.companion?.getSoulManifest()?.did ?? `did:key:bootstrap-ma`;
              const kit = provisionStarterKit(companionDid, true);
              for (const item of kit) {
                await itemStore.store(item);
              }
              // Auto-equip Everyday Garb
              const garb = kit.find(i => i.label === 'Everyday Garb');
              if (garb) {
                equipmentService.equip(companionDid, garb);
              }
              await credStorage.setItem('@wyrd_starter_provisioned', 'true');
            }
          }).catch(() => {
            // Non-fatal — starter kit provisioning can retry next launch
          });

          // Create bridge and set on companion
          const bridge = new CompanionCapabilityBridge(equipmentService, itemStore, usageTracker);
          node.companion.setCapabilityBridge(bridge);

          // Wire offline queue for dual inference routing (Wave 2/4)
          const offlineQueue = new OfflineQueue(storage as any);
          node.companion.setOfflineQueue(offlineQueue);

          // Pre-load saved remote URL for dual inference (discovery may update it later).
          // Propagate to BOTH the InferenceRouter (used by triage + simple path) AND
          // the CompanionEngine (used by the complex/bud-delegation path). Skipping the
          // router parks every SIMPLE turn in "Wyrd considers..." when no local model
          // is loaded — triage fires through the router, which has no URL, so the
          // companion's quick-reply call has nothing to dial.
          credStorage.getItem('@wyrd_inference_url').then(savedUrl => {
            if (savedUrl) {
              node.companion?.setRemoteInferenceUrl(savedUrl);
              inferenceRouter.setRemoteUrl(savedUrl);
            }
          }).catch(() => {});
        }

        // Discover inference endpoints: the saved URL (encrypted, or on this
        // device) and the device's own ports. Nothing on the home network is
        // probed over plain http — prompts never cross it in the clear.
        // On success, sets the remote URL on InferenceRouter for fallback inference.
        credStorage.getItem('@wyrd_inference_url').then(savedUrl =>
          discoverInference({ savedUrl: savedUrl ?? undefined }),
        ).then(discovered => {
          const best = bestEndpoint(discovered);
          if (best) {
            // Follow discovery only if the router actually TOOK the endpoint.
            // In mode 5 the remote slot is pinned to the user's cloud API and
            // this declines — persisting regardless would durably overwrite
            // their choice with a household URL they never asked for, and the
            // next launch would restore the overwrite.
            if (inferenceRouter.setRemoteUrlIfBetter(best.url)) {
              // Also set remote URL on CompanionEngine for dual inference routing
              node.companion?.setRemoteInferenceUrl(best.url);
              // Persist discovered URL for next launch
              credStorage.setItem('@wyrd_inference_url', best.url).catch(() => {});
            }
          }
        }).catch(() => {
          // Inference discovery is optional — local model or manual URL still works
        });

        // --- Local model download + load (phone-only inference) ---
        // Skip entirely if a remote inferenceUrl is configured — the user
        // explicitly chose "Use my server" or supplied an API key, so the
        // companion routes thinking through that URL. Downloading 2.6GB on
        // top blocks the room with progress prose for minutes and parks
        // every "say" in "Wyrd considers..." until it completes.
        const remoteInferenceUrl = useAppModeStore.getState().inferenceUrl;
        (async () => {
          try {
            // Mode 1 (remote terminal): a home zone is configured (relay leg
            // persisted), so inference comes from the home zone over the relay.
            // Skip the local Study model download even when no explicit
            // inferenceUrl is set. Pure-local modes 2/3 have no relay leg.
            const relayHomeZone = await credStorage.getItem('@wyrd_relay_url');
            if (remoteInferenceUrl || relayHomeZone) {
              // Word the reassurance for the mode actually in use — the API-key
              // path is NOT a home zone (2026-07-24): it routes thinking straight
              // to the cloud provider, so "Using your home zone" read as wrong.
              const apiKey = await credStorage.getItem('@wyrd_api_key');
              const text = relayHomeZone
                ? 'Using your home zone for thinking — no local model download needed.'
                : apiKey
                  ? 'Thinking runs in the cloud via your API key — no local model download needed.'
                  : 'Using your configured inference server — no local model download needed.';
              store.getState().addProse({ speaker: 'system', text });
              return;
            }
            // Belt-and-suspenders (2026-07-22): a zone in the BANK also means
            // a home zone exists — maybe mid-onboarding (invite accepted, login
            // not yet completed). Never auto-start a 2.5GB download in that
            // window; the user can always choose an on-device model explicitly
            // from Welcome → "On this phone".
            const { useZoneBankStore } = await import('../state/zoneBankStore');
            if (useZoneBankStore.getState().zones.length > 0) {
              store.getState().addProse({
                speaker: 'system',
                text: 'A home zone is saved — finish logging in on the Servers screen '
                  + 'and thinking will come from your zone (no model download needed).',
              });
              return;
            }
            // EXPERIMENTAL gate. Downloading gigabytes onto a phone that will
            // answer slower than the user can read is the thing this whole
            // change exists to stop, so the DEFAULT downloads nothing.
            if (!useAppModeStore.getState().onDeviceModelOptIn) {
              store.getState().addProse({
                speaker: 'system',
                text: 'Thinking will come from your home zone or cloud API. '
                  + 'To run the model on this phone instead, turn on '
                  + '"Run the model on this phone" in Settings — it is experimental, '
                  + 'and most phones are not fast enough for it yet.',
              });
              return;
            }

            const { ModelManager, MODEL_CATALOG } = await import('../inference/ModelManager');
            const RNFS = require('react-native-fs');
            const modelsDir = `${RNFS.DocumentDirectoryPath}/models`;
            const mm = new ModelManager(modelsDir);

            store.getState().addProse({
              speaker: 'system',
              text: 'Checking for local model...',
            });

            // 2B, not 4B. A 4B at 4-bit needs ~3GB resident, and a benchmark on
            // a 12GB iPhone 17 Pro could not load one at all — iOS jetsam caps
            // an app near half of total RAM, and the largest model that ran was
            // 1.7B. The 2B is ~1.28GB and sits in the band every source agrees
            // phones actually handle. The catalog has always carried it; the
            // boot path just stepped straight over it to the 4B.
            const preferredModel = 'qwen3.5-2b-q4';
            const fallbackModel = 'qwen3-0.6b-q8';

            let modelPath = await mm.getModelPath(preferredModel);
            if (!modelPath) modelPath = await mm.getModelPath(fallbackModel);

            if (!modelPath) {
              const modelInfo = MODEL_CATALOG.find(m => m.id === preferredModel)!;
              const sizeMb = Math.round(modelInfo.size / 1_000_000);
              store.getState().addProse({
                speaker: 'system',
                text: `Downloading ${modelInfo.name} (${sizeMb}MB)... This may take a few minutes.`,
              });

              modelPath = await mm.downloadModel(preferredModel, (pct) => {
                // Update prose with progress every 10%
                if (pct % 10 === 0) {
                  store.getState().addProse({
                    speaker: 'system',
                    text: `Downloading ${modelInfo.name}: ${pct}%`,
                  });
                }
              });

              store.getState().addProse({
                speaker: 'system',
                text: 'Download complete. Loading model...',
              });
            } else {
              store.getState().addProse({
                speaker: 'system',
                text: 'Local model found. Loading...',
              });
            }

            // Load model on the InferenceRouter's built-in LlamaService
            await inferenceRouter.loadLocalModel(modelPath, {
              nCtx: 2048,
              nThreads: Math.min(6, (globalThis as any).navigator?.hardwareConcurrency ?? 4),
            });

            store.getState().addProse({
              speaker: 'system',
              text: 'Model loaded. Your companion can think now.',
            });
          } catch (e: any) {
            store.getState().addProse({
              speaker: 'system',
              text: `Local model unavailable: ${e?.message ?? 'unknown error'}. Companion will use remote inference if available.`,
            });
          }
        })();

        // Try to connect to household Between network via NATS (optional).
        // On success, setBetween() wires all subsystems into PhoneNode. There
        // is no longer a background LAN scan that joins a home's bus
        // anonymously in the clear: the home refuses that since 0.5.0, and the
        // phone joins its home only as paired (see connectBetweenIfConfigured).
        const say = (text: string) => store.getState().addProse({ speaker: 'system', text });
        connectBetweenIfConfigured(storage, node, say).then(client => {
          if (client) {
            betweenRef.current = client;
          }

          // --- Soul Sync: pull latest manifest from server ---
          // Requires a household server URL (from Between config or saved).
          // Non-fatal: standalone works without server sync.
          credStorage.getItem('@wyrd_server_url').then(async (serverUrl) => {
            if (!serverUrl) {
              // Try to derive server URL from Between URL
              const betweenUrl = await credStorage.getItem('@wyrd_between_url');
              if (!betweenUrl) return;
              try {
                const parsed = new URL(betweenUrl);
                // Assume HTTP API on port 8080 on same host as NATS
                serverUrl = `http://${parsed.hostname}:8080`;
              } catch {
                return;
              }
            }
            // The soul manifest and a token never cross the network in the clear.
            const { isPlaintextToNetwork } = await import('../network/secureAddress');
            if (isPlaintextToNetwork(serverUrl)) return;
            // Soul-sync attempt: relies on system trust store on iOS (no
            // native pinning yet). Errors are swallowed by the .catch on
            // the outer .then chain — sync is optional anyway.

            const companion = node.companion;
            if (!companion) return;

            const soulManifestStore = new AsyncStorageSoulManifestStore(storage);
            const token = (await credStorage.getItem('@wyrd_auth_token'))
              ?? (await credStorage.getItem('@wyrd_token'))
              ?? undefined;
            const syncManager = new SoulSyncManager(soulManifestStore, serverUrl, token);
            const companionName = store.getState().companionName || 'Wyrd';
            const currentManifest = companion.getSoulManifest();
            if (!currentManifest) return;

            const pulled = await syncManager.tryPullFromServer(
              currentManifest.did,
              companionName,
            );
            if (pulled && !syncManager.isBootstrap(pulled)) {
              await companion.loadSoul(pulled);
              // Update appModeStore with sync metadata
              const syncTime = syncManager.getLastSyncTime();
              const syncVersion = syncManager.getLastSyncVersion();
              if (syncTime != null && syncVersion != null) {
                useAppModeStore.getState().setLastSoulSync(syncTime, syncVersion);
              }
            }
          }).catch(() => {
            // Soul sync is optional — companion works with local manifest
          });
        }).catch(() => {
          // Between connection is optional — standalone works without it
        });
      }).catch((e: unknown) => {
        const message = e instanceof Error ? e.message : 'Failed to start';
        store.getState().setNodeError(message);
        store.getState().setNodeState('error');
      });
    }).catch(() => {
      // Restore failed — start fresh (non-critical)
    });

    // Periodic persistence (every 30s)
    const persistTimer = setInterval(() => {
      store.getState().persistState(storage).catch(() => {});
    }, PERSIST_INTERVAL_MS);

    return () => {
      clearInterval(persistTimer);
      // Persist on unmount
      store.getState().persistState(storage).catch(() => {});
      unsub();
      // PhoneNode.stop() tears down Between subsystems internally
      node.stop();
      betweenRef.current?.disconnect().catch(() => {});
      betweenRef.current = null;
      store.getState().reset();
    };
  }, []);

  return (
    <StandaloneCtx.Provider value={{ phoneNode: nodeRef.current! }}>
      {children}
    </StandaloneCtx.Provider>
  );
};
