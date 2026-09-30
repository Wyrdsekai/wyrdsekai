/**
 * Her emotes and the lines the phone speaks for her follow the language the
 * app is set to (Settings → Language, the preferences store). They were
 * English literals in CompanionEngine, and in ProactivityJudgment for her
 * unprompted lines, whatever the language. The wording is the KMP client's
 * (UiStrings narration…). Model-facing prompt text stays English.
 */

import { readFileSync } from 'fs';
import { join } from 'path';
import { CompanionEngine } from '../../../src/engine/agent/CompanionEngine';
import { OfflineQueue } from '../../../src/engine/agent/OfflineQueue';
import { NEXUS_COMPANION } from '../../../src/engine/agent/AgentProfile';
import { evaluate } from '../../../src/engine/agent/ProactivityJudgment';
import type { JudgmentContext } from '../../../src/engine/agent/ProactivityJudgment';
import type { ProactiveAction } from '../../../src/engine/agent/ProactiveAction';
import { initialDriveState } from '../../../src/engine/agent/DriveState';
import type { DriveState } from '../../../src/engine/agent/DriveState';
import { initialVitality, withConfidence } from '../../../src/engine/agent/VitalityState';
import { RoomEngine } from '../../../src/engine/room/RoomEngine';
import { InMemoryEventJournal } from '../../../src/engine/persistence/InMemoryEventJournal';
import { InferenceRouter } from '../../../src/inference/InferenceRouter';
import { LlamaService } from '../../../src/inference/LlamaService';
import { getStrings } from '../../../src/i18n/strings';
import type { NarrationStrings } from '../../../src/i18n/strings';
import { usePreferencesStore } from '../../../src/state/preferencesStore';
import type { ChatMessage } from '../../../src/inference/types';
import type { BudDelegation, DelegationResult } from '../../../src/engine/between/BudDelegation';
import { createMockAsyncStorage } from '../../helpers/mockAsyncStorage';

const en = getStrings('en').narration;
const ja = getStrings('ja').narration;
const es = getStrings('es').narration;

const HOUSEHOLD = 'https://household.test';
/** Over 30 words: COMPLEX by heuristic, so no classifier request. */
const deep = Array.from({ length: 32 }, (_, i) => `word${i}`).join(' ');
/** Five words, no question: SIMPLE by heuristic. */
const quick = 'the garden looks very lovely';

/** An endpoint that answers every request with `reply` and records what it was sent. */
function answering(reply: string) {
  const sent: ChatMessage[][] = [];
  globalThis.fetch = jest.fn(async (_url: unknown, init?: { body?: string }) => {
    sent.push(JSON.parse(init?.body ?? '{}').messages);
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ choices: [{ message: { content: reply } }], usage: {} }),
      text: async () => '',
    };
  }) as unknown as typeof fetch;
  return sent;
}

const fence = (json: string) => `\`\`\`json\n${json}\n\`\`\``;

