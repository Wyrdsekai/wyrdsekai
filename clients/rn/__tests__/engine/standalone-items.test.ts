import { PhoneNode, PhoneNodeEvent } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { TierManager } from '../../src/engine/tier/TierManager';
import type { StudyStore } from '../../src/engine/study/StudyStore';
import type { StudyItem } from '../../src/engine/study/StudyItem';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

/**
 * Standalone (on-phone) item exercise: the Study's runnable furnishings —
 * journal (write/search), library card (search), note/pinboard — must actually
 * run against the local StudyStore in standalone mode, not just describe on
 * examine. examine coverage lives in phone-node.test.ts §2.2; this pins the
 * WRITE/SEARCH item behaviours the mobile app exposes (`journal <text>`,
 * `journal search <q>`, `search the library for <q>`, `note <text>`). (2026-07-24)
 */

const mockInference = {
  async complete(_role: ModelRole, messages: ChatMessage[]): Promise<ChatResponse> {
    const lastUser = messages.filter((m) => m.role === 'user').pop();
    return { content: `Echo: ${lastUser?.content ?? ''}`, promptTokens: 1, completionTokens: 1 };
  },
};

const t2Probe = {
  snapshot: () => ({
    availableMemoryMb: 2500, totalMemoryMb: 4000, batteryPercent: 80,
    isCharging: false, thermalState: 'NOMINAL' as const, hasWifi: true,
  }),
};

/** Minimal in-memory StudyStore — the phone ships SqliteStudyStore; this fake
 *  proves the node ROUTES each item command to the right store method. */
class InMemoryStudyStore implements StudyStore {
  items: StudyItem[] = [];
  private seq = 0;
  private mk(userDid: string, itemType: StudyItem['itemType'], content: string): StudyItem {
    const item = {
      id: `si-${++this.seq}`, userDid, itemType,
      title: content.slice(0, 40), content,
      createdAt: this.seq, updatedAt: this.seq,
    } as unknown as StudyItem;
    this.items.push(item);
    return item;
  }
  async writeJournal(userDid: string, content: string, isPrivate?: boolean): Promise<StudyItem> {
    return this.mk(userDid, isPrivate ? 'journal_private' : 'journal', content);
  }
  async addNote(userDid: string, content: string): Promise<StudyItem> {
    return this.mk(userDid, 'note', content);
  }
  async searchJournal(userDid: string, query: string): Promise<StudyItem[]> {
    return this.items.filter((i) => i.userDid === userDid
      && (i.itemType === 'journal' || i.itemType === 'journal_private')
      && i.content.toLowerCase().includes(query.toLowerCase()));
  }
  async searchAll(userDid: string, query: string): Promise<StudyItem[]> {
    return this.items.filter((i) => i.userDid === userDid
      && i.content.toLowerCase().includes(query.toLowerCase()));
  }
  async recentJournal(userDid: string, limit = 5): Promise<StudyItem[]> {
    return this.items.filter((i) => i.userDid === userDid
      && (i.itemType === 'journal' || i.itemType === 'journal_private'))
      .slice(-limit).reverse();
  }
  // Unused by handleStudyAction — satisfy the interface.
  async editItem(): Promise<StudyItem | null> { return null; }
  async pin(userDid: string, title: string, snippet: string): Promise<StudyItem> {
    return this.mk(userDid, 'pinboard', `${title}: ${snippet}`);
  }
  async getItem(id: string): Promise<StudyItem | null> {
    return this.items.find((i) => i.id === id) ?? null;
  }
  async putItem(item: StudyItem): Promise<void> { this.items.push(item); }
  async deleteItem(id: string): Promise<boolean> {
    const n = this.items.length; this.items = this.items.filter((i) => i.id !== id);
    return this.items.length < n;
  }
  async count(userDid: string): Promise<number> {
    return this.items.filter((i) => i.userDid === userDid).length;
  }
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('standalone Study items run against the local store', () => {
  let node: PhoneNode;
  let store: InMemoryStudyStore;
  let events: PhoneNodeEvent[];

  beforeEach(async () => {
    node = new PhoneNode(
      new InMemoryEventJournal(), new InMemoryVitalityStore(),
      mockInference, new TierManager(t2Probe),
    );
    store = new InMemoryStudyStore();
    node.studyStore = store;
    await node.start();
    events = [];
    node.onEvent((e) => events.push(e));
  });

  afterEach(() => node.stop());

  const prose = () => events.filter((e) => e.type === 'prose').map((e: any) => e.text as string);

  it('`journal <text>` writes a journal entry to the store', async () => {
    await node.say('local-user', 'You', 'journal the variance held its weight today');
    await flush();
    expect(store.items.some((i) => i.itemType === 'journal'
      && i.content.includes('variance held its weight'))).toBe(true);
    expect(prose().some((t) => t.startsWith('Journal entry saved'))).toBe(true);
  });

  it('`journal private <text>` writes a PRIVATE entry', async () => {
    await node.say('local-user', 'You', 'journal private a quiet worry');
    await flush();
    expect(store.items.some((i) => i.itemType === 'journal_private'
      && i.content.includes('quiet worry'))).toBe(true);
  });

  it('`journal search <q>` finds a previously written entry', async () => {
    await node.say('local-user', 'You', 'journal the kettle is warm');
    await flush();
    await node.say('local-user', 'You', 'journal search kettle');
    await flush();
    expect(prose().some((t) => t.startsWith('Found 1 entries'))).toBe(true);
  });

  it('`search the library for <q>` runs the library-card search', async () => {
    await node.say('local-user', 'You', 'note groceries: milk and bread');
    await flush();
    await node.say('local-user', 'You', 'search the library for groceries');
    await flush();
    // narrator confirms the consult AND a result line is emitted
    expect(prose().some((t) => t.includes('consult your library card'))).toBe(true);
    expect(prose().some((t) => t.startsWith('Found 1 results'))).toBe(true);
  });

  it('`note <text>` pins a note', async () => {
    await node.say('local-user', 'You', 'note remember to water the plants');
    await flush();
    expect(store.items.some((i) => i.itemType === 'note'
      && i.content.includes('water the plants'))).toBe(true);
    expect(prose().some((t) => t.includes('pin a note'))).toBe(true);
  });
});
