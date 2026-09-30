/**
 * Pure address checks, with no app state (, W2): the
 * phone never sends to another machine in the clear. Split from secureAddress
 * so the transport and inference layers can use them without loading stores.
 */

/** `host:port` → `http://host:port`, the way the connect screens always read it. */
export function withScheme(input: string): string {
  const t = input.trim();
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(t) ? t : `http://${t}`;
}

export function isLoopbackHost(host: string): boolean {
  const h = host.toLowerCase().replace(/^\[|\]$/g, '');
  return h === 'localhost' || h === '::1' || /^127\./.test(h);
}

/**
 * The TLS endpoint an address names: host (lowercase, IPv6 without brackets)
 * and port (443 for https/wss and 80 for http/ws when none is written), or
 * null. Parsed by hand: React Native's URL reads `hostname` for http(s) only,
 * so a wss:// address would have no host on the phone.
 */
export function endpointOf(url: string): { host: string; port: number } | null {
  const m = /^([a-z][a-z0-9+.-]*):\/\/(?:[^@/?#]*@)?(\[[^\]]+\]|[^:/?#[\]]+)(?::(\d+))?(?=[/?#]|$)/i.exec(url.trim());
  if (!m) return null;
  const scheme = m[1].toLowerCase();
  const host = m[2].replace(/^\[|\]$/g, '').toLowerCase();
  const port = m[3] ? Number(m[3])
    : scheme === 'https' || scheme === 'wss' ? 443
    : scheme === 'http' || scheme === 'ws' ? 80
    : NaN;
  if (!host || !Number.isInteger(port) || port <= 0 || port > 65535) return null;
  return { host, port };
}

/** Whether two addresses name the same host and port (https://h:7443 and wss://h:7443 do). */
export function sameEndpoint(a: string, b: string): boolean {
  const x = endpointOf(a);
  const y = endpointOf(b);
  return !!x && !!y && x.host === y.host && x.port === y.port;
}

/** True for http:// and ws:// to anything but this device. Unparseable counts as unsafe. */
export function isPlaintextToNetwork(url: string): boolean {
  const t = withScheme(url);
  const scheme = /^([a-z][a-z0-9+.-]*):\/\//i.exec(t)?.[1]?.toLowerCase();
  const e = endpointOf(t);
  if (!scheme || !e) return true;
  if (scheme !== 'http' && scheme !== 'ws') return false;
  return !isLoopbackHost(e.host);
}