describe('CompanionEngine — she narrates in the app language', () => {
  let originalFetch: typeof fetch;
  let journal: InMemoryEventJournal;
  let room: RoomEngine;
  let router: InferenceRouter;
  let engine: CompanionEngine;
  let queue: OfflineQueue;

  beforeEach(async () => {
    originalFetch = globalThis.fetch;
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-09-23T14:05:00Z'));
    journal = new InMemoryEventJournal();
    room = new RoomEngine('nexus', journal);
    router = new InferenceRouter(new LlamaService());
    engine = new CompanionEngine(NEXUS_COMPANION, room, router, null);
    queue = new OfflineQueue(createMockAsyncStorage());
    engine.setOfflineQueue(queue);
    await engine.start();
  });

  afterEach(() => {
    engine.shutdown();
    jest.useRealTimers();
    globalThis.fetch = originalFetch;
    usePreferencesStore.setState({ locale: 'en' });
  });

  const language = (locale: string) => usePreferencesStore.setState({ locale });

  /** Her lines and emotes in the room's journal, in order: `say: …` and `emote: …`. */
  const hers = () =>
    journal.allEvents('nexus').flatMap((e) =>
      (e.type === 'said' || e.type === 'emoted') && e.entityId === NEXUS_COMPANION.entityId
        ? [`${e.type === 'said' ? 'say' : 'emote'}: ${e.text}`]
        : []);
  const narrator = () =>
    journal.allEvents('nexus').flatMap((e) => (e.type === 'emoted' && e.entityId === 'narrator' ? [e.text] : []));

  async function aliceSays(text: string): Promise<void> {
    await room.send({ type: 'say_in_room', entityId: 'player-Alice', entityName: 'Alice', text });
    await jest.advanceTimersByTimeAsync(5_000);
  }

  it('she considers and thinks deeply in Japanese; what the model is told stays English', async () => {
    language('ja');
    const sent = answering('Hello.');
    router.setRemoteUrl(HOUSEHOLD);
    engine.setRemoteInferenceUrl(HOUSEHOLD);

    await aliceSays(quick);
    await aliceSays(deep);

    expect(hers()).toEqual([`emote: ${ja.considers}`, 'say: Hello.', `emote: ${ja.thinkingDeeply}`, 'say: Hello.']);
    expect(sent[0][0].content.startsWith('You are Wyrd. Respond briefly in 1-2 sentences.')).toBe(true);
  });

  it('nothing can think deeply or answer: the mental note and the fallback line are Japanese', async () => {
    language('ja');
    // No household and no configured backend: the deep turn and the acknowledgement both fail.

    await aliceSays(deep);

    expect(await queue.size()).toBe(1);
    expect(hers()).toEqual([`emote: ${ja.thinkingDeeply}`, `emote: ${ja.mentalNote}`, `say: ${ja.thinkLater}`]);
  });

  it('a replayed answer is introduced in Japanese', async () => {
    language('ja');
    const long = 'what did we decide about the new shelves in the study';
    await queue.enqueue('When does the tide turn?', 'Alice', 'nexus');
    await queue.enqueue(long, 'Alice', 'nexus');
    answering('At noon.');
    engine.setRemoteInferenceUrl(HOUSEHOLD);

    await engine.replayOfflineQueue();

    expect(hers()).toEqual([
      `emote: ${ja.catchesUp}`,
      'say: 「When does the tide turn?」について — At noon.',
      `say: 「${long.substring(0, 40)}...」について — At noon.`,
    ]);
    expect(en.replayAbout('the tide')).toBe('About "the tide" —');
  });

  it('what she does with her actions is narrated in Japanese', async () => {
    language('ja');
    answering([
      'Let me see.',
      fence('{"action": "equip", "item": "lantern"}'),
      fence('{"action": "doff", "item": "cloak"}'),
      fence('{"action": "consume", "item": "tea"}'),
      fence('{"action": "skill_execute", "skill_name": "weather"}'),
      fence('{"action": "workbench_submit", "skill_name": "tide-table"}'),
      fence('{"action": "think_deeply"}'),
      fence('{"action": "tell_agent", "target": "Rose", "message": "hi"}'),
      fence('{"action": "make_commitment", "description": "water the tomatoes"}'),
      fence('{"action": "delegate_chain", "goal": "tidy the study", "steps": [{"skill": "a"}, {"skill": "b"}]}'),
      fence('{"action": "zone_command", "command": "lights-off"}'),
      fence('{"action": "notify_human", "message": "The tide turns."}'),
      fence('{"action": "create_watcher", "name": "tide", "check": "x"}'),
      fence('{"action": "cancel_watcher", "watcher_id": "w1"}'),
      fence('{"action": "schedule_skill", "skill": "weather", "interval": "1h"}'),
      fence('{"action": "codex_action", "operation": "archive", "itemId": "garden-notes"}'),
      fence('{"action": "create_room", "name": "Greenhouse"}'),
    ].join('\n'));
    router.setRemoteUrl(HOUSEHOLD);

    await aliceSays(quick);

    expect(hers()).toEqual([
      `emote: ${ja.considers}`,
      'say: Let me see.',
      ...[
        ja.equips('lantern'),
        ja.removes('cloak'),
        ja.uses('tea'),
        ja.usesSkill('weather'),
        ja.workbenchSubmit('tide-table'),
        ja.thinkingDeeplyAbout,
        ja.sendsMessage('Rose'),
        ja.commitsTo('water the tomatoes'),
        ja.planning('tidy the study', 2),
        ja.zoneCommand('lights-off'),
        ja.notification('The tide turns.'),
        ja.watchingFor('tide'),
        ja.stopsWatching('w1'),
        ja.schedules('weather', '1h'),
        ja.codexOn('archive', 'garden-notes'),
        ja.roomIdeaLater,
      ].map((line) => `say: ${line}`),
    ]);
    // A translation may put the arguments in another order.
    expect(ja.codexOn('archive', 'garden-notes')).toBe('*garden-notesにarchive*');
  });

  it('what the household did is narrated with Spanish words', async () => {
    language('es');
    const delegate = jest.fn(async (): Promise<DelegationResult> => ({
      text: 'Vamos.',
      actions: [
        { type: 'room_navigated', data: { newRoomId: 'garden', direction: 'north' } },
        { type: 'room_navigated', data: { newRoomId: 'vault', direction: 'the old stair' } },
        { type: 'notification', data: { message: 'La marea sube.', priority: 'high' } },
        { type: 'notification', data: { message: 'Otra.', priority: 'someday' } },
        { type: 'room_created', data: { exitLabel: 'A narrow door' } },
      ],
    }));
    engine.setBudDelegation({ delegate } as unknown as BudDelegation);

    await aliceSays(deep);

    // They came out as "heads north" and "*notification (high)*: …" whatever the language.
    expect(hers()).toEqual([
      `emote: ${es.thinkingDeeply}`,
      'say: Vamos.',
      'emote: se dirige hacia el norte',
      'emote: se dirige hacia the old stair', // an exit's own name, as it is
    ]);
    expect(narrator()).toEqual([
      '*notificación (alta)*: La marea sube.',
      '*notificación (someday)*: Otra.',
      'Aparece un nuevo pasaje: A narrow door',
    ]);
  });

  it('a change in Settings applies to her next line', async () => {
    answering('Hello.');
    router.setRemoteUrl(HOUSEHOLD);

    await aliceSays(quick);
    language('ja');
    await aliceSays(quick);

    expect(hers().filter((h) => h.startsWith('emote: '))).toEqual([`emote: ${en.considers}`, `emote: ${ja.considers}`]);
  });

  it('her unprompted line through the engine is Japanese', async () => {
    language('ja');
    // Drive pressure and budget build over hours of ticks; set them where the
    // judgment acts on the next tick (care over 0.8: she checks in).
    const inner = engine as unknown as { drives: DriveState; budgetWindowStart: number };
    inner.drives = { ...initialDriveState(), care: 0.9 };
    inner.budgetWindowStart = Date.now() - 2 * 3_600_000;

    await jest.advanceTimersByTimeAsync(1_000);

    expect(hers()).toEqual([`say: ${ja.quietCheckIn}`]);
  });
});

