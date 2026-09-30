/**
 * The home bus on the home network (, W2 + D1/D3).
 *
 * Since 0.5.0 the home's NATS websocket takes TLS from the household CA and
 * refuses anonymous clients. A phone uses it only when it has all of:
 *   • the household CA fingerprint from its invite (zoneBankStore.trust.homeCaFp)
 *     — the connection is pinned to it;
 *   • the bus address: the invite's `home_bus`, else the `natsUrl` of a pairing
 *     reply (both wss://<lan>:<bus port + 1>), else — only for an invite that
 *     carries neither — the invite's lan_https host on the default port 4223;
 *   • its own home-bus credentials from the pairing reply (`nats_user`/`nats_pass`,
 *     from /api/pair/verify or the relay's `pair.device`).
 * Otherwise the phone stays on the relay. It never falls back to plain ws://.
 *
 * What a phone's bus login may use is narrow (HouseholdBus.phonePermissions on
 * the home): sealed requests, the tunnel, replies on `_INBOX.<nats_user>`, and
 * Study sync addressed by its login name — see PhoneNode.setBetween(busUser).
 */
import { secureStorage } from '../state/secureStorage';
import { useZoneBankStore, type ZoneTrust } from '../state/zoneBankStore';
import { randomHex } from '../crypto/random';
import { endpointOf, lanHost } from '../network/secureAddress';

export const HOME_NATS_USER_KEY = '@wyrd_home_nats_user';
export const HOME_NATS_PASS_KEY = '@wyrd_home_nats_pass';
/** The bus address a pairing reply gave (its `natsUrl`), when it is a wss:// address. */
export const HOME_BUS_URL_KEY = '@wyrd_home_bus_url';
const DEVICE_LABEL_KEY = '@wyrd_device_label';
/** The home's default bus websocket port; used only when no invite or pairing reply names the bus. */
export const HOME_BUS_PORT = 4223;

export interface HomeBus {
  /** wss://<lan host>:<port> */
  url: string;
  host: string;
  homeCaFp: string;
  /** The phone's login on the bus; also its name there (inbox, Study sync). */
  user: string;
  pass: string;
}

/** Keeps the home-bus credentials from a pairing reply, when it carries them. */
export async function saveHomeBusCredentials(user?: string, pass?: string): Promise<void> {
  if (!user || !pass) return;
  await secureStorage.setItem(HOME_NATS_USER_KEY, user);
  await secureStorage.setItem(HOME_NATS_PASS_KEY, pass);
}

/** Keeps the bus address from a pairing reply (`natsUrl`) — a wss:// address only. */
export async function saveHomeBusUrl(url?: string | null): Promise<void> {
  const u = typeof url === 'string' ? url.trim().replace(/\/+$/, '') : '';
  if (!/^wss:\/\//i.test(u) || !endpointOf(u)) return;
  await secureStorage.setItem(HOME_BUS_URL_KEY, u);
}

/**
 * The bus address for a home: the invite's `home_bus`, else the pairing
 * reply's, else the invite's lan_https host on 4223. Null when none exists.
 */
export function homeBusUrl(trust: ZoneTrust | undefined, pairedUrl: string | null): string | null {
  if (trust?.homeBus) return trust.homeBus;
  if (pairedUrl && /^wss:\/\//i.test(pairedUrl) && endpointOf(pairedUrl)) return pairedUrl;
  const host = trust?.lanHttps ? lanHost(trust.lanHttps)?.replace(/^\[|\]$/g, '') : null;
  if (!host) return null;
  return `wss://${host.includes(':') ? `[${host}]` : host}:${HOME_BUS_PORT}`;
}

/** The pinned home-bus connection for `zoneId`, or null when the phone lacks any part of it. */
export async function resolveHomeBus(zoneId: string | null): Promise<HomeBus | null> {
  if (!zoneId) return null;
  const trust = useZoneBankStore.getState().getZoneTrust(zoneId);
  if (!trust?.homeCaFp) return null;
  const url = homeBusUrl(trust, await secureStorage.getItem(HOME_BUS_URL_KEY));
  const host = url ? endpointOf(url)?.host : null;
  if (!url || !host) return null;
  const user = await secureStorage.getItem(HOME_NATS_USER_KEY);
  const pass = await secureStorage.getItem(HOME_NATS_PASS_KEY);
  if (!user || !pass) return null;
  return { url, host, homeCaFp: trust.homeCaFp, user, pass };
}

/** A stable name for this phone in the home's device list. */
async function deviceLabel(): Promise<string> {
  let label = await secureStorage.getItem(DEVICE_LABEL_KEY);
  if (!label) {
    label = `phone-${randomHex(3)}`;
    await secureStorage.setItem(DEVICE_LABEL_KEY, label);
  }
  return label;
}

/**
 * After a relay login: when the invite gave this phone the home's network
 * address and CA but it holds no home-bus credentials yet, ask the home for
 * them through the (sealed) `pair.device` request. Best-effort.
 */
export async function ensureHomeBusCredentials(
  client: { pairDevice?: (name: string) => Promise<{ natsUser?: string; natsPass?: string; natsUrl?: string } | null> },
  zoneId: string,
): Promise<void> {
  const trust = useZoneBankStore.getState().getZoneTrust(zoneId);
  if (!trust?.homeCaFp || !(trust.homeBus || trust.lanHttps) || typeof client.pairDevice !== 'function') return;
  if (await secureStorage.getItem(HOME_NATS_USER_KEY)) return;
  const r = await client.pairDevice(await deviceLabel());
  await saveHomeBusCredentials(r?.natsUser, r?.natsPass);
  await saveHomeBusUrl(r?.natsUrl);
}

/**
 * Which Between leg the phone may open, and what to tell the person once.
 *
 * Only the pinned home bus. Not the relay: Between on the relay is plain
 * pub/sub (Study items and the session token on `between.{zone}.*.*.study.*`),
 * readable by the relay operator and by the zone's other phones, which the
 * relay lets subscribe there; the sealed tunnel and sealed requests do not
 * cover it. Not a saved plain ws:// address either (
 * W2/W3). The Study still reaches the home through the relay with the sealed
 * `study.journal` requests and the sealed tunnel.
 */
export async function planBetweenLeg(
  zoneId: string | null,
  legacyLanUrl: string | null,
  onRelay: boolean,
): Promise<{ bus: HomeBus | null; notice: 'lanPairAgain' | 'pairAgain' | null }> {
  const bus = await resolveHomeBus(zoneId);
  if (bus) return { bus, notice: null };
  const trust = zoneId ? useZoneBankStore.getState().getZoneTrust(zoneId) : undefined;
  // Without the home key the relay does not work either; the relay login says
  // to pair again in that case.
  if (onRelay && trust?.zk && (legacyLanUrl || !trust.homeCaFp || !(trust.homeBus || trust.lanHttps))) {
    return { bus: null, notice: 'lanPairAgain' };
  }
  if (!onRelay && legacyLanUrl) return { bus: null, notice: 'pairAgain' };
  return { bus: null, notice: null };
}
