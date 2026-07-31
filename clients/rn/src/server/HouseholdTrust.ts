/**
 * HouseholdTrust — TOFU cert-pinning helper for the wyrdsekai phone client.
 *
 * Implementation status: Phase 1 (this file) — probe/fetch/fingerprint/store
 * with a native module hook left as TODO. Phase 2 will wire the native OkHttp
 * TrustManager to actually use stored pins. Until Phase 2 lands the user
 * still needs the household CA installed via Android Settings (or via ADB:
 * `adb push ca.crt /sdcard/ && Settings → Security → Install certificate`),
 * after which the existing `<certificates src="user"/>` trust anchor in
 * network_security_config.xml covers TLS.
 *
 * The Phase 1 work persists the cert + fingerprint so Phase 2 can pin
 * directly without re-prompting the user, and exposes the user-facing
 * confirmation UX shape (caller passes a `confirm` callback returning bool).
 *
 */

// Trust pins are credentials in the sense that anyone with them can MITM
// fewer hosts (forge them) — store in secureStorage, not plaintext
// AsyncStorage. The legacy @wyrd_trust_* AsyncStorage entries are wiped by
// initSecureStorage() on first cold start.
import { secureStorage as AsyncStorage } from '../state/secureStorage';
import { Alert, DeviceEventEmitter, NativeModules, Platform } from 'react-native';

const STORAGE_PREFIX = '@wyrd_trust_';

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
interface NativeHouseholdTrust {
  addTrustedCert(host: string, pem: string): Promise<boolean>;
  removeTrustedCert(host: string): Promise<boolean>;
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
  pinFingerprint?(host: string, fingerprint: string): Promise<boolean>;
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
  /** "tofu" = user-accepted self-signed CA; "system" = chain validated against public CA. */
  source: 'tofu' | 'system';
}

export interface TrustConfirmation {
  host: string;
  fingerprint: string;
  /** Bytes the user is being asked to trust, for display/printing the CA. */
  certPem: string;
}

export interface HouseholdTrustOptions {
  /**
   * Called when an HTTPS probe rejects the cert and we've fetched /ca.crt.
   * Implementation: present a UI showing the fingerprint + ask the user.
   * Return true to accept and store the pin, false to abort the connection.
   */
  confirm: (c: TrustConfirmation) => Promise<boolean>;
  /** Optional override for fetch (testing). */
  fetchImpl?: typeof fetch;
}

/** Read the SHA-256 fingerprint as colon-separated hex pairs, lowercased. */
async function sha256Hex(input: string): Promise<string> {
  // Browser-style: react-native runtime exposes globalThis.crypto.subtle.
  // (rn 0.79+ ships a polyfill that proxies to native CommonCrypto / Conscrypt.)
  const data = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest('SHA-256', data);
  const bytes = new Uint8Array(digest);
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join(':');
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
 * Persist a trust record. Used both by `probeAndTrust` after user confirm
 * and by callers who already have the cert (e.g. wired CA install flow).
 *
 * Two-write path: AsyncStorage (for JS-side reads) AND the native
 * HouseholdTrustStore (for OkHttp TLS pinning). The native call is what
 * actually unblocks HTTPS — the AsyncStorage entry is metadata.
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
  host: string,
  fingerprints: Array<string | undefined>,
): Promise<number> {
  if (!nativeTrust?.pinFingerprint) return 0;
  const wanted = fingerprints
    .filter((f): f is string => !!f)
    .map((f) => f.toUpperCase());
  let pinned = 0;
  for (const fp of wanted) {
    try {
      await nativeTrust.pinFingerprint(host, fp);
      pinned += 1;
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn(`[HouseholdTrust] direct pin failed for ${host} ${fp}:`, e);
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
    host,
    certPem: match.pem,
    fingerprint: match.fingerprint,
    trustedAt: Date.now(),
    source: 'tofu',
  };
  await setTrust(record);
  return record;
}

/**
 * Pin-mismatch recovery listener — wire this once at app startup. Native
 * TLS layer emits `wyrd_trust_pin_mismatch` when an existing pin doesn't
 * match the chain (i.e. operator ran `wyrd relay rotate-cert --ca`).
 * We pop an Alert showing the new fingerprint; on accept we clear the
 * pin so the next request flips into the TOFU path and re-pins. On
 * deny we do nothing — the request will keep failing until the user
 * either accepts or rolls back the relay cert.
 *
 */
let pinMismatchSub: ReturnType<typeof DeviceEventEmitter.addListener> | null = null;
export function installPinMismatchListener(): () => void {
  if (pinMismatchSub) return () => {};
  pinMismatchSub = DeviceEventEmitter.addListener(
    'wyrd_trust_pin_mismatch',
    async (e: { host: string; newFingerprint: string; pinnedFingerprint: string }) => {
      const { host, newFingerprint, pinnedFingerprint } = e;
      // Coalesce: if the user already saw this event for this host in the
      // last few seconds, don't re-prompt (TLS retries hammer the bus).
      const now = Date.now();
      const last = pinMismatchLastAt[host] ?? 0;
      if (now - last < 5000) return;
      pinMismatchLastAt[host] = now;

      const accepted = await new Promise<boolean>((resolve) => {
        Alert.alert(
          'Server certificate changed',
          `${host}\n\nThe TLS cert presented by this server does not match the\nfingerprint you previously trusted.\n\n` +
          `Pinned : ${pinnedFingerprint || '(unknown)'}\n` +
          `New    : ${newFingerprint}\n\n` +
          `If you (or your household steward) just rotated the cert,\n` +
          `accept the new fingerprint to continue. Otherwise this could\n` +
          `be an attempt to intercept your traffic — choose Cancel.`,
          [
            { text: 'Cancel', style: 'cancel', onPress: () => resolve(false) },
            { text: 'Trust new cert', style: 'destructive', onPress: () => resolve(true) },
          ],
          { cancelable: false },
        );
      });
      if (!accepted) return;
      try {
        await clearTrust(host);
        // Next HTTPS request hits the empty-pin branch → probeAndTrust
        // re-fetches /ca.crt and re-pins after the user confirms again.
      } catch {
        // best-effort — clearTrust is idempotent
      }
    },
  );
  return () => {
    pinMismatchSub?.remove();
    pinMismatchSub = null;
  };
}
const pinMismatchLastAt: Record<string, number> = {};
