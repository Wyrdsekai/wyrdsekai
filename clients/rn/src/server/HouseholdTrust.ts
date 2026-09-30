/**
 * HouseholdTrust — certificate pinning for the wyrdsekai phone client.
 *
 * Where the pins come from: only invites (the relay's `fp`/`ca_fp`, the home's
 * `home_ca_fp`) and certificates matched against them. The invite IS the trust
 * decision; the phone never asks the person to trust a certificate, and a
 * certificate that does not match a pin is refused with a plain message to pair
 * again ( D6; SECURITY_MODEL.md "not TOFU").
 *
 * Where they are kept: in secureStorage (encrypted, its key in the iOS Keychain
 * / Android Keystore). The native pin stores (iOS WyrdTrustStore, Android
 * HouseholdTrustStore) hold them in memory only and are filled from here at
 * every start (restoreNativePins) — nothing pin-related lives in
 * NSUserDefaults or SharedPreferences.
 *
 */
import { secureStorage as AsyncStorage } from '../state/secureStorage';
import { Alert, DeviceEventEmitter, NativeModules, Platform } from 'react-native';
import { useZoneBankStore, type ZoneTrust } from '../state/zoneBankStore';
import { endpointOf, sameEndpoint } from '../network/secureAddress';
import { securityText } from '../security/securityText';

const STORAGE_PREFIX = '@wyrd_trust_';

/**
 * The key a pin is kept under in the native store. On iOS pins are per
 * `host:port` (WyrdTrustStore), so a relay and a home on the same machine
 * never share pins: the relay's leaf pin must not stand for the home, and the
 * home's CA pin must not refuse the relay. Android's store (HouseholdTrustStore,
 * used by the Android build of this app only) is still per host.
 */
export function pinKey(host: string, port: number): string {
  const h = host.replace(/^\[|\]$/g, '').toLowerCase();
  return Platform.OS === 'ios' ? `${h}:${port}` : h;
}

/** The pin key for an address (https/wss/http/ws; default ports filled in), or null. */
export function pinKeyForUrl(url: string): string | null {
  const e = endpointOf(url);
  return e ? pinKey(e.host, e.port) : null;
}

/** The home-network addresses an invite gave for one home, each pinned to its CA. */
function homeEndpoints(t: ZoneTrust): string[] {
  return [t.lanHttps, t.homeBus].filter((u): u is string => !!u);
}

/**
 * Native bridge to the OkHttp HouseholdTrustManager (Android only).
 *   addTrustedCert(host, pem) — persist + install for future HTTPS calls
 *   removeTrustedCert(host)   — drop the pin
 *   listTrustedHosts()        — inspector for the trust UI
 *
 * iOS now ships the parallel native module too (#733): the in-tree
 * wyrd-household-trust pod exports the same `HouseholdTrust` bridge name and
 * pins the SocketRocket WebSocket via an SRSecurityPolicy fingerprint check,
 * so both platforms use the identical JS surface below.
 */
/** Every `key` below is a pin key: `host:port` on iOS, the host on Android (see pinKey). */
interface NativeHouseholdTrust {
  addTrustedCert(key: string, pem: string): Promise<boolean>;
  removeTrustedCert(key: string): Promise<boolean>;
  listTrustedHosts(): Promise<
    Array<{ host: string; subject: string; validUntil: number }>
  >;
  /** Unvalidated TLS chain grab — see trustFromInviteFingerprints. */
  fetchServerCertificates(
    host: string,
    port: number,
  ): Promise<Array<{ pem: string; fingerprint: string }>>;
  /** Pin a fingerprint directly (no cert fetch) — see pinInviteFingerprints.
   *  iOS-only today; absent on Android (use addTrustedCert there). */
  pinFingerprint?(key: string, fingerprint: string): Promise<boolean>;
  /** Pin a host to a CA by the CA certificate's SHA-256: the served chain must
   *  validate up to that CA (hostname checked too). Used for `home_ca_fp`. */
  pinCaFingerprint?(key: string, fingerprint: string): Promise<boolean>;
}

const nativeTrust: NativeHouseholdTrust | null =
  (Platform.OS === 'android' || Platform.OS === 'ios') && NativeModules.HouseholdTrust
    ? (NativeModules.HouseholdTrust as NativeHouseholdTrust)
    : null;

