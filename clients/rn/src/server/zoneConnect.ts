/**
 * zoneConnect — cross-relay AUTO-ATTEMPT login for a zone bank entry
 * ( routing + P2).
 *
 * "Tap a server → it just works." Given a ZoneBankEntry, the held relays that
 * reach it (in preference order), and the user's password for that zone, we
 * try each relay until one logs us in — then report WHICH relay won so the
 * caller can remember it (bump it to the front next time).
 *
 * Per-relay, three steps, each with a distinct meaning:
 *   1. connect() throws  → relay is down/unreachable        → try next relay
 *   2. probe() is null   → zone is not homed on THIS relay   → try next relay
 *      (no responder on wyrd.zone.{zone}.* — a different relay may carry it)
 *   3. login() throws    → we REACHED the zone; this is the account decision
 *                          (wrong password / no such account) → STOP, definitive
 *
 * Step 3 is definitive because every relay that carries the zone reaches the
 * SAME zone backend — the login verdict is identical across them, so retrying
 * other relays after a real auth rejection is pointless (and would hammer the
 * account with repeated failures).
 */
import { NatsServerClient } from './NatsServerClient';
import type { HeldRelay, ZoneBankEntry } from '../state/zoneBankStore';

export interface ZoneConnectOk {
  ok: true;
  client: NatsServerClient;
  /** The relay that succeeded — caller should bump it to the front. */
  relayUrl: string;
  auth: { token: string; userId: string; username: string; role?: string; zoneId?: string };
}

export interface ZoneConnectError {
  ok: false;
  error: string;
  /** true → we reached the zone but the account was rejected (prompt re-auth);
   *  false → no held relay could reach the zone (network / wrong relay set). */
  authRejected: boolean;
  attempts: Array<{ relayUrl: string; stage: 'connect' | 'probe'; error: string }>;
}

export type ZoneConnectResult = ZoneConnectOk | ZoneConnectError;

export async function connectToZone(
  zone: ZoneBankEntry,
  relays: HeldRelay[],
  password: string,
  opts?: { requestTimeoutMs?: number },
): Promise<ZoneConnectResult> {
  return connectToZoneWith(
    zone, relays,
    (client) => client.login(zone.username, password),
    opts,
  );
}

/**
 * The generic engine behind {@link connectToZone}: same three-step
 * relay-attempt ladder, with step 3 (the account decision) injectable.
 * Registration (2026-07-23) reuses steps 1-2 verbatim — a named
 * auth.register/auth.redeem is just as definitive as a login once the zone
 * is reached (username_taken / registration_closed / bad code are account
 * verdicts, not transport failures).
 */
export async function connectToZoneWith(
  zone: ZoneBankEntry,
  relays: HeldRelay[],
  authenticate: (client: NatsServerClient) => Promise<ZoneConnectOk['auth']>,
  opts?: { requestTimeoutMs?: number },
): Promise<ZoneConnectResult> {
  const attempts: ZoneConnectError['attempts'] = [];

  if (relays.length === 0) {
    return {
      ok: false,
      authRejected: false,
      error: 'This device holds no relay that reaches this server. Add the relay (scan/paste an invite) first.',
      attempts,
    };
  }

  for (const relay of relays) {
    const client = new NatsServerClient({
      relayUrl: relay.wsUrl,
      zoneId: zone.zoneId,
      user: relay.natsUser,
      password: relay.natsPass,
      requestTimeoutMs: opts?.requestTimeoutMs,
    });

    // 1. Relay reachable?
    try {
      await client.connect();
    } catch (e) {
      attempts.push({ relayUrl: relay.wsUrl, stage: 'connect', error: errStr(e) });
      await safeDisconnect(client);
      continue;
    }

    // 2. Zone homed on THIS relay? (null = no responder → try another relay.)
    let reachable = false;
    try {
      reachable = (await client.probe()) != null;
    } catch (e) {
      attempts.push({ relayUrl: relay.wsUrl, stage: 'probe', error: errStr(e) });
      await safeDisconnect(client);
      continue;
    }
    if (!reachable) {
      attempts.push({ relayUrl: relay.wsUrl, stage: 'probe', error: 'zone not reachable via this relay' });
      await safeDisconnect(client);
      continue;
    }

    // 3. The account decision (login OR register/redeem) — definitive once
    // we've reached the zone.
    try {
      const auth = await authenticate(client);
      return { ok: true, client, relayUrl: relay.wsUrl, auth };
    } catch (e) {
      await safeDisconnect(client);
      return {
        ok: false,
        authRejected: true,
        error: `${zone.displayName}: ${errStr(e)}`,
        attempts,
      };
    }
  }

  // Surface WHY each relay failed — a bare "could not reach" hides whether it
  // was the wss/TLS handshake (connect) or the zone not answering (probe), which
  // is exactly what's needed to tell a pin/cert problem from a wrong-relay one.
  const detail = attempts
    .map((a) => `${a.stage}: ${a.error}`)
    .filter((s, i, arr) => arr.indexOf(s) === i) // de-dupe identical failures
    .join('; ');
  return {
    ok: false,
    authRejected: false,
    error: `Could not reach ${zone.displayName} on any of your ${relays.length} relay(s).`
      + (detail ? ` (${detail})` : ''),
    attempts,
  };
}

function errStr(e: unknown): string {
  // nats.ws / native TLS failures frequently reject with a bare object, an
  // Error whose `.message` is empty/undefined, or nothing at all — so the naive
  // `e.message ?? String(e)` rendered a useless "undefined". Dig for anything
  // legible: message → code → name → JSON → the constructor name. (2026-07-24)
  if (e == null) return 'unknown error (null)';
  if (e instanceof Error && e.message) return e.message;
  if (typeof e === 'string' && e) return e;
  const rec = e as { message?: unknown; code?: unknown; name?: unknown; reason?: unknown };
  if (rec.message) return String(rec.message);
  if (rec.reason) return String(rec.reason);
  if (rec.code != null) return `code=${String(rec.code)}`;
  try {
    const json = JSON.stringify(e);
    if (json && json !== '{}' && json !== '"undefined"') return json;
  } catch { /* circular — fall through */ }
  if (rec.name) return String(rec.name);
  const ctor = (e as { constructor?: { name?: string } })?.constructor?.name;
  return ctor ? `bare ${ctor} (no message)` : 'unknown error (no message)';
}

async function safeDisconnect(client: NatsServerClient): Promise<void> {
  try { await client.disconnect(); } catch { /* idempotent */ }
}
