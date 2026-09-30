import { assemblePrompt, estimateTokens, buildRoomContext, buildRecencyAnchor } from '../../src/engine/agent/FullPromptAssembler';
import { buildTimeContext, formatDuration } from '../../src/engine/agent/TimeContext';
import { NEXUS_COMPANION } from '../../src/engine/agent/AgentProfile';
import { initialVitality } from '../../src/engine/agent/VitalityState';
import type { Said } from '../../src/engine/events/WorldEvent';
import type { RoomSnapshot } from '../../src/protocol/models';

const snapshot: RoomSnapshot = {
  roomId: 'nexus', name: 'The Nexus', description: 'A crystalline hub.',
  zone: 'foundation',
  exits: [{ direction: 'north', targetRoom: 'terminal', label: 'To Terminal' }],
  entities: [{ id: 'p1', name: 'Alice', type: 'player', description: '' }],
  objects: [{ id: 'o1', name: 'crystal', description: 'A pulsing crystal.', takeable: false }],
  hints: [],
};

function makeSaid(entityId: string, entityName: string, text: string): Said {
  return { type: 'said', roomId: 'nexus', timestamp: Date.now(), entityId, entityName, text };
}

describe('FullPromptAssembler', () => {
  it('minimal prompt has system message', () => {
    const messages = assemblePrompt(NEXUS_COMPANION, null, [], null);
    expect(messages).toHaveLength(1);
    expect(messages[0].role).toBe('system');
    expect(messages[0].content).toContain('Wyrd');
  });

  it('includes room context', () => {
    const messages = assemblePrompt(NEXUS_COMPANION, snapshot, [], null);
    expect(messages.length).toBeGreaterThan(1);
    const systemMessages = messages.filter(m => m.role === 'system');
    const hasRoom = systemMessages.some(m => m.content.includes('The Nexus'));
    expect(hasRoom).toBe(true);
  });

  it('includes conversation history with correct roles', () => {
    const history: Said[] = [
      makeSaid('p1', 'Alice', 'Hello'),
      makeSaid('companion-wyrd', 'Wyrd', 'Welcome!'),
      makeSaid('p1', 'Alice', 'What can I do?'),
    ];
    const messages = assemblePrompt(NEXUS_COMPANION, null, history, null);
    const userMsgs = messages.filter(m => m.role === 'user');
    const assistantMsgs = messages.filter(m => m.role === 'assistant');
    expect(userMsgs.length).toBe(2);
    expect(assistantMsgs.length).toBe(1);
  });

  it('includes trigger event', () => {
    const trigger = makeSaid('p1', 'Alice', 'Help me!');
    const messages = assemblePrompt(NEXUS_COMPANION, null, [], trigger);
    const lastMsg = messages[messages.length - 1];
    expect(lastMsg.role).toBe('user');
    expect(lastMsg.content).toContain('Help me!');
  });

  it('deduplicates trigger from history', () => {
    const trigger = makeSaid('p1', 'Alice', 'Help me!');
    const messages = assemblePrompt(NEXUS_COMPANION, null, [trigger], trigger);
    const userMsgs = messages.filter(m => m.role === 'user');
    // Should NOT duplicate the trigger
    expect(userMsgs.length).toBe(1);
  });

  it('includes vitality description', () => {
    const messages = assemblePrompt(NEXUS_COMPANION, null, [], null, initialVitality());
    const systemMsgs = messages.filter(m => m.role === 'system');
    const hasVitality = systemMsgs.some(m => m.content.includes('Current state:'));
    expect(hasVitality).toBe(true);
  });

  it('includes recency anchor when room + history present', () => {
    const history = [makeSaid('p1', 'Alice', 'Hello')];
    const messages = assemblePrompt(NEXUS_COMPANION, snapshot, history, null);
    const systemMsgs = messages.filter(m => m.role === 'system');
    const hasAnchor = systemMsgs.some(m => m.content.includes('[Current state:'));
    expect(hasAnchor).toBe(true);
  });

  it('estimateTokens returns correct estimates', () => {
    expect(estimateTokens('')).toBe(0);
    expect(estimateTokens('Hi')).toBe(1);
    expect(estimateTokens('Hello, world! How are you?')).toBe(6); // 27/4 = 6
  });
});

/**
 * Layer 3: "Last heard from you", from the previous line of the person
 * speaking now. RN had no such layer; KMP's Layer 3 has it, with this rule.
 * The date and the time are never in it: they ride the Now line the send
 * point stamps on the last user message.
 */
