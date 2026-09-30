/**
 * Addresses the phone may send to (, W2).
 *
 * The phone never talks to another machine in the clear: a typed http:// or
 * ws:// address on the network is refused, unless an invite gave this phone
 * the same host's https address (`lan_https`, pinned to the household CA), in
 * which case that address is used instead. The phone's own loopback stays
 * allowed (an inference server on the device).
 */
import { useZoneBankStore } from '../state/zoneBankStore';
import { securityText } from '../security/securityText';
import { withScheme, isPlaintextToNetwork } from './plainAddress';

export { withScheme, isLoopbackHost, endpointOf, sameEndpoint, isPlaintextToNetwork } from './plainAddress';

function parse(url: string): URL | null {
  try {
    return new URL(withScheme(url));
  } catch {
    return null;
  }
}

/** The host of an https address, or null. */
export function lanHost(lanHttps: string): string | null {
  const u = parse(lanHttps);
  return u && u.protocol === 'https:' && u.hostname ? u.hostname : null;
}

export type SecureAddress = { ok: true; url: string } | { ok: false; error: string };

/**
 * The address to use for a home the person typed or saved: as given when it is
 * already encrypted, the invite's https address for the same host when the phone
 * has one, otherwise a refusal with the plain reason.
 */
export function secureHomeAddress(input: string): SecureAddress {
  const url = withScheme(input).replace(/\/+$/, '');
  if (!isPlaintextToNetwork(url)) return { ok: true, url };
  const host = parse(url)?.hostname;
  const trust = Object.values(useZoneBankStore.getState().trust ?? {}).find(
    (t) => t.lanHttps && t.homeCaFp && host && lanHost(t.lanHttps) === host,
  );
  if (trust?.lanHttps) return { ok: true, url: trust.lanHttps };
  return { ok: false, error: securityText().plainAddressRefused };
}
