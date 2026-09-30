/**
 * Since 0.5.0 the home puts a login check in front of every /api route but a
 * few public ones, and /api/study/* takes the owner from the login (a `user`
 * naming someone else is refused). Every HTTP client call the phone makes to a
 * gated route must carry its token; the journal write must not name a user.
 */
import { ServerClient } from '../../src/server/ServerClient';
import { getLatestManifest, syncManifest } from '../../src/network/SoulClient';
import { getSoulLatest, getSoulHistory, getSoulVersion, syncSoulManifest } from '../../src/network/soul';
import { fetchHouseholdSouls, importFromHousehold } from '../../src/engine/soul/SoulSeedImporter';
import { linkDevice } from '../../src/network/AuthClient';
import { checkStatus as pairStatus } from '../../src/network/PairingClient';
import { me } from '../../src/network/auth';

const mockFetch = jest.fn();
(globalThis as { fetch?: unknown }).fetch = mockFetch;

const BASE = 'https://home.example:7443';

function lastCall(): { url: string; headers: Record<string, string>; body: unknown } {
  const [url, init] = mockFetch.mock.calls[mockFetch.mock.calls.length - 1] as [string, RequestInit | undefined];
  return {
    url,
    headers: (init?.headers ?? {}) as Record<string, string>,
    body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
  };
}

beforeEach(() => {
  mockFetch.mockReset();
  mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ ok: true, results: [], id: 'e1' }) });
});

describe('gated /api routes carry the login', () => {
  it('ServerClient study journal: Bearer token, no user named', async () => {
    const c = new ServerClient(BASE, 'sess-1');
    await c.writeJournal('did:someone-else', 'hello', true);
    const call = lastCall();
    expect(call.url).toBe(`${BASE}/api/study/journal`);
    expect(call.headers.Authorization).toBe('Bearer sess-1');
    expect(call.body).toEqual({ content: 'hello', isPrivate: true });
  });

  it('ServerClient library search, tell and do: Bearer token', async () => {
    const c = new ServerClient(BASE, 'sess-1');
    await c.searchLibrary('ferns');
    expect(lastCall().url).toContain('/api/library/search?q=ferns');
    expect(lastCall().headers.Authorization).toBe('Bearer sess-1');
    await c.tell('mia', 'hi');
    expect(lastCall().headers.Authorization).toBe('Bearer sess-1');
    await c.doCommand('look');
    expect(lastCall().headers.Authorization).toBe('Bearer sess-1');
  });

  it('ServerClient without a login sends nothing to gated routes', async () => {
    const c = new ServerClient(BASE);
    expect((await c.writeJournal('u', 'x')).status).toBe(401);
    expect((await c.searchLibrary('x')).status).toBe(401);
    expect(mockFetch).not.toHaveBeenCalled();
  });

  it('soul routes carry the token', async () => {
    await getLatestManifest(BASE, 'did:c', 'tok');
    expect(lastCall().url).toBe(`${BASE}/api/soul/did%3Ac?token=tok`);
    await syncManifest(BASE, 'did:c', {} as never, 'tok');
    expect(lastCall().url).toBe(`${BASE}/api/soul/did%3Ac?token=tok`);
    await getSoulLatest(BASE, 'did:c', 'tok').catch(() => {});
    expect(lastCall().url).toContain('token=tok');
    await getSoulHistory(BASE, 'did:c', 'tok').catch(() => {});
    expect(lastCall().url).toContain('token=tok');
    await getSoulVersion(BASE, 'did:c', 3, 'tok').catch(() => {});
    expect(lastCall().url).toContain('token=tok');
    await syncSoulManifest(BASE, 'did:c', {} as never, 'tok').catch(() => {});
    expect(lastCall().url).toContain('token=tok');
    await fetchHouseholdSouls(BASE, 'tok');
    expect(lastCall().url).toBe(`${BASE}/api/soul/list?token=tok`);
    await importFromHousehold(BASE, 'did:c', 'tok');
    expect(lastCall().url).toBe(`${BASE}/api/soul/did%3Ac?token=tok`);
  });

  it('auth/me, auth/link-device and pair/status carry the token', async () => {
    await me(BASE, 'tok').catch(() => {});
    expect(lastCall().url).toBe(`${BASE}/api/auth/me?token=tok`);
    await linkDevice(BASE, 'auth-tok', 'dev-tok');
    expect(lastCall().headers.Authorization).toBe('Bearer auth-tok');
    await pairStatus(BASE, 'dev-tok');
    expect(lastCall().url).toBe(`${BASE}/api/pair/status`);
    expect(lastCall().headers.Authorization).toBe('Bearer dev-tok');
  });
});