export interface PinnedHouseholdTrust {
  host: string;
  certPem: string;
  fingerprint: string;
  trustedAt: number;
  /** "tofu" (historical name) = a cert matched against an invite's fingerprints;
   *  "system" = chain validated against a public CA. */
  source: 'tofu' | 'system';
}

export interface HouseholdTrustOptions {
  /** Optional override for fetch (testing). */
  fetchImpl?: typeof fetch;
}

/**
 * Look up an existing pinned trust for `host`. Returns null if none stored.
 */
export async function getTrust(host: string): Promise<PinnedHouseholdTrust | null> {
  const raw = await AsyncStorage.getItem(STORAGE_PREFIX + host);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as PinnedHouseholdTrust;
  } catch {
    return null;
  }
}

/**
 * Persist a trust record: a certificate matched against an invite
 * (trustFromInviteFingerprints) or a system-trusted probe (probeAndTrust).
 *
 * Two-write path: secure storage (the lasting copy, restored into the native
 * store at every start) AND the native pin store (in memory, what the TLS
 * layer checks).
 */
export async function setTrust(t: PinnedHouseholdTrust): Promise<void> {
  await AsyncStorage.setItem(STORAGE_PREFIX + t.host, JSON.stringify(t));
  if (nativeTrust && t.certPem) {
    try {
      await nativeTrust.addTrustedCert(t.host, t.certPem);
    } catch (e) {
      // Don't fail the whole flow — the cert is still saved at the JS layer
      // and the user can re-prompt. But log so a real failure is visible.
      // eslint-disable-next-line no-console
      console.warn(`[HouseholdTrust] native addTrustedCert failed for ${t.host}:`, e);
    }
  }
}

/**
 * Drop the pin for a host (used on rotation or manual revoke).
 */
export async function clearTrust(host: string): Promise<void> {
  await AsyncStorage.removeItem(STORAGE_PREFIX + host);
  if (nativeTrust) {
    try {
      await nativeTrust.removeTrustedCert(host);
    } catch {
      // best-effort; native side may already be empty
    }
  }
}

/** Extract `host[:port]` from a URL string for use as the trust-store key. */
function hostKey(url: string): string {
  try {
    const u = new URL(url);
    return u.host;
  } catch {
    return url.replace(/^https?:\/\//, '').split('/')[0];
  }
}

/**
 * Run the trust-probe for a server URL.
 *
 * the cleartext /ca.crt fetch over :80
 * is GONE (the relay no longer listens on :80, freeing it for the
 * operator's website). Bootstrap paths now:
 *
 *   • Public relay with Let's Encrypt — system trust validates, done.
 *   • Household CA on LAN — operator distributes ca.crt out-of-band
 *     (AirDrop / email / USB) and the user installs it via Settings →
 *     Security → Install certificate. Then system trust covers it.
 *   • Previously-pinned via legacy TOFU — kept in store, still honoured.
 *
 * Flow:
 *   1. Already pinned? Return it.
 *   2. Try the system-trust HTTPS probe.
 *   3. On TLS failure: throw `trust-not-established`. The caller surfaces
 *      operator-facing instructions for manual CA install.
 *
 * Returns the trust record (existing or system-trusted), or null if the
 * URL uses a public CA and no pin was needed.
 */
export async function probeAndTrust(
  url: string,
  opts: HouseholdTrustOptions
): Promise<PinnedHouseholdTrust | null> {
  const host = hostKey(url);
  const f = opts.fetchImpl ?? fetch;

  // Already pinned? Just return it.
  const existing = await getTrust(host);
  if (existing) return existing;

  // System-trust HTTPS probe. Succeeds if:
  //   • cert chain validates against device root store (public CA), or
  //   • household CA is already installed via Settings (user trust), or
  //   • a stale legacy TOFU pin is still applied at the native layer.
  try {
    const resp = await f(`${url.replace(/\/+$/, '')}/api/auth/status`, {
      method: 'GET',
    });
    if (resp.ok) {
      const record: PinnedHouseholdTrust = {
        host,
        certPem: '',
        fingerprint: '',
        trustedAt: Date.now(),
        source: 'system',
      };
      await setTrust(record);
      return record;
    }
    throw new Error(`probe-non-ok-${resp.status}`);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg.startsWith('probe-non-ok-')) throw e;
    // TLS failure. No cleartext bootstrap path exists anymore.
    throw new Error('trust-not-established');
  }
}

