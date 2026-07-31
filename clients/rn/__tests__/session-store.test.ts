/**
 * Session store conformance tests.
 * Validates that the Zustand store correctly processes all 9 S2C message types.
 */

import { useSessionStore } from '../src/state/sessionStore';
import { S2CMessage } from '../src/protocol/s2c';

// Reset store between tests
beforeEach(() => {
  useSessionStore.setState({
    connectionState: 'disconnected',
    serverUrl: 'http://localhost:7070',
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
});

describe('Session Store Message Handling', () => {

  test('handles room_state', () => {
    const msg: S2CMessage = {
      type: 'room_state', seq: 1,
      room: {
        roomId: 'nexus', name: 'The Nexus', description: 'A hub.',
        zone: 'home',
        exits: [{ direction: 'north', targetRoom: 'terminal', label: 'North' }],
        entities: [{ id: 'e1', name: 'Guide', type: 'agent', description: 'A guide' }],
        objects: [{ id: 'o1', name: 'scroll', description: 'A scroll', takeable: true }],
        hints: [{ label: 'Look', intent: 'look', action: 'look', labelKey: null }],
      },
      inventory: [{ id: 'o2', name: 'key', description: 'A key', takeable: true }],
    };

    useSessionStore.getState().handleMessage(msg);
    const state = useSessionStore.getState();

    expect(state.roomId).toBe('nexus');
    expect(state.roomName).toBe('The Nexus');
    expect(state.exits).toHaveLength(1);
    expect(state.entities).toHaveLength(1);
    expect(state.objects).toHaveLength(1);
    expect(state.hints).toHaveLength(1);
    expect(state.inventory).toHaveLength(1);
    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].text).toContain('The Nexus');
  });

  test('handles prose', () => {
    const msg: S2CMessage = {
      type: 'prose', seq: 2, speaker: 'Guide',
      text: 'Welcome, traveler.',
      hints: [{ label: 'Ask', intent: 'ask', action: 'say', labelKey: null }],
      structured: null, priority: 'normal',
      lang: 'en', isAiGenerated: true, blocks: [],
    };

    useSessionStore.getState().handleMessage(msg);
    const state = useSessionStore.getState();

    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].speaker).toBe('Guide');
    expect(state.proseStream[0].text).toBe('Welcome, traveler.');
    expect(state.proseStream[0].priority).toBe('normal');
    expect(state.proseStream[0].isAiGenerated).toBe(true);
    expect(state.hints).toHaveLength(1);
  });

  test('handles agent_action', () => {
    const msg: S2CMessage = {
      type: 'agent_action', seq: 7, agentName: 'Guide',
      action: 'emote', description: 'smiles warmly',
    };

    useSessionStore.getState().handleMessage(msg);
    const state = useSessionStore.getState();

    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].text).toContain('Guide');
    expect(state.proseStream[0].text).toContain('smiles warmly');
  });

  test('handles state_change', () => {
    const msg: S2CMessage = {
      type: 'state_change', seq: 5, description: 'A door opens.',
      structured: null, blocks: [],
    };

    useSessionStore.getState().handleMessage(msg);
    const state = useSessionStore.getState();

    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].text).toContain('A door opens.');
  });

  test('handles error', () => {
    const msg: S2CMessage = {
      type: 'error', seq: 10, code: 'no_exit',
      message: 'There is no exit in that direction.',
      requestId: 'msg-002',
    };

    useSessionStore.getState().handleMessage(msg);
    const state = useSessionStore.getState();

    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].priority).toBe('critical');
    expect(state.proseStream[0].text).toContain('no_exit');
  });

  test('handles notification', () => {
    const msg: S2CMessage = {
      type: 'notification', seq: 11, level: 'info',
      title: 'New message', message: 'You have a new message.',
    };

    useSessionStore.getState().handleMessage(msg);
    const state = useSessionStore.getState();

    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].text).toContain('New message');
  });

  test('handles replay_done', () => {
    const msg: S2CMessage = {
      type: 'replay_done', seq: 6, fromSeq: 3, toSeq: 5, count: 2,
    };

    useSessionStore.getState().handleMessage(msg);
    const state = useSessionStore.getState();

    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].text).toContain('Reconnected');
    expect(state.proseStream[0].text).toContain('2');
  });

  test('handles transit', () => {
    const msg: S2CMessage = {
      type: 'transit', seq: 13, targetZoneId: 'neighbor-zone',
      targetUrl: 'wss://neighbor.example.com/ws',
      transitToken: 'tt-abc', message: 'Departing for neighbor-zone.',
    };

    useSessionStore.getState().handleMessage(msg);
    const state = useSessionStore.getState();

    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].priority).toBe('critical');
    expect(state.proseStream[0].text).toContain('Transit');
  });

  // --- Token Stream ---

  test('handles token_stream accumulation', () => {
    const { handleMessage } = useSessionStore.getState();

    handleMessage({ type: 'token_stream', seq: 10, source: 'Guide', token: 'The ancient ', done: false, context: null });
    expect(useSessionStore.getState().streamingText['Guide']).toBe('The ancient ');

    handleMessage({ type: 'token_stream', seq: 11, source: 'Guide', token: 'scroll reads: ', done: false, context: null });
    expect(useSessionStore.getState().streamingText['Guide']).toBe('The ancient scroll reads: ');

    handleMessage({ type: 'token_stream', seq: 12, source: 'Guide', token: "'Welcome, ", done: false, context: null });
    handleMessage({ type: 'token_stream', seq: 13, source: 'Guide', token: "traveler.'", done: true, context: null });

    const state = useSessionStore.getState();
    // After done, streaming text should be cleared
    expect(state.streamingText['Guide']).toBeUndefined();
    // Final text should be in prose stream
    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].text).toBe("The ancient scroll reads: 'Welcome, traveler.'");
    expect(state.proseStream[0].isAiGenerated).toBe(true);
  });

  test('handles multiple concurrent token streams', () => {
    const { handleMessage } = useSessionStore.getState();

    handleMessage({ type: 'token_stream', seq: 10, source: 'Guide', token: 'Hello ', done: false, context: null });
    handleMessage({ type: 'token_stream', seq: 11, source: 'Agent', token: 'Working ', done: false, context: null });
    handleMessage({ type: 'token_stream', seq: 12, source: 'Guide', token: 'there.', done: true, context: null });

    const state = useSessionStore.getState();
    // Guide should be finalized
    expect(state.streamingText['Guide']).toBeUndefined();
    expect(state.proseStream).toHaveLength(1);
    expect(state.proseStream[0].text).toBe('Hello there.');

    // Agent should still be streaming
    expect(state.streamingText['Agent']).toBe('Working ');
  });
});