describe('ProactivityJudgment — her unprompted lines follow the strings it is given', () => {
  function act(drives: Partial<DriveState>, tier: number, extra: Partial<JudgmentContext> = {}): ProactiveAction {
    const result = evaluate({
      drives: { ...initialDriveState(), ...drives },
      vitality: withConfidence(initialVitality(), 0.5),
      remainingBudget: 3.0,
      lastProactiveActionMs: null,
      lastHumanSpeechMs: null,
      agentEntityId: NEXUS_COMPANION.entityId,
      tier,
      strings: ja,
      ...extra,
    });
    if (result.type !== 'act') throw new Error(`expected an action, got ${JSON.stringify(result)}`);
    return result.action;
  }

  const text = (a: ProactiveAction) =>
    a.tier === 'ambient' ? a.emoteText : a.tier === 'observation' ? a.speechText : a.description;

  it('every line and emote in Japanese', () => {
    const longAgo = Date.now() - 40 * 60_000;
    const said: [string, ProactiveAction][] = [
      [ja.noticedSomething, act({ curiosity: 0.75 }, 0)],
      [ja.exploringSomething, act({ curiosity: 0.8 }, 2)],
      [ja.quietCheckIn, act({ care: 0.9 }, 0)],
      [ja.concernedGlance, act({ care: 0.75 }, 0)],
      [ja.anythingOnYourMind, act({ social: 0.9 }, 0)],
      [ja.beenAWhile, act({ social: 0.9 }, 0, { lastHumanSpeechMs: longAgo })],
      [ja.shiftsThoughtfully, act({ social: 0.55 }, 1)],
      [ja.meaningToFollowUp, act({ achievement: 0.75 }, 0)],
      [ja.actingOnCommitment, act({ achievement: 0.8 }, 2)],
      [ja.patternsShifted, act({ alertness: 0.8 }, 0)],
      [ja.oracleSensed('The tide turns at noon'), act({ alertness: 0.8 }, 0, {
        oraclePredictions: [{ text: 'The tide turns at noon', category: 'pattern', confidence: 0.9 }],
      })],
      // The phone says an initiative below tier 2 as an observation.
      [ja.noticedSomething, act({ curiosity: 0.8 }, 1)],
    ];
    for (const [expected, action] of said) expect(text(action)).toBe(expected);
  });

  it('English when it is given none', () => {
    expect(text(act({ care: 0.9 }, 0, { strings: undefined }))).toBe("Is everything alright? It's been quiet.");
  });
});

