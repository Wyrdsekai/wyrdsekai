/**
 * openZone — the orchestration behind "tap a server in your bank"
 * The Servers screen calls this; everything
 * testable lives here, the screen stays thin.
 *
 * Flow:
 *   1. Look the zone up in the bank; resolve the held relays that reach it.
 *   2. Resolve the password: explicit arg > the per-device remembered one
 *      (@wyrd_zone_pw_{zoneId}, never synced). None → 'needs-password' so the
 *      screen prompts.
 *   3. Auto-attempt the login across the relays (zoneConnect).
 *   4. On success: remember the password (this device), bump the winning relay
 *      to the front, touch lastUsedAt. Return the connected client for the
 *      session to adopt.
 */
import { connectToZone, connectToZoneWith } from './zoneConnect';
import type { ZoneConnectOk } from './zoneConnect';
import type { NatsServerClient } from './NatsServerClient';
import type { HeldRelay } from '../state/zoneBankStore';
import { useZoneBankStore, zonePasswordKey } from '../state/zoneBankStore';
import { secureStorage } from '../state/secureStorage';
import { syncZoneBank } from './zoneBankSync';

export type OpenZoneResult =
  | { ok: true; client: NatsServerClient; relayUrl: string }
  | { ok: false; reason: 'needs-password' }
  | { ok: false; reason: 'auth-rejected'; error: string }
  | { ok: false; reason: 'unreachable'; error: string };

export async function openZone(
  zoneId: string,
  opts?: { password?: string; requestTimeoutMs?: number },
): Promise<OpenZoneResult> {
  const bank = useZoneBankStore.getState();
  const zone = bank.getZone(zoneId);
  if (!zone) {
    return { ok: false, reason: 'unreachable', error: 'That server is not in your bank.' };
  }
  const relays = bank.relaysForZone(zoneId);

  // Resolve password: explicit wins, else the per-device remembered one.
  let password = opts?.password;
  if (!password) {
    password = (await secureStorage.getItem(zonePasswordKey(zoneId))) ?? undefined;
  }
  if (!password) {
    return { ok: false, reason: 'needs-password' };
  }

  await pinRelays(relays);

  const res = await connectToZone(zone, relays, password, {
    requestTimeoutMs: opts?.requestTimeoutMs,
  });

  if (!res.ok) {
    return res.authRejected
      ? { ok: false, reason: 'auth-rejected', error: res.error }
      : { ok: false, reason: 'unreachable', error: res.error };
  }

  await persistZoneSession(zoneId, relays, res, password);
  return { ok: true, client: res.client, relayUrl: res.relayUrl };
}

export type CreateZoneAccountResult =
  | { ok: true; client: NatsServerClient; relayUrl: string;
      role?: string; recoveryKey?: string }
  | { ok: false; reason: 'registration-closed' | 'rejected' | 'unreachable'; error: string };

/**
 * Create a NAMED account on a banked zone over the relay — the phone-first
 * onboarding path (2026-07-23). Same relay-attempt ladder and post-success
 * persistence as {@link openZone}, but step 3 is auth.register (or
 * auth.redeem when the household is invite-only and the user holds a code).
 * On a fresh household the first registrant becomes the steward and gets a
 * one-time recoveryKey — the caller MUST show it (it is the only
 * password-reset credential).
 */
export async function createZoneAccount(
  zoneId: string,
  args: { username: string; password: string; inviteCode?: string },
  opts?: { requestTimeoutMs?: number },
): Promise<CreateZoneAccountResult> {
  const bank = useZoneBankStore.getState();
  const zone = bank.getZone(zoneId);
  if (!zone) {
    return { ok: false, reason: 'unreachable', error: 'That server is not in your bank.' };
  }
  const relays = bank.relaysForZone(zoneId);
  await pinRelays(relays);

  let recoveryKey: string | undefined;
  let role: string | undefined;
  const res = await connectToZoneWith(
    zone, relays,
    async (client) => {
      const auth = args.inviteCode
        ? await client.redeemNamed(args.inviteCode, args.username, args.password)
        : await client.registerNamed(args.username, args.password);
      recoveryKey = auth.recoveryKey;
      role = auth.role;
      return auth;
    },
    { requestTimeoutMs: opts?.requestTimeoutMs },
  );

  if (!res.ok) {
    if (res.authRejected && /registration_closed/i.test(res.error)) {
      return {
        ok: false, reason: 'registration-closed',
        error: 'This household is invite-only — ask the steward for an invite code '
          + '(minted from the invitation scroll in their Study).',
      };
    }
    return res.authRejected
      ? { ok: false, reason: 'rejected', error: res.error }
      : { ok: false, reason: 'unreachable', error: res.error };
  }

  // Bank the username the account was created under, then the same session
  // persistence as a login.
  bank.addOrUpdateZone({ ...zone, username: args.username });
  await persistZoneSession(zoneId, relays, res, args.password);
  return { ok: true, client: res.client, relayUrl: res.relayUrl, role, recoveryKey };
}