/**
 * Pin the relay's household CA from a wyrdphone:// invite's fingerprints —
 * Single-port relays (§10.9) have no cleartext
 * /ca.crt bootstrap, but the invite the steward hands to their own device
 * carries the CA's SHA-256 fingerprint (`ca_fp`) and the leaf's (`fp`).
 * Caddy serves leaf + CA in the TLS chain, so:
 *
 *   1. Grab the presented chain WITHOUT validating (native trust-all probe;
 *      read-only — nothing rides the connection).
 *   2. Find the chain cert whose fingerprint matches one of the invite's.
 *      Prefer the CA (rotation-proof pin); fall back to the leaf.
 *   3. Pin the matching PEM. No user prompt — the invite IS the trust
 *      decision, same authority as the QR code that delivered it.
 *
 * Returns the pinned record, or null when nothing matched / no native
 * module (iOS — #733). A null is non-fatal: the connect attempt will fail
 * TLS and surface the manual-CA-install path instead.
 */
/**
 * pinInviteFingerprints — seed the native pin set DIRECTLY from the invite's
 * fingerprints, with no TLS round-trip (no fetchServerCertificates).
 *
 * The invite already carries the relay's leaf+CA SHA-256, so we don't need to
 * fetch the served cert to know what to pin — the WebSocket serverTrust
 * challenge later computes the served leaf's SHA-256 and matches it against this
 * set. This is the ROBUST iOS path: fetchServerCertificates does an `https://`
 * GET to grab the chain, which iOS opportunistically probes over HTTP/3/QUIC; a
 * relay that serves a TCP-TLS WebSocket but no QUIC fails that probe, leaving
 * the pin set empty and the WS handshake (correctly) failing closed. Seeding
 * straight from the invite sidesteps that entirely. Mirrors KMP InvitePinning.ios.
 *
 * Returns the count of fingerprints pinned (0 when no native pinFingerprint —
 * Android, which pins via OkHttp/addTrustedCert instead).
 */
export async function pinInviteFingerprints(
  relayWsUrl: string,
  fingerprints: Array<string | undefined>,
): Promise<number> {
  const key = pinKeyForUrl(relayWsUrl);
  if (!nativeTrust?.pinFingerprint || !key) return 0;
  const wanted = fingerprints
    .filter((f): f is string => !!f)
    .map((f) => f.toUpperCase());
  let pinned = 0;
  for (const fp of wanted) {
    try {
      await nativeTrust.pinFingerprint(key, fp);
      pinned += 1;
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn(`[HouseholdTrust] direct pin failed for ${key} ${fp}:`, e);
    }
  }
  return pinned;
}

export async function trustFromInviteFingerprints(
  host: string,
  port: number,
  fingerprints: Array<string | undefined>,
): Promise<PinnedHouseholdTrust | null> {
  if (!nativeTrust?.fetchServerCertificates) return null;
  const wanted = fingerprints
    .filter((f): f is string => !!f)
    .map((f) => f.toUpperCase());
  if (wanted.length === 0) return null;

  let chain: Array<{ pem: string; fingerprint: string }>;
  try {
    chain = await nativeTrust.fetchServerCertificates(host, port);
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn(`[HouseholdTrust] cert probe failed for ${host}:${port}:`, e);
    return null;
  }

  // Prefer the LAST matching cert — chain order is leaf-first, so the CA
  // (when present and matched) wins over the leaf and the pin survives
  // leaf rotation.
  let match: { pem: string; fingerprint: string } | null = null;
  for (const cert of chain) {
    if (wanted.includes(cert.fingerprint.toUpperCase())) match = cert;
  }
  if (!match) {
    // eslint-disable-next-line no-console
    console.warn(
      `[HouseholdTrust] no chain cert matched invite fingerprints for ${host} ` +
      `(chain=${chain.map((c) => c.fingerprint.slice(0, 23)).join(', ')})`,
    );
    return null;
  }

  const record: PinnedHouseholdTrust = {
    host: pinKey(host, port),
    certPem: match.pem,
    fingerprint: match.fingerprint,
    trustedAt: Date.now(),
    source: 'tofu',
  };
  await setTrust(record);
  return record;
}

