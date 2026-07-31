import { assemblePrompt, estimateTokens, buildRoomContext, buildRecencyAnchor } from '../../src/engine/agent/FullPromptAssembler';
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
