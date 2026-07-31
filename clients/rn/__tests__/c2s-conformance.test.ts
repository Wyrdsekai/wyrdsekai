/**
 * C2S protocol conformance tests.
 * Validates serialization roundtrip of all 10 C2S message types.
 */

import { C2SMessage, serializeC2S, newId } from '../src/protocol/c2s';

describe('C2S Protocol Conformance', () => {

  test('serialize say', () => {
    const msg: C2SMessage = { type: 'say', id: 'msg-001', roomId: 'nexus', text: 'Hello everyone!' };
    const json = serializeC2S(msg);
    const parsed = JSON.parse(json);

    expect(parsed.type).toBe('say');
    expect(parsed.id).toBe('msg-001');
    expect(parsed.roomId).toBe('nexus');
    expect(parsed.text).toBe('Hello everyone!');
  });

  test('serialize go', () => {
    const msg: C2SMessage = { type: 'go', id: 'msg-002', roomId: 'nexus', direction: 'north' };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('go');
    expect(parsed.direction).toBe('north');
  });

  test('serialize take', () => {
    const msg: C2SMessage = { type: 'take', id: 'msg-003', roomId: 'nexus', objectName: 'scroll' };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('take');
    expect(parsed.objectName).toBe('scroll');
  });

  test('serialize drop', () => {
    const msg: C2SMessage = { type: 'drop', id: 'msg-004', roomId: 'nexus', objectName: 'scroll' };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('drop');
    expect(parsed.objectName).toBe('scroll');
  });

  test('serialize use with target', () => {
    const msg: C2SMessage = { type: 'use', id: 'msg-005', roomId: 'nexus', objectName: 'key', target: 'chest' };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('use');
    expect(parsed.objectName).toBe('key');
    expect(parsed.target).toBe('chest');
  });

  test('serialize use without target', () => {
    const msg: C2SMessage = { type: 'use', id: 'msg-005b', roomId: 'nexus', objectName: 'scroll', target: null };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('use');
    expect(parsed.target).toBeNull();
  });

  test('serialize look', () => {
    const msg: C2SMessage = { type: 'look', id: 'msg-006', roomId: 'nexus' };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('look');
    expect(parsed.roomId).toBe('nexus');
  });

  test('serialize hint_select', () => {
    const msg: C2SMessage = { type: 'hint_select', id: 'msg-007', roomId: 'nexus', index: 0 };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('hint_select');
    expect(parsed.index).toBe(0);
  });

  test('serialize reconnect', () => {
    const msg: C2SMessage = { type: 'reconnect', id: 'msg-008', roomId: 'nexus', lastSeenSeq: 42 };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('reconnect');
    expect(parsed.lastSeenSeq).toBe(42);
  });

  test('serialize command (simple)', () => {
    const msg: C2SMessage = { type: 'command', id: 'msg-009', command: 'inventory', args: [], payload: {} };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('command');
    expect(parsed.command).toBe('inventory');
    expect(parsed.args).toEqual([]);
    expect(parsed.payload).toEqual({});
  });

  test('serialize command (namespaced with payload)', () => {
    const msg: C2SMessage = {
      type: 'command', id: 'msg-009b', command: 'codeplane.approve',
      args: [], payload: { eventId: 'evt-42', decision: 'approve' },
    };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.command).toBe('codeplane.approve');
    expect(parsed.payload.eventId).toBe('evt-42');
    expect(parsed.payload.decision).toBe('approve');
  });

  test('serialize command (with args)', () => {
    const msg: C2SMessage = {
      type: 'command', id: 'msg-009c', command: 'whisper',
      args: ['Guide', 'Hello there'], payload: {},
    };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.args).toEqual(['Guide', 'Hello there']);
  });

  test('serialize set_preference', () => {
    const msg: C2SMessage = { type: 'set_preference', id: 'msg-010', key: 'lang', value: 'ja' };
    const parsed = JSON.parse(serializeC2S(msg));

    expect(parsed.type).toBe('set_preference');
    expect(parsed.key).toBe('lang');
    expect(parsed.value).toBe('ja');
  });

  // --- Full roundtrip ---

  test('full roundtrip all types', () => {
    const messages: C2SMessage[] = [
      { type: 'say', id: 'rt-1', roomId: 'r1', text: 'hello' },
      { type: 'go', id: 'rt-2', roomId: 'r1', direction: 'north' },
      { type: 'take', id: 'rt-3', roomId: 'r1', objectName: 'key' },
      { type: 'drop', id: 'rt-4', roomId: 'r1', objectName: 'key' },
      { type: 'use', id: 'rt-5', roomId: 'r1', objectName: 'key', target: 'door' },
      { type: 'look', id: 'rt-6', roomId: 'r1' },
      { type: 'hint_select', id: 'rt-7', roomId: 'r1', index: 2 },
      { type: 'reconnect', id: 'rt-8', roomId: 'r1', lastSeenSeq: 10 },
      { type: 'command', id: 'rt-9', command: 'who', args: [], payload: {} },
      { type: 'set_preference', id: 'rt-10', key: 'lang', value: 'es' },
    ];

    for (const original of messages) {
      const json = serializeC2S(original);
      const reparsed = JSON.parse(json);
      expect(reparsed.type).toBe(original.type);
      expect(reparsed.id).toBe(original.id);
    }
  });

  // --- Utility ---

  test('newId generates unique IDs', () => {
    const ids = new Set<string>();
    for (let i = 0; i < 100; i++) {
      ids.add(newId());
    }
    expect(ids.size).toBe(100);
  });
});
