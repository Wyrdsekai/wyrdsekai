/**
 * OpenRouter OAuth PKCE flow — RN port.
 *
 * Mirrors the API of {@code OpenRouterOAuth.kt} (KMP) and
 * {@code OpenRouterOAuth.java} (server). See those for the full design.
 *
 * Flow:
 *   1. buildAuthUrl(LOOPBACK_CALLBACK) → {authUrl, pkce}
 *   2. Render authUrl inside a WebView (OpenRouterAuthScreen).
 *   3. WebView intercepts the {@link LOOPBACK_CALLBACK} navigation —
 *      the localhost URL never actually hits the network.
 *   4. Pull {@code ?code=...} from the intercepted URL, call exchangeCode.
 *   5. exchangeCode POSTs to OpenRouter and returns the API key.
 *
 * OpenRouter only permits https:443, https:3000, or http://localhost:3000
 * as callback URLs — explicitly no custom URI schemes. The loopback URL
 * doesn't need a listener; the WebView's URL-intercept aborts the load
 * before any TCP connection is attempted.
 */
import { sha256 } from 'js-sha256';

const AUTH_URL = 'https://openrouter.ai/auth';
const EXCHANGE_URL = 'https://openrouter.ai/api/v1/auth/keys';

/** Default callback for phone clients (intercepted in WebView). */
export const LOOPBACK_CALLBACK = 'http://localhost:3000/callback';

/** PKCE state — held between redirect and exchange. */
export interface PkceState {
  codeVerifier: string;
  codeChallenge: string;
}

export interface OAuthResult {
  key: string | null;
  error: string | null;
}

const VERIFIER_CHARS =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';

function generateCodeVerifier(): string {
  const buf = new Uint8Array(64);
  if (
    typeof globalThis !== 'undefined' &&
    (globalThis as any).crypto?.getRandomValues
  ) {
    (globalThis as any).crypto.getRandomValues(buf);
  } else {
    // Should not happen — index.js imports react-native-get-random-values.
    for (let i = 0; i < buf.length; i++) {
      buf[i] = Math.floor(Math.random() * 256);
    }
  }
  let out = '';
  for (let i = 0; i < buf.length; i++) {
    out += VERIFIER_CHARS[buf[i] % VERIFIER_CHARS.length];
  }
  return out;
}

function base64UrlEncodeBytes(bytes: ArrayLike<number>): string {
  let bin = '';
  for (let i = 0; i < (bytes as any).length; i++) {
    bin += String.fromCharCode((bytes as any)[i]);
  }
  // RN doesn't ship Buffer; `btoa` is provided by react-native's globals.
  const b64 =
    typeof btoa !== 'undefined'
      ? btoa(bin)
      : // Fallback — should not be reached in RN
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        (require('buffer').Buffer as typeof import('buffer').Buffer)
          .from(bin, 'binary')
          .toString('base64');
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function computeS256Challenge(verifier: string): string {
  // js-sha256 returns an ArrayBuffer when called with `.arrayBuffer()`.
  const digest = sha256.arrayBuffer(verifier);
  return base64UrlEncodeBytes(new Uint8Array(digest));
}

/**
 * Build the authorization URL to load in the WebView.
 * @returns [authUrl, pkceState]. Keep pkceState alive until exchangeCode.
 */
export function buildAuthUrl(callbackUrl: string): {
  authUrl: string;
  pkce: PkceState;
} {
  const verifier = generateCodeVerifier();
  const challenge = computeS256Challenge(verifier);
  const url =
    `${AUTH_URL}?callback_url=${encodeURIComponent(callbackUrl)}` +
    `&code_challenge=${challenge}` +
    `&code_challenge_method=S256`;
  return { authUrl: url, pkce: { codeVerifier: verifier, codeChallenge: challenge } };
}

/**
 * Exchange the authorization code for an API key.
 * Posts to {@code https://openrouter.ai/api/v1/auth/keys} with the verifier.
 */
export async function exchangeCode(
  code: string,
  pkce: PkceState,
): Promise<OAuthResult> {
  try {
    const res = await fetch(EXCHANGE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code,
        code_verifier: pkce.codeVerifier,
        code_challenge_method: 'S256',
      }),
    });
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      return { key: null, error: `Exchange failed (${res.status}): ${body}` };
    }
    const body = (await res.json()) as { key?: string };
    if (!body.key) {
      return { key: null, error: 'OpenRouter response missing key' };
    }
    return { key: body.key, error: null };
  } catch (e: any) {
    return { key: null, error: `Exchange error: ${e?.message ?? String(e)}` };
  }
}

/** Pull the {@code code} query param out of the intercepted callback URL. */
export function parseCodeFromCallbackUrl(url: string): string | null {
  const q = url.indexOf('?') >= 0 ? url.slice(url.indexOf('?') + 1) : '';
  if (!q) return null;
  for (const kv of q.split('&')) {
    const eq = kv.indexOf('=');
    if (eq > 0 && kv.slice(0, eq) === 'code') {
      return decodeURIComponent(kv.slice(eq + 1));
    }
  }
  return null;
}
