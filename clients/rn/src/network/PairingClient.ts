/**
 * PairingClient — HTTP client for the server pairing API.
 *
 * Used by the first-run wizard to pair a phone with a household server.
 */

export interface PairingChallenge {
  challengeId: string;
  expiresIn: number;
}

export interface PairingCredentials {
  token: string;
  householdId: string;
  householdName: string;
  serverDid: string;
  natsUrl: string;
  serverUrl: string;
  relayUrl: string | null;
  relayToken: string | null;
}

function normalizeUrl(url: string): string {
  const trimmed = url.trim().replace(/\/+$/, '');
  if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
    return trimmed;
  }
  return `http://${trimmed}`;
}

/** Request pairing with a server. Returns challenge info or null on error. */
export async function requestPairing(
  serverUrl: string,
  deviceName: string,
  deviceType: string,
): Promise<PairingChallenge | null> {
  try {
    const url = normalizeUrl(serverUrl);
    const resp = await fetch(`${url}/api/pair/request`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceName, deviceType }),
    });
    if (resp.ok) {
      return (await resp.json()) as PairingChallenge;
    }
    return null;
  } catch {
    return null;
  }
}

/** Verify a pairing code. Returns credentials or null. */
export async function verifyCode(
  serverUrl: string,
  challengeId: string,
  code: string,
): Promise<PairingCredentials | null> {
  try {
    const url = normalizeUrl(serverUrl);
    const resp = await fetch(`${url}/api/pair/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ challengeId, code }),
    });
    if (resp.ok) {
      return (await resp.json()) as PairingCredentials;
    }
    return null;
  } catch {
    return null;
  }
}

/** Check if a device token is still valid. */
export async function checkStatus(
  serverUrl: string,
  token: string,
): Promise<boolean> {
  try {
    const url = normalizeUrl(serverUrl);
    const resp = await fetch(`${url}/api/pair/status`, {
      method: 'GET',
      headers: { Authorization: `Bearer ${token}` },
    });
    return resp.ok;
  } catch {
    return false;
  }
}
