/**
 * /P5 — parse a `wyrdphone://` connection invite.
 *
 * Minted by `wyrd phone invite` (relay /phone-invite endpoint). Shape:
 *   wyrdphone://host[:port]/<base64url-JSON>
 * where the payload is
 *   { v: 1, kind: "phone",
 *     relays: [{ ws_url, nats_user, nats_password, fp?, ca_fp? }, ...],
 *     household_id, zone_id, minted_at }
 *
 * `relays` is an ORDERED failover list (one entry today). `fp`/`ca_fp`
 * are present only for self-signed relays — they seed the pin the native
 * trust layer checks. ACME relays carry no pin material; system trust applies.
 *
 * Since 0.5.0 the home stamps more top-level fields
 * (, W2/W3):
 *   zk          the home's X25519 tunnel key (base64url, 32 bytes). The phone
 *               seals its tunnel and its requests to it, so the relay only routes.
 *   home_ca_fp  SHA-256 of the household CA certificate (hex). The phone pins
 *               its home-network connections to that CA.
 *   lan_https   "https://<lan host or ip>:7443", the home on the home network.
 *   home_bus    "wss://<lan host or ip>:<port>", the home's bus websocket as a
 *               phone reaches it (the port follows the home's bus port; it is
 *               4223 only on a home with the default port).
 *
 * Pure + side-effect free so it unit-tests without an emulator. The
 * caller (ConnectScreen paste path, QR scan later) persists the fields.
 */
import { decodePublicKey } from '../crypto/sealedTunnel';

export interface PhoneInviteRelay {
  wsUrl: string;
  natsUser: string;
  natsPassword: string;
  /** Relay leaf-cert SHA-256, colon-hex — TOFU pin seed (self-signed only). */
  fp?: string;
  /** Household CA SHA-256, colon-hex (self-signed only). */
  caFp?: string;
}

export interface PhoneInvite {
  relays: PhoneInviteRelay[];
  householdId?: string;
  /** Zone hint — lets the client skip the wyrd.discover.zone round trip. */
  zoneId?: string;
  mintedAt?: number;
  /** The home's public tunnel key, base64url (32 bytes). Absent in invites older than 0.5.0. */
  zk?: string;
  /** SHA-256 of the household CA certificate, lowercase hex without colons. */
  homeCaFp?: string;
  /** The home's HTTPS address on the home network, e.g. https://198.51.100.20:7443. */
  lanHttps?: string;
  /** The home's bus websocket on the home network, e.g. wss://198.51.100.20:4223. */
  homeBus?: string;
}

export function isPhoneInviteUrl(text: string): boolean {
  return text.trim().toLowerCase().startsWith('wyrdphone://');
}

/**
 * Parse an invite URL. Throws Error with a human-readable message on any
 * malformation — the connect screen surfaces it verbatim.
 */
export function parsePhoneInvite(url: string): PhoneInvite {
  const trimmed = url.trim();
  if (!isPhoneInviteUrl(trimmed)) {
    throw new Error('Not a wyrdphone:// invite URL');
  }
  const rest = trimmed.substring('wyrdphone://'.length);
  const slash = rest.indexOf('/');
  if (slash <= 0 || slash === rest.length - 1) {
    throw new Error('Invite URL is missing its payload');
  }
  const payloadB64 = rest.substring(slash + 1);

  let payload: any;
  try {
    payload = JSON.parse(base64UrlDecode(payloadB64));
  } catch (e) {
    throw new Error('Invite payload is not valid (re-copy the full URL)');
  }
  if (payload?.kind !== 'phone') {
    throw new Error(`Not a phone invite (kind=${payload?.kind ?? 'missing'})`);
  }
  if (!Array.isArray(payload.relays) || payload.relays.length === 0) {
    throw new Error('Invite carries no relays');
  }

  const relays: PhoneInviteRelay[] = payload.relays.map((r: any, i: number) => {
    if (!r?.ws_url || !r?.nats_user || !r?.nats_password) {
      throw new Error(`Relay entry ${i + 1} is incomplete`);
    }
    return {
      wsUrl: String(r.ws_url),
      natsUser: String(r.nats_user),
      natsPassword: String(r.nats_password),
      fp: r.fp ? String(r.fp) : undefined,
      caFp: r.ca_fp ? String(r.ca_fp) : undefined,
    };
  });

  let zk: string | undefined;
  if (payload.zk != null) {
    if (!decodePublicKey(String(payload.zk))) {
      throw new Error("The invite's home key is damaged (re-copy the full URL)");
    }
    zk = String(payload.zk);
  }
  let homeCaFp: string | undefined;
  if (payload.home_ca_fp != null) {
    homeCaFp = normalizeSha256Hex(String(payload.home_ca_fp)) ?? undefined;
    if (!homeCaFp) throw new Error("The invite's certificate fingerprint is damaged (re-copy the full URL)");
  }
  // Only an https address is ever used on the home network; anything else is ignored.
  const lanHttps = typeof payload.lan_https === 'string' && /^https:\/\/[^/\s]+/i.test(payload.lan_https)
    ? payload.lan_https.replace(/\/+$/, '')
    : undefined;
  // Likewise the home bus: wss only (a plain ws:// bus is never used).
  const homeBus = typeof payload.home_bus === 'string' && /^wss:\/\/[^/\s]+/i.test(payload.home_bus)
    ? payload.home_bus.replace(/\/+$/, '')
    : undefined;

  return {
    relays,
    householdId: unspecifiedToUndefined(payload.household_id),
    zoneId: unspecifiedToUndefined(payload.zone_id),
    mintedAt: typeof payload.minted_at === 'number' ? payload.minted_at : undefined,
    zk,
    homeCaFp,
    lanHttps,
    homeBus,
  };
}

/** A SHA-256 fingerprint in any of the usual spellings → 64 lowercase hex chars, or null. */
export function normalizeSha256Hex(fp: string): string | null {
  const hex = fp.trim().replace(/[:\s]/g, '').toLowerCase();
  return /^[0-9a-f]{64}$/.test(hex) ? hex : null;
}

function unspecifiedToUndefined(v: unknown): string | undefined {
  if (typeof v !== 'string' || v.length === 0 || v === 'unspecified') return undefined;
  return v;
}

function base64UrlDecode(b64url: string): string {
  let b64 = b64url.replace(/-/g, '+').replace(/_/g, '/');
  while (b64.length % 4 !== 0) b64 += '=';
  // global.atob exists in RN Hermes; Buffer covers Jest/node.
  if (typeof atob === 'function') {
    // atob yields latin1; re-decode as UTF-8 for non-ASCII zone names.
    const latin1 = atob(b64);
    const bytes = new Uint8Array(latin1.length);
    for (let i = 0; i < latin1.length; i++) bytes[i] = latin1.charCodeAt(i);
    return utf8Decode(bytes);
  }
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  return require('buffer').Buffer.from(b64, 'base64').toString('utf8');
}

function utf8Decode(bytes: Uint8Array): string {
  if (typeof TextDecoder !== 'undefined') {
    return new TextDecoder('utf-8').decode(bytes);
  }
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  return require('buffer').Buffer.from(bytes).toString('utf8');
}