describe('Narration strings', () => {
  it('every language has the same direction and priority words, each in its own words', () => {
    for (const [locale, s] of [['ja', ja], ['es', es]] as const) {
      expect([locale, Object.keys(s.directionWords).sort()]).toEqual([locale, Object.keys(en.directionWords).sort()]);
      expect([locale, Object.keys(s.priorityWords).sort()]).toEqual([locale, Object.keys(en.priorityWords).sort()]);
    }
    expect(Object.entries(en.directionWords).every(([code, word]) => code === word)).toBe(true);
    expect(Object.values(ja.directionWords).some((w) => /[A-Za-z]/.test(w))).toBe(false);
    expect(Object.values(ja.priorityWords).some((w) => /[A-Za-z]/.test(w))).toBe(false);
    expect(es.directionWords.north).toBe('el norte');
  });

  it('every line is translated: no Japanese or Spanish entry says what the English one says', () => {
    const say = (s: NarrationStrings, key: keyof NarrationStrings): string => {
      const v = s[key];
      if (typeof v === 'function') return (v as (a: string, b: string) => string)('X', 'Y');
      return typeof v === 'string' ? v : JSON.stringify(v);
    };
    for (const key of Object.keys(en) as (keyof NarrationStrings)[]) {
      expect([key, say(ja, key)]).not.toEqual([key, say(en, key)]);
      expect([key, say(es, key)]).not.toEqual([key, say(en, key)]);
    }
  });

  it('the engine emotes and speaks no English literal', () => {
    const src = readFileSync(join(__dirname, '../../../src/engine/agent/CompanionEngine.ts'), 'utf8');
    const literal = /^(['"`])((?:\\.|(?!\1)[^\\])*)\1/;
    const offenders: string[] = [];
    const check = (where: number, expr: string) => {
      const m = literal.exec(expr.trim());
      // A literal whose own words (outside ${…}) hold a letter is an English line.
      if (m && /[A-Za-z]/.test(m[2].replace(/\$\{[^}]*\}/g, ''))) {
        offenders.push(`line ${src.slice(0, where).split('\n').length}: ${expr.trim()}`);
      }
    };
    for (const m of src.matchAll(/this\.speak\(([^\n]*)/g)) check(m.index ?? 0, m[1]);
    for (const m of src.matchAll(/type: '(?:emote_in_room|say_in_room)',[^}]*?\btext:([^\n]*)/g)) check(m.index ?? 0, m[1]);
    expect(offenders).toEqual([]);
  });
});
