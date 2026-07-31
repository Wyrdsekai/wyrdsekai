/**
 * Discovers inference endpoints and Wyrdsekai servers on the local network and on-device.
 *
 * Strategy:
 * 1. If a saved URL exists (user explicitly configured), probe it first
 * 2. If household IP known (from SavedHouseholdConfig), probe well-known ports
 * 3. Try common localhost ports (for on-device servers)
 * 4. Return all responsive endpoints
 *
 * Uses short timeouts (2s) so discovery completes quickly even when
 * endpoints are unreachable.
 *
 */

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
 * @param opts.householdHost IP or hostname of the household server (from mDNS or saved config)
 * @param opts.savedUrl User-configured inference URL (from AsyncStorage)
 * @returns All responsive endpoints, ordered by priority (saved > household > local)
 */
export async function discoverInference(opts?: {
  householdHost?: string;
  savedUrl?: string;
}): Promise<DiscoveredInference[]> {
  const results: DiscoveredInference[] = [];
  const householdHost = opts?.householdHost;
  const savedUrl = opts?.savedUrl;

  // Saved URL first (user explicitly configured)
  if (savedUrl) {
    if (await probe(savedUrl)) {
      results.push({ url: savedUrl, type: detectType(savedUrl), label: 'Saved endpoint' });
    }
  }

  // Probe household host at well-known ports
  if (householdHost) {
    // Wyrdsekai server on port 7070 (primary — gives us natsUrl)
    const wyrdUrl = `http://${householdHost}:7070`;
    const wyrdServer = await probeWyrdsekai(wyrdUrl, `Household server (${householdHost})`);
    if (wyrdServer) {
      results.push(wyrdServer);
    }

    const ollamaUrl = `http://${householdHost}:11434`;
    if (await probe(`${ollamaUrl}/api/tags`)) {
      results.push({ url: ollamaUrl, type: 'ollama', label: `Household Ollama (${householdHost})` });
    }

    const llamaUrl = `http://${householdHost}:8080`;
    if (await probe(`${llamaUrl}/health`)) {
      results.push({ url: llamaUrl, type: 'llama-server', label: `Household llama-server (${householdHost})` });
    }

    // SGLang / vLLM common port
    const sglangUrl = `http://${householdHost}:30000`;
    if (await probe(`${sglangUrl}/health`)) {
      results.push({ url: sglangUrl, type: 'openai-compat', label: `Household inference (${householdHost}:30000)` });
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
 * Scan the local /24 subnet for Wyrdsekai servers on port 7070.
 *
 * This is the RN equivalent of KMP's InferenceDiscovery.discover() subnet scan.
 * Probes all 254 IPs in parallel with short timeouts.
 *
 * @param localSubnet Subnet prefix (e.g., "192.168.1"). If not provided, tries common subnets.
 * @returns All discovered Wyrdsekai servers with natsUrl from /health
 */
export async function discoverWyrdsekaiServers(
  localSubnet?: string,
): Promise<DiscoveredInference[]> {
  const subnets = localSubnet ? [localSubnet] : detectLocalSubnets();
  const results: DiscoveredInference[] = [];

  // Probe all IPs in each subnet in parallel
  const probePromises: Promise<DiscoveredInference | null>[] = [];
  for (const subnet of subnets) {
    for (let host = 1; host <= 254; host++) {
      const ip = `${subnet}.${host}`;
      const url = `http://${ip}:7070`;
      probePromises.push(probeWyrdsekai(url));
    }
  }

  const probeResults = await Promise.all(probePromises);
  for (const server of probeResults) {
    if (server && !results.some(r => r.url === server.url)) {
      results.push(server);
    }
  }

  return results;
}

/**
 * Try to detect local subnets. Falls back to common home subnets.
 */
function detectLocalSubnets(): string[] {
  // React Native doesn't have NetworkInterface access.
  // Use common home subnet prefixes as fallback.
  return ['192.168.1', '192.168.10', '192.168.0'];
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
