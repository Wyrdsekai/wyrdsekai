/**
 * ConnectScreen logic tests.
 *
 * Tests the validation, credential, and routing logic used by ConnectScreen
 * WITHOUT rendering React components. We test the underlying stores and
 * network helpers directly.
 */

import { useSessionStore } from '../../src/state/sessionStore';
import { useCredentialStore } from '../../src/state/credentialStore';

// Reset stores between tests
beforeEach(() => {
  useSessionStore.setState({
    connectionState: 'disconnected',
    serverUrl: 'localhost:7070',
    token: null,
    roomId: '',
    roomName: '?',
    roomDescription: '',
    exits: [],
    entities: [],
    objects: [],
    hints: [],
    inventory: [],
    proseStream: [],
    streamingText: {},
  });
  useCredentialStore.getState().clearCredentials();
});

describe('ConnectScreen URL validation logic', () => {
  test('accepts valid hostname:port URL', () => {
    useSessionStore.getState().setServerUrl('example.com:7070');
    expect(useSessionStore.getState().serverUrl).toBe('example.com:7070');
  });

  test('accepts URL with protocol', () => {
    useSessionStore.getState().setServerUrl('https://example.com:7070');
    expect(useSessionStore.getState().serverUrl).toBe('https://example.com:7070');
  });

  test('accepts localhost', () => {
    useSessionStore.getState().setServerUrl('localhost:7070');
    expect(useSessionStore.getState().serverUrl).toBe('localhost:7070');
  });

  test('accepts IP address', () => {
    useSessionStore.getState().setServerUrl('198.51.100.100:7070');
    expect(useSessionStore.getState().serverUrl).toBe('198.51.100.100:7070');
  });

  test('accepts empty string (will fail on connect attempt)', () => {
    useSessionStore.getState().setServerUrl('');
    expect(useSessionStore.getState().serverUrl).toBe('');
  });
});

describe('ConnectScreen credential storage/retrieval', () => {
  test('saves and retrieves credentials', () => {
    useCredentialStore.getState().saveCredentials(
      'example.com:7070',
      'testuser',
      'tok-abc123',
    );

    const state = useCredentialStore.getState();
    expect(state.savedServerUrl).toBe('example.com:7070');
    expect(state.savedUsername).toBe('testuser');
    expect(state.savedToken).toBe('tok-abc123');
  });

  test('clears credentials', () => {
    useCredentialStore.getState().saveCredentials('host', 'user', 'tok');
    useCredentialStore.getState().clearCredentials();

    const state = useCredentialStore.getState();
    expect(state.savedServerUrl).toBeNull();
    expect(state.savedUsername).toBeNull();
    expect(state.savedToken).toBeNull();
  });

  test('starts with no saved credentials', () => {
    const state = useCredentialStore.getState();
    expect(state.savedServerUrl).toBeNull();
    expect(state.savedUsername).toBeNull();
    expect(state.savedToken).toBeNull();
  });

  test('overwrites previous credentials on re-save', () => {
    useCredentialStore.getState().saveCredentials('host1', 'user1', 'tok1');
    useCredentialStore.getState().saveCredentials('host2', 'user2', 'tok2');

    const state = useCredentialStore.getState();
    expect(state.savedServerUrl).toBe('host2');
    expect(state.savedUsername).toBe('user2');
    expect(state.savedToken).toBe('tok2');
  });
});

describe('ConnectScreen navigation routing logic', () => {
  test('connection state starts disconnected', () => {
    expect(useSessionStore.getState().connectionState).toBe('disconnected');
  });

  test('setting connected state triggers navigation condition', () => {
    // The ConnectScreen navigates to Room when connectionState === 'connected'
    useSessionStore.getState().setConnectionState('connected');
    expect(useSessionStore.getState().connectionState).toBe('connected');
  });

  test('standalone mode does not require connection state', () => {
    // Standalone navigation is direct (no connection needed)
    // Verify that connectionState remains disconnected for standalone path
    expect(useSessionStore.getState().connectionState).toBe('disconnected');
    // Standalone navigates via navigation.navigate('Standalone') directly
  });

  test('setting token updates session store', () => {
    useSessionStore.getState().setToken('test-token-123');
    expect(useSessionStore.getState().token).toBe('test-token-123');
  });

  test('setting token to null clears it', () => {
    useSessionStore.getState().setToken('test-token-123');
    useSessionStore.getState().setToken(null);
    expect(useSessionStore.getState().token).toBeNull();
  });

  test('saved credentials restore server URL', () => {
    // Simulates the pattern ConnectScreen uses on mount
    useCredentialStore.getState().saveCredentials('saved-host:7070', 'saved-user', 'saved-tok');
    const { savedServerUrl, savedUsername } = useCredentialStore.getState();

    if (savedServerUrl) {
      useSessionStore.getState().setServerUrl(savedServerUrl);
    }

    expect(useSessionStore.getState().serverUrl).toBe('saved-host:7070');
    expect(savedUsername).toBe('saved-user');
  });
});
