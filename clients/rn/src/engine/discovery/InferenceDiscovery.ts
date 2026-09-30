/**
 * Discovers inference endpoints and Wyrdsekai servers on the local network and on-device.
 *
 * Strategy:
 * 1. If a saved URL exists (user explicitly configured), probe it first — only
 *    when it is encrypted (https, pinned first when an invite named it) or on
 *    this device
 * 2. Try common localhost ports (for on-device servers)
 * 3. Return all responsive endpoints
 *
 * No plain-http inference address on the home network is ever probed or
 * offered: prompts would cross the network in the clear. Inference reaches
 * the home over its pinned HTTPS (or through the relay).
 *
 * Wyrdsekai homes are never found by scanning: since 0.5.0 a home answers
 * plain http://<ip>:7070 on its own machine only, and a phone must not trust a
 * home it found on the network by itself. A home becomes known through its
 * invite (QR or link: lan_https + home_ca_fp), and only such homes are listed,
 * over HTTPS pinned to their household CA ( W2).
 *
 * Uses short timeouts (2s) so discovery completes quickly even when
 * endpoints are unreachable.
 *
 */

import { isPlaintextToNetwork } from '../../network/plainAddress';

export interface DiscoveredInference {
  /** Base URL of the inference server (e.g., "http://198.51.100.10:11434") */
  url: string;
  /** Server type: "ollama", "llama-server", "openai-compat", or "wyrdsekai" */
  type: 'ollama' | 'llama-server' | 'openai-compat' | 'wyrdsekai';
  /** Human-readable label for display in settings UI */
  label: string;
  /** NATS URL from /health response (Wyrdsekai servers only) */
  natsUrl?: string | null;
  /** Relay URL from /health response (Wyrdsekai servers only) */
  relayUrl?: string | null;
}

const PROBE_TIMEOUT_MS = 2_000;

/**
 * Probe a URL with a short timeout. Returns true if 2xx response.
 */
async function probe(url: string): Promise<boolean> {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
    const response = await fetch(url, { signal: controller.signal });
    clearTimeout(timeout);
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Probe a Wyrdsekai server's /health endpoint and parse natsUrl/relayUrl.
 * Returns a DiscoveredInference if the server is responsive, null otherwise.
 */
async function probeWyrdsekai(baseUrl: string, label?: string): Promise<DiscoveredInference | null> {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
    const response = await fetch(`${baseUrl}/health`, { signal: controller.signal });
    clearTimeout(timeout);
    if (!response.ok) return null;

    let name = extractHostname(baseUrl);
    let natsUrl: string | null = null;
    let relayUrl: string | null = null;
    try {
      const body = await response.json();
      name = body.name ?? body.server ?? extractHostname(baseUrl);
      natsUrl = body.natsUrl ?? null;
      relayUrl = body.relayUrl ?? null;
    } catch {
      // Parse failure is non-fatal
    }

    return {
      url: baseUrl,
      type: 'wyrdsekai',
      label: label ?? `${name} (${baseUrl})`,
      natsUrl,
      relayUrl,
    };
  } catch {
    return null;
  }
}

function extractHostname(url: string): string {
  try {
    return url.replace(/^https?:\/\//, '').split(':')[0].split('/')[0];
  } catch {
    return url;
  }
}

/**
 * Detect server type from URL. Port 11434 = Ollama, else OpenAI-compatible.
 */
function detectType(url: string): 'ollama' | 'llama-server' | 'openai-compat' {
  return url.includes(':11434') ? 'ollama' : 'openai-compat';
}

/**
 * Discover inference endpoints.
 *
 * @param opts.savedUrl User-configured inference URL (from secure storage)
 * @returns All responsive endpoints, ordered by priority (saved > local)
 */
export async function discoverInference(opts?: {
  savedUrl?: string;
}): Promise<DiscoveredInference[]> {
  const results: DiscoveredInference[] = [];
  const savedUrl = opts?.savedUrl;

  // Saved URL first (user explicitly configured) — never over plain http to another machine.
  if (savedUrl && !isPlaintextToNetwork(savedUrl)) {
    const { pinKnownHome } = await import('../../server/HouseholdTrust');
    await pinKnownHome(savedUrl);
    if (await probe(savedUrl)) {
      results.push({ url: savedUrl, type: detectType(savedUrl), label: 'Saved endpoint' });
    }
  }

  // Localhost probes (on-device)
  for (const port of [8080, 11434]) {
    const url = `http://localhost:${port}`;
    const probeUrl = port === 11434 ? `${url}/api/tags` : `${url}/health`;
    if (await probe(probeUrl)) {
      const type: DiscoveredInference['type'] = port === 11434 ? 'ollama' : 'llama-server';
      results.push({ url, type, label: `Local (${port})` });
    }
  }

  return results;
}

/**
 * The Wyrdsekai homes this phone knows from its invites that answer on the
 * home network right now: each invite's lan_https, pinned to its home_ca_fp
 * before the probe. There is no subnet scan and no trust on first use — a home
 * the phone has no invite for is not listed (pair with `wyrd phone invite`).
 */
export async function discoverWyrdsekaiServers(): Promise<DiscoveredInference[]> {
  const { useZoneBankStore } = await import('../../state/zoneBankStore');
  const bank = useZoneBankStore.getState();
  if (!bank.loaded) await bank.loadFromStorage();
  const homes = new Set<string>();
  for (const t of Object.values(useZoneBankStore.getState().trust ?? {})) {
    if (t.lanHttps && t.homeCaFp) homes.add(t.lanHttps);
  }
  if (homes.size === 0) return [];
  const { pinKnownHome } = await import('../../server/HouseholdTrust');
  const found = await Promise.all([...homes].map(async (url) => {
    await pinKnownHome(url);
    return probeWyrdsekai(url, `Household server (${extractHostname(url)})`);
  }));
  return found.filter((d): d is DiscoveredInference => d != null);
}

/**
 * Pick the best endpoint from discovered list.
 *
 * Priority: saved > wyrdsekai server > household ollama > household other > local.
 */
export function bestEndpoint(discovered: DiscoveredInference[]): DiscoveredInference | null {
  return (
    discovered.find(d => d.label.startsWith('Saved')) ??
    discovered.find(d => d.type === 'wyrdsekai') ??
    discovered.find(d => d.type === 'ollama' && !d.label.startsWith('Local')) ??
    discovered.find(d => !d.label.startsWith('Local')) ??
    discovered[0] ??
    null
  );
}
