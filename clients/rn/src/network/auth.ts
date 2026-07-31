/** Auth client — register, login, me */

function normalizeUrl(url: string): string {
  let u = url.trim().replace(/\/$/, '');
  if (!/^https?:\/\//i.test(u)) {
    u = 'http://' + u;
  }
  return u;
}

export interface AuthResponse {
  token: string;
  user_id: string;
  username: string;
}

export interface UserInfo {
  user_id: string;
  username: string;
  display_name: string | null;
}

export async function register(
  baseUrl: string,
  username: string,
  password: string,
  displayName: string,
): Promise<AuthResponse> {
  const res = await fetch(`${normalizeUrl(baseUrl)}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, display_name: displayName }),
  });
  if (!res.ok) throw new Error(`Register failed: ${res.status}`);
  return res.json();
}

export async function login(
  baseUrl: string,
  username: string,
  password: string,
): Promise<AuthResponse> {
  const res = await fetch(`${normalizeUrl(baseUrl)}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error(`Login failed: ${res.status}`);
  return res.json();
}

export async function me(baseUrl: string, token: string): Promise<UserInfo> {
  const res = await fetch(`${normalizeUrl(baseUrl)}/api/auth/me?token=${token}`);
  if (!res.ok) throw new Error(`Me failed: ${res.status}`);
  return res.json();
}
