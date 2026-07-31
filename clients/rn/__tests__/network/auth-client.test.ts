/**
 * AuthClient unit tests.
 *
 * Tests the AuthClient HTTP helper functions by mocking global fetch.
 * Verifies correct URL construction, request bodies, response mapping,
 * and error handling for all four endpoints.
 */

import {
  checkStatus,
  login,
  register,
  linkDevice,
} from '../../src/network/AuthClient';

// Mock global fetch
const mockFetch = jest.fn();
(globalThis as any).fetch = mockFetch;

beforeEach(() => {
  mockFetch.mockReset();
});

describe('checkStatus', () => {
  test('returns server status with hasUsers=true', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ has_users: true, open_registration: true }),
    });

    const result = await checkStatus('http://localhost:8080');
    expect(result.hasUsers).toBe(true);
    expect(result.openRegistration).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://localhost:8080/api/auth/status');
  });

  test('returns hasUsers=false for fresh server', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ has_users: false, open_registration: true }),
    });

    const result = await checkStatus('http://localhost:8080');
    expect(result.hasUsers).toBe(false);
  });

  test('normalizes URL without protocol', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ has_users: true }),
    });

    await checkStatus('198.51.100.10:8080');
    expect(mockFetch).toHaveBeenCalledWith('http://198.51.100.10:8080/api/auth/status');
  });

  test('strips trailing slashes', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ has_users: true }),
    });

    await checkStatus('http://localhost:8080///');
    expect(mockFetch).toHaveBeenCalledWith('http://localhost:8080/api/auth/status');
  });

  test('throws on non-OK response', async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 500 });
    await expect(checkStatus('http://localhost:8080')).rejects.toThrow('500');
  });

  test('defaults openRegistration to true when missing', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ has_users: true }),
    });

    const result = await checkStatus('http://localhost:8080');
    expect(result.openRegistration).toBe(true);
  });
});

describe('login', () => {
  test('sends correct request and maps response', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        token: 'tok-abc',
        user_id: 'uid-1',
        username: 'alice',
        role: 'steward',
      }),
    });

    const result = await login('http://localhost:8080', 'alice', 'secret');
    expect(result.token).toBe('tok-abc');
    expect(result.userId).toBe('uid-1');
    expect(result.username).toBe('alice');
    expect(result.role).toBe('steward');

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/auth/login',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'alice', password: 'secret' }),
      }),
    );
  });

  test('defaults role to user when missing', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        token: 'tok-abc',
        user_id: 'uid-1',
        username: 'alice',
      }),
    });

    const result = await login('http://localhost:8080', 'alice', 'secret');
    expect(result.role).toBe('user');
  });

  test('throws helpful message on 401', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 401,
    });

    await expect(login('http://localhost:8080', 'alice', 'wrong')).rejects.toThrow(
      'Invalid username or password.',
    );
  });

  test('throws on generic error', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      text: async () => 'Internal Server Error',
    });

    await expect(login('http://localhost:8080', 'alice', 'pw')).rejects.toThrow(
      'Login failed: 500',
    );
  });
});

describe('register', () => {
  test('sends correct request with display name', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        token: 'tok-new',
        user_id: 'uid-2',
        username: 'bob',
        role: 'steward',
      }),
    });

    const result = await register('http://localhost:8080', 'bob', 'password', 'Bob');
    expect(result.token).toBe('tok-new');
    expect(result.userId).toBe('uid-2');
    expect(result.role).toBe('steward');

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/auth/register',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          username: 'bob',
          password: 'password',
          display_name: 'Bob',
        }),
      }),
    );
  });

  test('uses username as display name when not provided', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        token: 'tok-x',
        user_id: 'uid-3',
        username: 'carol',
        role: 'user',
      }),
    });

    await register('http://localhost:8080', 'carol', 'pw');
    const body = JSON.parse(mockFetch.mock.calls[0][1].body);
    expect(body.display_name).toBe('carol');
  });

  test('throws helpful message on 409 (username taken)', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 409,
    });

    await expect(
      register('http://localhost:8080', 'alice', 'pw'),
    ).rejects.toThrow('Username already taken.');
  });

  test('throws on generic error with "taken" in body', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 400,
      text: async () => 'username already taken',
    });

    await expect(
      register('http://localhost:8080', 'alice', 'pw'),
    ).rejects.toThrow('Username already taken.');
  });
});

describe('linkDevice', () => {
  test('sends correct request with Bearer auth', async () => {
    mockFetch.mockResolvedValueOnce({ ok: true });

    const result = await linkDevice(
      'http://localhost:8080',
      'tok-abc',
      'device-xyz',
    );
    expect(result).toBe(true);

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/auth/link-device',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer tok-abc',
        },
        body: JSON.stringify({ deviceToken: 'device-xyz' }),
      }),
    );
  });

  test('returns false on non-OK response', async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 403 });

    const result = await linkDevice('http://localhost:8080', 'tok', 'dev');
    expect(result).toBe(false);
  });

  test('returns false on network error (non-fatal)', async () => {
    mockFetch.mockRejectedValueOnce(new Error('Network error'));

    const result = await linkDevice('http://localhost:8080', 'tok', 'dev');
    expect(result).toBe(false);
  });
});
