/**
 * S2C protocol conformance tests.
 * Validates deserialization of all 9 S2C message types against protocol-tests/fixtures/.
 */

import { parseS2CMessage, S2CMessage } from '../src/protocol/s2c';

describe('S2C Protocol Conformance', () => {

  // --- RoomState ---

  test('deserialize room_state', () => {
    const json = JSON.stringify({
      type: 'room_state',
      seq: 1,
      room: {
        roomId: 'nexus',
        name: 'The Nexus',
        description: 'A vast crystalline chamber hums with quiet energy.',
        zone: 'home',
        exits: [
          { direction: 'north', targetRoom: 'terminal', label: 'A corridor leads north' },
          { direction: 'down', targetRoom: 'vault', label: 'Stone steps spiral downward' },
        ],
        entities: [
          { id: 'agent-guide-01', name: 'Guide', type: 'agent', description: 'A patient guide' },
        ],
        objects: [
          { id: 'obj-scroll-01', name: 'scroll', description: 'An ancient scroll', takeable: true },
          { id: 'obj-pedestal-01', name: 'pedestal', description: 'A stone pedestal', takeable: false },
        ],
        hints: [
          { label: 'Talk to the Guide', intent: 'greet_guide', action: 'say', labelKey: 'hint.greet_guide' },
          { label: 'Read the scroll', intent: 'read_scroll', action: 'use', labelKey: null },
        ],
      },
      inventory: [
        { id: 'obj-key-01', name: 'brass key', description: 'A small brass key', takeable: true },
      ],
    });

    const msg = parseS2CMessage(json)!;
    expect(msg).not.toBeNull();
    expect(msg.type).toBe('room_state');
    if (msg.type !== 'room_state') return;

    expect(msg.seq).toBe(1);
    expect(msg.room.roomId).toBe('nexus');
    expect(msg.room.name).toBe('The Nexus');
    expect(msg.room.zone).toBe('home');
    expect(msg.room.exits).toHaveLength(2);
    expect(msg.room.exits[0].direction).toBe('north');
    expect(msg.room.entities).toHaveLength(1);
    expect(msg.room.entities[0].name).toBe('Guide');
    expect(msg.room.objects).toHaveLength(2);
    expect(msg.room.objects[0].takeable).toBe(true);
    expect(msg.room.hints).toHaveLength(2);
    expect(msg.room.hints[0].labelKey).toBe('hint.greet_guide');
    expect(msg.room.hints[1].labelKey).toBeNull();
    expect(msg.inventory).toHaveLength(1);
    expect(msg.inventory![0].name).toBe('brass key');
  });

  test('deserialize room_state with empty collections', () => {
    const json = JSON.stringify({
      type: 'room_state',
      seq: 1,
      room: {
        roomId: 'void', name: 'Empty Room', description: 'Nothing here.',
        zone: 'home', exits: [], entities: [], objects: [], hints: [],
      },
      inventory: null,
    });

    const msg = parseS2CMessage(json)!;
    expect(msg).not.toBeNull();
    if (msg.type !=='room_state') return;
    expect(msg.room.exits).toHaveLength(0);
    expect(msg.inventory).toBeNull();
  });

  // --- Prose ---

  test('deserialize prose', () => {
    const json = JSON.stringify({
      type: 'prose', seq: 2, speaker: 'Guide',
      text: 'Welcome, traveler.',
      hints: [{ label: 'Ask about rooms', intent: 'ask_rooms', action: 'say', labelKey: null }],
      structured: null, priority: 'normal', lang: 'en', isAiGenerated: true, blocks: [],
    });

    const msg = parseS2CMessage(json)!;
    expect(msg).not.toBeNull();
    if (msg.type !=='prose') return;
    expect(msg.seq).toBe(2);
    expect(msg.speaker).toBe('Guide');
    expect(msg.priority).toBe('normal');
    expect(msg.lang).toBe('en');
    expect(msg.isAiGenerated).toBe(true);
    expect(msg.hints).toHaveLength(1);
    expect(msg.hints[0].action).toBe('say');
  });

  test('deserialize prose critical', () => {
    const json = JSON.stringify({
      type: 'prose', seq: 5, speaker: 'system',
      text: 'Warning: Server restart in 5 minutes.',
      hints: [], structured: null, priority: 'critical',
      lang: null, isAiGenerated: false, blocks: [],
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='prose') return;
    expect(msg.priority).toBe('critical');
    expect(msg.isAiGenerated).toBe(false);
  });

  test('deserialize prose ambient', () => {
    const json = JSON.stringify({
      type: 'prose', seq: 6, speaker: 'narrator',
      text: 'A gentle breeze stirs the curtains.',
      hints: [], structured: null, priority: 'ambient',
      lang: 'en', isAiGenerated: true, blocks: [],
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='prose') return;
    expect(msg.priority).toBe('ambient');
  });

  test('deserialize prose minimal (optional fields missing)', () => {
    const json = JSON.stringify({
      type: 'prose', seq: 3, speaker: 'narrator',
      text: 'The room is quiet.',
      hints: [], structured: null, priority: 'normal',
    });

    const msg = parseS2CMessage(json)!;
    expect(msg).not.toBeNull();
    if (msg.type !=='prose') return;
    expect(msg.speaker).toBe('narrator');
    // Optional fields may be undefined
    expect(msg.blocks ?? []).toHaveLength(0);
  });

  test('deserialize prose with content blocks', () => {
    const json = JSON.stringify({
      type: 'prose', seq: 42, speaker: 'Agent',
      text: 'Code review complete',
      hints: [{ label: 'Approve changes', intent: 'approve', action: 'command', labelKey: null }],
      structured: null, priority: 'normal', lang: 'en', isAiGenerated: true,
      blocks: [
        {
          format: 'codeplane.diff',
          data: { filePath: 'auth.js', additions: 12, deletions: 5 },
          fallback: 'auth.js: +12 -5 lines changed',
        },
        {
          format: 'codeplane.cost',
          data: { tokensIn: 4200, tokensOut: 850, estimatedUSD: 0.03 },
          fallback: 'Cost: $0.03 (4.2K in, 850 out)',
        },
      ],
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='prose') return;
    expect(msg.blocks).toHaveLength(2);
    expect(msg.blocks![0].format).toBe('codeplane.diff');
    expect(msg.blocks![0].fallback).toBe('auth.js: +12 -5 lines changed');
    expect(msg.blocks![1].format).toBe('codeplane.cost');
  });

  test('deserialize prose with structured', () => {
    const json = JSON.stringify({
      type: 'prose', seq: 8, speaker: 'narrator',
      text: 'You look around.',
      hints: [],
      structured: {
        name: 'The Nexus', description: 'A hub.',
        exits: [{ direction: 'north', targetRoom: 'terminal', label: 'North' }],
        entities: [], objects: [], hints: [],
        properties: { atmosphere: 'calm' }, zone: 'home',
      },
      priority: 'normal', lang: 'en', isAiGenerated: false, blocks: [],
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='prose') return;
    expect(msg.structured).not.toBeNull();
    expect(msg.structured!.name).toBe('The Nexus');
    expect(msg.structured!.zone).toBe('home');
  });

  // --- AgentAction ---

  test('deserialize agent_action', () => {
    const json = JSON.stringify({
      type: 'agent_action', seq: 7, agentName: 'Guide',
      action: 'emote', description: 'smiles warmly',
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='agent_action') return;
    expect(msg.agentName).toBe('Guide');
    expect(msg.action).toBe('emote');
  });

  // --- StateChange ---

  test('deserialize state_change', () => {
    const json = JSON.stringify({
      type: 'state_change', seq: 5, description: 'A door opens.',
      structured: null, blocks: [],
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='state_change') return;
    expect(msg.description).toBe('A door opens.');
  });

  test('deserialize state_change with blocks', () => {
    const json = JSON.stringify({
      type: 'state_change', seq: 20, description: 'Board updated',
      structured: null,
      blocks: [{
        format: 'codeplane.board_card',
        data: { boardId: 'board-7', name: 'Auth refactor', status: 'in_progress' },
        fallback: 'Card: Auth refactor → in_progress',
      }],
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='state_change') return;
    expect(msg.blocks).toHaveLength(1);
    expect(msg.blocks![0].format).toBe('codeplane.board_card');
  });

  // --- ReplayDone ---

  test('deserialize replay_done', () => {
    const json = JSON.stringify({
      type: 'replay_done', seq: 6, fromSeq: 3, toSeq: 5, count: 2,
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='replay_done') return;
    expect(msg.fromSeq).toBe(3);
    expect(msg.toSeq).toBe(5);
    expect(msg.count).toBe(2);
  });

  // --- Error ---

  test('deserialize error', () => {
    const json = JSON.stringify({
      type: 'error', seq: 10, code: 'no_exit',
      message: 'There is no exit in that direction.',
      requestId: 'msg-002',
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='error') return;
    expect(msg.code).toBe('no_exit');
    expect(msg.requestId).toBe('msg-002');
  });

  // --- Notification ---

  test('deserialize notification', () => {
    const json = JSON.stringify({
      type: 'notification', seq: 11, level: 'info',
      title: 'New message', message: 'You have a new message.',
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='notification') return;
    expect(msg.level).toBe('info');
    expect(msg.title).toBe('New message');
  });

  test('deserialize notification warning', () => {
    const json = JSON.stringify({
      type: 'notification', seq: 12, level: 'warning',
      title: 'Low storage', message: 'Running low on zone storage.',
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='notification') return;
    expect(msg.level).toBe('warning');
  });

  // --- Transit ---

  test('deserialize transit', () => {
    const json = JSON.stringify({
      type: 'transit', seq: 13, targetZoneId: 'neighbor-zone',
      targetUrl: 'wss://neighbor.example.com/ws',
      transitToken: 'tt-a1b2c3d4',
      message: 'Departing for neighbor-zone.',
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='transit') return;
    expect(msg.targetZoneId).toBe('neighbor-zone');
    expect(msg.targetUrl).toBe('wss://neighbor.example.com/ws');
    expect(msg.transitToken).toBe('tt-a1b2c3d4');
  });

  // --- TokenStream ---

  test('deserialize token_stream', () => {
    const json = JSON.stringify({
      type: 'token_stream', seq: 14, source: 'Guide',
      token: 'The ancient', done: false, context: null,
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='token_stream') return;
    expect(msg.source).toBe('Guide');
    expect(msg.token).toBe('The ancient');
    expect(msg.done).toBe(false);
    expect(msg.context).toBeNull();
  });

  test('deserialize token_stream done', () => {
    const json = JSON.stringify({
      type: 'token_stream', seq: 17, source: 'Guide',
      token: '.', done: true, context: null,
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='token_stream') return;
    expect(msg.done).toBe(true);
  });

  test('deserialize token_stream with context', () => {
    const json = JSON.stringify({
      type: 'token_stream', seq: 15, source: 'Agent',
      token: 'Processing', done: false, context: 'board-7',
    });

    const msg = parseS2CMessage(json)!;
    if (msg.type !=='token_stream') return;
    expect(msg.context).toBe('board-7');
  });

  // --- Scenarios ---

  test('token stream assembly', () => {
    const messages = [
      { type: 'token_stream', seq: 10, source: 'Guide', token: 'The ancient ', done: false, context: null },
      { type: 'token_stream', seq: 11, source: 'Guide', token: 'scroll reads: ', done: false, context: null },
      { type: 'token_stream', seq: 12, source: 'Guide', token: "'Welcome, ", done: false, context: null },
      { type: 'token_stream', seq: 13, source: 'Guide', token: "traveler.'", done: true, context: null },
    ];

    let buffer = '';
    let lastSeq = 0;

    for (const raw of messages) {
      const msg = parseS2CMessage(JSON.stringify(raw))!;
      expect(msg).not.toBeNull();
      if (msg.type !=='token_stream') continue;
      expect(msg.seq).toBeGreaterThan(lastSeq);
      lastSeq = msg.seq;
      buffer += msg.token;
    }

    expect(buffer).toBe("The ancient scroll reads: 'Welcome, traveler.'");
  });

  test('unknown content block does not crash', () => {
    const json = JSON.stringify({
      type: 'prose', seq: 50, speaker: 'Agent',
      text: 'Here are the results.',
      hints: [], structured: null, priority: 'normal',
      lang: 'en', isAiGenerated: true,
      blocks: [{
        format: 'future.unknown_format',
        data: { someField: 'someValue' },
        fallback: 'Results: 42 items processed, 3 errors',
      }],
    });

    const msg = parseS2CMessage(json)!;
    expect(msg).not.toBeNull();
    if (msg.type !=='prose') return;
    expect(msg.blocks).toHaveLength(1);
    expect(msg.blocks![0].format).toBe('future.unknown_format');
    expect(msg.blocks![0].fallback).toBe('Results: 42 items processed, 3 errors');
  });

  test('unknown fields are ignored', () => {
    const json = JSON.stringify({
      type: 'prose', seq: 99, speaker: 'narrator',
      text: 'Hello.',
      hints: [], structured: null, priority: 'normal',
      futureField: 'should be ignored',
      anotherFutureField: 42,
    });

    const msg = parseS2CMessage(json)!;
    expect(msg).not.toBeNull();
    expect(msg.type).toBe('prose');
  });

  test('invalid JSON returns null', () => {
    expect(parseS2CMessage('not json')).toBeNull();
    expect(parseS2CMessage('{}')).toBeNull();
    expect(parseS2CMessage('{"type":"prose"}')).toBeNull(); // missing seq
  });
});