/**
 * Pin-mismatch listener — wire this once at app startup. The native TLS layer
 * emits `wyrd_trust_pin_mismatch` when a host that has pins presents a
 * certificate that does not match them. The connection has already been
 * refused; this only tells the person why and what to do (pair again). There
 * is no "trust the new certificate" choice: a changed certificate reaches the
 * phone only through a new invite., D6.
 */
let pinMismatchSub: ReturnType<typeof DeviceEventEmitter.addListener> | null = null;
const pinMismatchShown = new Set<string>();
export function installPinMismatchListener(): () => void {
  if (pinMismatchSub) return () => {};
  pinMismatchSub = DeviceEventEmitter.addListener(
    'wyrd_trust_pin_mismatch',
    (e: { host: string; newFingerprint: string; pinnedFingerprint: string }) => {
      const host = e?.host ?? '';
      // TLS retries repeat the event; say it once per host while the app runs.
      if (pinMismatchShown.has(host)) return;
      pinMismatchShown.add(host);
      // eslint-disable-next-line no-console
      console.warn(`[HouseholdTrust] refused ${host}: served ${e?.newFingerprint} does not match pin ${e?.pinnedFingerprint}`);
      const t = securityText();
      Alert.alert(t.pinMismatchTitle, t.pinMismatchBody(host), [{ text: t.ok }]);
    },
  );
  return () => {
    pinMismatchSub?.remove();
    pinMismatchSub = null;
  };
}

/** `home_ca_fp` (hex, any case, colons or not) → the store's UPPERCASE colon form. */
export function toColonHex(fp: string): string {
  const hex = fp.replace(/[:\s]/g, '').toUpperCase();
  return (hex.match(/.{2}/g) ?? []).join(':');
}

/**
 * Pin one of the home's network addresses (its lan_https, or its bus
 * websocket) to its household CA (`home_ca_fp` from the invite). The native
 * layer then accepts that host:port only with a chain that validates up to that
 * exact CA — for fetch(), the session websocket and the home-bus websocket.
 */
export async function pinHomeCa(url: string, homeCaFp: string): Promise<boolean> {
  const key = pinKeyForUrl(url);
  if (!nativeTrust?.pinCaFingerprint || !key || !homeCaFp) return false;
  try {
    return await nativeTrust.pinCaFingerprint(key, toColonHex(homeCaFp));
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn(`[HouseholdTrust] CA pin failed for ${key}:`, e);
    return false;
  }
}

/** The household CA fingerprint for `url` when an invite named exactly that host and port as its home. */
export function knownHomeCaFor(url: string): string | null {
  for (const t of Object.values(useZoneBankStore.getState().trust ?? {})) {
    if (t.homeCaFp && homeEndpoints(t).some((u) => sameEndpoint(u, url))) return t.homeCaFp;
  }
  return null;
}

/** Before connecting to `url`: pin it to its zone's household CA when an invite named that address. */
export async function pinKnownHome(url: string): Promise<void> {
  const fp = knownHomeCaFor(url);
  if (fp) await pinHomeCa(url, fp);
}

/**
 * Fill the native pin stores from secure storage. They keep pins in memory
 * only, so this runs at every start, before any screen can connect: the
 * certificates matched from invites (@wyrd_trust_*), the held relays'
 * fingerprints, and each zone's household CA for its home-network addresses
 * (lan_https and the home bus, each its own host:port).
 */
export async function restoreNativePins(): Promise<void> {
  if (!nativeTrust) return;
  const keys = await AsyncStorage.keys();
  for (const k of keys.filter((key) => key.startsWith(STORAGE_PREFIX))) {
    try {
      const rec = JSON.parse((await AsyncStorage.getItem(k)) ?? 'null') as PinnedHouseholdTrust | null;
      if (rec?.host && rec.certPem) await nativeTrust.addTrustedCert(rec.host, rec.certPem);
    } catch {
      /* one unreadable record must not stop the rest */
    }
  }
  const bank = useZoneBankStore.getState();
  for (const relay of bank.relays) {
    const fps = [relay.caFp, relay.fp].filter((f): f is string => !!f);
    if (fps.length > 0) await pinInviteFingerprints(relay.wsUrl, fps);
  }
  for (const t of Object.values(bank.trust ?? {})) {
    if (!t.homeCaFp) continue;
    for (const u of homeEndpoints(t)) await pinHomeCa(u, t.homeCaFp);
  }
}
