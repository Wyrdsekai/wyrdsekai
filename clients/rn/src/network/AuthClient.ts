/**
 * AuthClient — HTTP client for the account/auth API.
 *
 * Used by LoginScreen after pairing to create or authenticate a user
 * account on the household server. Mirrors the KMP AuthClient.
 *
 * Uses standard fetch() — no external HTTP library needed.
 */

export interface AuthResult {
  token: string;
  userId: string;
  username: string;
  role: string;
}

export interface ServerStatus {
  hasUsers: boolean;
  openRegistration: boolean;
}

function normalizeUrl(url: string): string {
  let u = url.trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(u)) {
    u = 'http://' + u;
  }
  return u;
}

/**
 * Query GET /api/auth/status to determine whether the server has
 * existing user accounts (and whether open registration is allowed).
 */
export async function checkStatus(serverUrl: string): Promise<ServerStatus> {
  const url = normalizeUrl(serverUrl);
  const res = await fetch(`${url}/api/auth/status`);
  if (!res.ok) {
    throw new Error(`Server status check failed: ${res.status}`);
  }
  const body = await res.json();
  return {
    hasUsers: body.hasUsers ?? body.has_users ?? false,
    openRegistration: body.openRegistration ?? body.open_registration ?? true,
  };
}

/**
 * POST /api/auth/login — authenticate with username + password.
 */
export async function login(
  serverUrl: string,
  username: string,
  password: string,
): Promise<AuthResult> {
  const url = normalizeUrl(serverUrl);
  const res = await fetch(`${url}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    if (res.status === 401) {
      throw new Error('Invalid username or password.');
    }
    const text = await res.text().catch(() => '');
    throw new Error(
      text.includes('username') || text.includes('password')
        ? 'Invalid username or password.'
        : `Login failed: ${res.status}`,
    );
  }
  const body = await res.json();
  return {
    token: body.token,
    userId: body.userId ?? body.user_id,
    username: body.username,
    role: body.role ?? 'user',
  };
}

/**
 * POST /api/auth/register — create a new account.
 */
export async function register(
  serverUrl: string,
  username: string,
  password: string,
  displayName?: string,
): Promise<AuthResult> {
  const url = normalizeUrl(serverUrl);
  const res = await fetch(`${url}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username,
      password,
      display_name: displayName ?? username,
    }),
  });
  if (!res.ok) {
    if (res.status === 409) {
      throw new Error('Username already taken.');
    }
    const text = await res.text().catch(() => '');
    throw new Error(
      text.includes('taken') || text.includes('exists')
        ? 'Username already taken.'
        : `Registration failed: ${res.status}`,
    );
  }
  const body = await res.json();
  return {
    token: body.token,
    userId: body.userId ?? body.user_id,
    username: body.username,
    role: body.role ?? 'user',
  };
}

/**
 * POST /api/auth/link-device — bind a pairing device token to the
 * authenticated user account.
 */
export async function linkDevice(
  serverUrl: string,
  authToken: string,
  deviceToken: string,
): Promise<boolean> {
  const url = normalizeUrl(serverUrl);
  try {
    const res = await fetch(`${url}/api/auth/link-device`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${authToken}`,
      },
      body: JSON.stringify({ deviceToken }),
    });
    return res.ok;
  } catch {
    // Link failure is non-fatal — user is still authenticated
    return false;
  }
}