/**
 * Install the relay TLS pins BEFORE connecting. A relay-only zone serves a
 * self-signed household leaf — iOS/Android system trust rejects it, so the
 * wss handshake dies before reaching the relay unless we've pinned its
 * fingerprints first. ConnectScreen does this for the direct path; the bank
 * paths (login + register) reach connect through here, so they must too
 * (, #733). The served cert is the LEAF, so offer both
 * the CA fp and the leaf fp. Best-effort: a failed pin leaves the connect to
 * surface the TLS error through the normal trust-not-established path.
 */
async function pinRelays(relays: HeldRelay[]): Promise<void> {
  await Promise.all(
    relays.map(async (relay) => {
      const fps = [relay.caFp, relay.fp].filter(Boolean) as string[];
      if (fps.length === 0) return;
      try {
        const u = new URL(relay.wsUrl.replace(/^wss:/, 'https:').replace(/^ws:/, 'http:'));
        const port = u.port ? Number(u.port) : 443;
        const { pinInviteFingerprints, trustFromInviteFingerprints } = await import(
          './HouseholdTrust'
        );
        // Primary path: seed the pin set straight from the invite fingerprints
        // (no TLS round-trip). The served leaf's SHA-256 IS one of these, so the
        // WS serverTrust challenge will match. Robust against relays that don't
        // serve HTTP/3 (which would break the cert-fetch probe on iOS).
        await pinInviteFingerprints(u.hostname, fps);
        // Supplement (best-effort): also try the cert-chain grab so a CA-pin
        // (rotation-proof) lands too where the probe works. Never required.
        const pinned = await trustFromInviteFingerprints(u.hostname, port, fps);
        if (!pinned) {
          // eslint-disable-next-line no-console
          console.warn('[openZone] cert-chain pin did not take for', relay.wsUrl);
        }
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn('[openZone] invite-fingerprint pin failed:', e);
      }
    }),
  );
}

/**
 * Post-auth session persistence shared by login and registration: remember
 * the password on THIS device, learn the winning relay, persist the
 * TOP-LEVEL relay keys the node boot reads (@wyrd_relay_url + relay_phone
 * creds + zone id: without them the study-sync
 * Between leg can't come up and the model-skip gate wrongly downloads a
 * local model), persist the account userId (the ACCOUNT owns the Study),
 * and fire the cross-device bank sync.
 */
async function persistZoneSession(
  zoneId: string,
  relays: HeldRelay[],
  res: ZoneConnectOk,
  password: string,
): Promise<void> {
  const bank = useZoneBankStore.getState();
  await secureStorage.setItem(zonePasswordKey(zoneId), password);
  bank.bumpRelay(zoneId, res.relayUrl);
  bank.touchZone(zoneId);

  const winRelay = relays.find((r) => r.wsUrl === res.relayUrl) ?? relays[0];
  if (winRelay) {
    await secureStorage.setItem('@wyrd_relay_url', winRelay.wsUrl);
    if (winRelay.natsUser) await secureStorage.setItem('@wyrd_nats_user', winRelay.natsUser);
    if (winRelay.natsPass) await secureStorage.setItem('@wyrd_nats_pass', winRelay.natsPass);
  }
  await secureStorage.setItem('@wyrd_zone_id', zoneId);
  if (res.auth?.userId) await secureStorage.setItem('@wyrd_user_id', res.auth.userId);

  // Best-effort and non-blocking: a sync failure must never delay or fail
  // the session. Fire-and-forget after bumping/touching so the upload
  // carries the freshest local view.
  void syncZoneBank(res.client, Date.now()).catch(() => {});
}

/** Forget the remembered password for a zone (e.g. after an auth rejection,
 *  or a "sign out of this server on this device" action). */
export async function forgetZonePassword(zoneId: string): Promise<void> {
  await secureStorage.removeItem(zonePasswordKey(zoneId));
}
