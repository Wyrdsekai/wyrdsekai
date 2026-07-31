/**
 * LoginScreen logic tests.
 *
 * Tests the auth credential storage, navigation routing, and state management
 * used by LoginScreen WITHOUT rendering React components. We test the
 * underlying appModeStore auth actions directly.
 */

// appModeStore now persists credentials through `secureStorage` (encrypted
// MMKV), aliased internally as `AsyncStorage`. We mock that module directly so
// the test exercises the real appModeStore→secureStorage path without pulling
// in the native `react-native-mmkv`/`react-native` modules (which jest's
// node/ts-jest preset can't transform). The exported `secureStorage` object is
// an in-memory async store; assertions below verify its spy calls.
jest.mock('../../src/state/secureStorage', () => {
  const store = new Map<string, string>();
  return {
    __esModule: true,
    secureStorage: {
      getItem: jest.fn(async (key: string) => store.get(key) ?? null),
      setItem: jest.fn(async (key: string, value: string) => { store.set(key, value); }),
      removeItem: jest.fn(async (key: string) => { store.delete(key); }),
    },
    initSecureStorage: jest.fn(async () => {}),
    _store: store,
    _clear: () => store.clear(),
  };
});

import { useAppModeStore } from '../../src/state/appModeStore';

// The mocked module surface — `secureStorage` is what appModeStore writes to.
const SecureStorageMock = require('../../src/state/secureStorage');
const secureStorage = SecureStorageMock.secureStorage;

beforeEach(() => {
  // Reset store state
  useAppModeStore.setState({
    authToken: null,
    userId: null,
    userRole: null,
  });
  SecureStorageMock._clear();
  jest.clearAllMocks();
});

describe('appModeStore auth credential storage', () => {
  test('starts with null auth credentials', () => {
    const state = useAppModeStore.getState();
    expect(state.authToken).toBeNull();
    expect(state.userId).toBeNull();
    expect(state.userRole).toBeNull();
  });

  test('setAuth stores token, userId, and role', () => {
    useAppModeStore.getState().setAuth('tok-123', 'uid-abc', 'steward');

    const state = useAppModeStore.getState();
    expect(state.authToken).toBe('tok-123');
    expect(state.userId).toBe('uid-abc');
    expect(state.userRole).toBe('steward');
  });

  test('setAuth persists to AsyncStorage', () => {
    useAppModeStore.getState().setAuth('tok-456', 'uid-def', 'user');

    expect(secureStorage.setItem).toHaveBeenCalledWith('@wyrd_auth_token', 'tok-456');
    expect(secureStorage.setItem).toHaveBeenCalledWith('@wyrd_user_id', 'uid-def');
    expect(secureStorage.setItem).toHaveBeenCalledWith('@wyrd_user_role', 'user');
  });

  test('clearAuth resets credentials to null', () => {
    useAppModeStore.getState().setAuth('tok-x', 'uid-x', 'steward');
    useAppModeStore.getState().clearAuth();

    const state = useAppModeStore.getState();
    expect(state.authToken).toBeNull();
    expect(state.userId).toBeNull();
    expect(state.userRole).toBeNull();
  });

  test('clearAuth removes from AsyncStorage', () => {
    useAppModeStore.getState().setAuth('tok-x', 'uid-x', 'steward');
    jest.clearAllMocks();

    useAppModeStore.getState().clearAuth();

    expect(secureStorage.removeItem).toHaveBeenCalledWith('@wyrd_auth_token');
    expect(secureStorage.removeItem).toHaveBeenCalledWith('@wyrd_user_id');
    expect(secureStorage.removeItem).toHaveBeenCalledWith('@wyrd_user_role');
  });

  test('setAuth overwrites previous credentials', () => {
    useAppModeStore.getState().setAuth('tok-1', 'uid-1', 'user');
    useAppModeStore.getState().setAuth('tok-2', 'uid-2', 'steward');

    const state = useAppModeStore.getState();
    expect(state.authToken).toBe('tok-2');
    expect(state.userId).toBe('uid-2');
    expect(state.userRole).toBe('steward');
  });
});