describe('FullPromptAssembler — Layer 3: last heard from you', () => {
  const HOUR = 3_600_000;
  const now = Date.now();
  const said = (entityId: string, entityName: string, text: string, ago: number): Said =>
    ({ type: 'said', roomId: 'nexus', timestamp: now - ago, entityId, entityName, text });
  const layer3 = (recentSaid: Said[], trigger: Said | null) =>
    assemblePrompt(NEXUS_COMPANION, null, recentSaid, trigger)
      .filter((m) => m.role === 'system' && m.content.startsWith('Last heard from you'))
      .map((m) => m.content);

  it("counts the speaker's line before the trigger, not the trigger", () => {
    // The engine files the trigger in memory before it assembles, so recentSaid
    // ends with it; counting it would put the gap under a minute.
    const earlier = said('p1', 'Alice', 'Good night.', 3 * HOUR);
    const trigger = said('p1', 'Alice', 'Morning!', 0);

    expect(layer3([earlier, trigger], trigger)).toEqual(['Last heard from you: 3 hours ago.']);
  });

  it('tells her own lines apart by entityId, not by name', () => {
    // A person may share her name, and her lines from before a rename carry the
    // old one. Under a greeting ("system" trigger) any line but hers counts, so
    // only the own-line check keeps her later line out.
    const person = said('p1', NEXUS_COMPANION.name, 'See you tomorrow.', 2 * HOUR);
    const herOldName = said(NEXUS_COMPANION.entityId, 'Old Name', 'Rest well.', HOUR);
    const greeting = said('system', 'system', 'You has entered the room.', 0);

    expect(layer3([person, herOldName], greeting)).toEqual(['Last heard from you: 2 hours ago.']);
  });

  it('tells the speaker apart by entityId, not by name', () => {
    // Two people named Alex: the one speaking now last spoke 5 hours ago, the
    // other 1 hour ago.
    const alexOne = said('p1', 'Alex', 'Good night.', 5 * HOUR);
    const alexTwo = said('p2', 'Alex', 'Still up.', HOUR);
    const trigger = said('p1', 'Alex', 'Morning!', 0);

    expect(layer3([alexOne, alexTwo, trigger], trigger)).toEqual(['Last heard from you: 5 hours ago.']);
  });

  it('is from whoever speaks now, not anyone in the room', () => {
    const alice = said('p1', 'Alice', 'Good night.', 5 * HOUR);
    const bob = said('p2', 'Bob', 'Anyone up?', 3 * HOUR);
    const trigger = said('p1', 'Alice', 'Morning!', 0);

    expect(layer3([alice, bob, trigger], trigger)).toEqual(['Last heard from you: 5 hours ago.']);

    // Someone who has not spoken before has no gap to tell.
    const first = said('p3', 'Cara', 'Hello?', 0);
    expect(layer3([alice, bob, first], first)).toEqual([]);
  });

  it("a greeting (the engine's own \"system\" trigger) counts any person's last line", () => {
    const alice = said('p1', 'Alice', 'Good night.', 4 * HOUR);
    const greeting = said('system', 'system', 'You has entered the room.', 0);

    expect(layer3([alice], greeting)).toEqual(['Last heard from you: 4 hours ago.']);
  });

  it('says nothing under a minute, or with no one to measure from', () => {
    const justNow = said('p1', 'Alice', 'One more thing.', 30_000);
    const trigger = said('p1', 'Alice', 'Actually two.', 0);

    expect(layer3([justNow, trigger], trigger)).toEqual([]);
    expect(layer3([], null)).toEqual([]);
  });

  it('carries no date or time of day, and no system layer does', () => {
    const earlier = said('p1', 'Alice', 'Good night.', 26 * HOUR);
    const trigger = said('p1', 'Alice', 'Morning!', 0);
    const messages = assemblePrompt(NEXUS_COMPANION, snapshot, [earlier, trigger], trigger, initialVitality());

    expect(layer3([earlier, trigger], trigger)).toEqual(['Last heard from you: 1 day 2h ago.']);
    const system = messages.filter((m) => m.role === 'system').map((m) => m.content).join('\n');
    expect(system).not.toMatch(/\[Now:|Today is|morning|afternoon|evening|night|\b20\d\d\b|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday/);
  });

  it('elapsed time reads as it does on the server and in KMP', () => {
    const MIN = 60_000;
    expect(buildTimeContext(null)).toBe('');
    expect(buildTimeContext(now - 59_000, now)).toBe('');
    expect(formatDuration(MIN)).toBe('a moment');
    expect(formatDuration(5 * MIN)).toBe('5 minutes');
    expect(formatDuration(60 * MIN)).toBe('1 hour');
    expect(formatDuration(150 * MIN)).toBe('2h 30m');
    expect(formatDuration(24 * 60 * MIN)).toBe('1 day');
    expect(formatDuration((2 * 24 + 3) * 60 * MIN + 59_000)).toBe('2 days 3h');
  });
});
