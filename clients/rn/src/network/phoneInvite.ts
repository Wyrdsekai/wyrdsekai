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
 * are present only for self-signed relays — they seed the TOFU pin the
 * native trust layer (#705) otherwise learns on first connect. ACME
 * relays carry no pin material; system trust applies.
 *
 * Pure + side-effect free so it unit-tests without an emulator. The
 * caller (ConnectScreen paste path, QR scan later) persists the fields.
 */

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

  return {
    relays,
    householdId: unspecifiedToUndefined(payload.household_id),
    zoneId: unspecifiedToUndefined(payload.zone_id),
    mintedAt: typeof payload.minted_at === 'number' ? payload.minted_at : undefined,
  };
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