describe('LoginScreen navigation routing logic', () => {
  test('user is logged in when authToken and userId are set', () => {
    useAppModeStore.getState().setAuth('tok', 'uid', 'user');

    const state = useAppModeStore.getState();
    const isLoggedIn = !!state.authToken && !!state.userId;
    expect(isLoggedIn).toBe(true);
  });

  test('user is not logged in when authToken is null', () => {
    const state = useAppModeStore.getState();
    const isLoggedIn = !!state.authToken && !!state.userId;
    expect(isLoggedIn).toBe(false);
  });

  test('user is not logged in after clearAuth', () => {
    useAppModeStore.getState().setAuth('tok', 'uid', 'steward');
    useAppModeStore.getState().clearAuth();

    const state = useAppModeStore.getState();
    const isLoggedIn = !!state.authToken && !!state.userId;
    expect(isLoggedIn).toBe(false);
  });

  test('paired local mode without auth triggers Login route', () => {
    // Simulate post-pairing state: local mode, has server URL, no auth
    useAppModeStore.setState({
      mode: 'local',
      firstRunComplete: true,
      inferenceUrl: 'http://198.51.100.10:8080',
      pairingToken: 'device-tok',
      authToken: null,
      userId: null,
    });

    const state = useAppModeStore.getState();
    const hasServer = !!state.inferenceUrl;
    const isLoggedIn = !!state.authToken && !!state.userId;
    // Should route to Login
    expect(hasServer && !isLoggedIn).toBe(true);
  });

  test('paired local mode with auth skips Login', () => {
    // Simulate post-login state
    useAppModeStore.setState({
      mode: 'local',
      firstRunComplete: true,
      inferenceUrl: 'http://198.51.100.10:8080',
      pairingToken: 'device-tok',
      authToken: 'tok-abc',
      userId: 'uid-123',
      userRole: 'steward',
    });

    const state = useAppModeStore.getState();
    const hasServer = !!state.inferenceUrl;
    const isLoggedIn = !!state.authToken && !!state.userId;
    // Should skip Login, go to Birth
    expect(hasServer && !isLoggedIn).toBe(false);
  });

  test('local mode without server URL skips Login (no pairing)', () => {
    // Simulate standalone local mode (skipped server scan)
    useAppModeStore.setState({
      mode: 'local',
      firstRunComplete: true,
      inferenceUrl: null,
      authToken: null,
      userId: null,
    });

    const state = useAppModeStore.getState();
    const hasServer = !!state.inferenceUrl;
    // No server = no Login needed
    expect(hasServer).toBe(false);
  });
});

describe('loadFromStorage includes auth credentials', () => {
  test('loads auth credentials from AsyncStorage', async () => {
    // Pre-populate secureStorage
    await secureStorage.setItem('@wyrd_app_mode', 'local');
    await secureStorage.setItem('@wyrd_first_run_complete', 'true');
    await secureStorage.setItem('@wyrd_auth_token', 'persisted-tok');
    await secureStorage.setItem('@wyrd_user_id', 'persisted-uid');
    await secureStorage.setItem('@wyrd_user_role', 'steward');

    await useAppModeStore.getState().loadFromStorage();

    const state = useAppModeStore.getState();
    expect(state.authToken).toBe('persisted-tok');
    expect(state.userId).toBe('persisted-uid');
    expect(state.userRole).toBe('steward');
    expect(state.loaded).toBe(true);
  });

  test('loads null auth when keys are absent', async () => {
    await secureStorage.setItem('@wyrd_app_mode', 'local');
    await secureStorage.setItem('@wyrd_first_run_complete', 'true');

    await useAppModeStore.getState().loadFromStorage();

    const state = useAppModeStore.getState();
    expect(state.authToken).toBeNull();
    expect(state.userId).toBeNull();
    expect(state.userRole).toBeNull();
    expect(state.loaded).toBe(true);
  });
});
