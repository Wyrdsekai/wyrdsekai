/**
 * A phone's own login on the home bus (the pairing reply's nats_user) may use
 * only Study sync under its own name (HouseholdBus.phonePermissions,
 * W2): publish between.{zone}.{user}.*.study.state|sync
 * hear only between.{zone}.*.{user}.study.sync. So with `busUser` the node
 * starts Study sync under that name and none of the LAN machinery the home
 * refuses (presence, events, items, dock, headlines, oracle, MCP proxy).
 */
import { PhoneNode } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { InMemoryBetweenClient, type BetweenMessageHandler } from '../../src/engine/between/BetweenClient';
import { AsyncStorageStudyStore } from '../../src/engine/study/AsyncStorageStudyStore';
import { createMockAsyncStorage } from '../helpers/mockAsyncStorage';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

const mockInference = {
  async complete(_role: ModelRole, messages: ChatMessage[]): Promise<ChatResponse> {
    return { content: `Echo: ${messages[messages.length - 1]?.content ?? ''}`, promptTokens: 1, completionTokens: 1 };
  },
};

/** Records every subscription pattern the node asks for. */
class RecordingBetween extends InMemoryBetweenClient {
  subscribed: string[] = [];
  override subscribe(subject: string, handler: BetweenMessageHandler): () => void {
    this.subscribed.push(subject);
    return super.subscribe(subject, handler);
  }
}

const USER = 'phone-fa236c1b-1bc8-4d9e-ac49-fbcef0896e49';
const ZONE = 'rehearsal';

/** The home's phone grant for USER in ZONE (HouseholdBus.phonePermissions, study part). */
function allowedPub(s: string): boolean {
  return new RegExp(`^between\\.${ZONE}\\.${USER}\\.[^.]+\\.study\\.(state|sync)$`).test(s);
}
function allowedSub(s: string): boolean {
  return new RegExp(`^between\\.${ZONE}\\.[^.]+\\.${USER}\\.study\\.(state|sync)$`).test(s);
}

describe('PhoneNode on the home bus (busUser)', () => {
  let node: PhoneNode;
  let bus: RecordingBetween;

  beforeEach(async () => {
    node = new PhoneNode(new InMemoryEventJournal(), new InMemoryVitalityStore(), mockInference);
    node.studyStore = new AsyncStorageStudyStore(createMockAsyncStorage());
    await node.start();
    bus = new RecordingBetween();
    await bus.connect('wss://home:27223');
  });

  afterEach(() => node.stop());

  it('starts only Study sync, under the login name, and asks only for what the home allows', async () => {
    node.setBetween({
      client: bus, nodeId: 'rn-1', householdId: 'default', companionDid: 'did:wyrd:companion:rn-1',
      zoneId: ZONE, accountUserId: 'u-ada', sessionToken: 'sess', viaRelay: false, busUser: USER,
    });
    await new Promise((r) => setTimeout(r, 20)); // first state broadcast

    expect(node.presenceManager).toBeNull();
    expect(node.phoneDock).toBeNull();
    expect(node.itemExchange).toBeNull();
    expect(node.headlineSyncClient).toBeNull();
    expect(node.mcpGateway.betweenClient).toBeNull();
    expect(node.budDelegation).not.toBeNull(); // HTTP only

    expect(bus.subscribed).toEqual([`between.${ZONE}.*.${USER}.study.sync`]);
    expect(bus.subscribed.every(allowedSub)).toBe(true);
    expect(bus.published.length).toBeGreaterThan(0);
    for (const p of bus.published) expect(allowedPub(p.subject)).toBe(true);
    expect(bus.published[0].subject).toBe(`between.${ZONE}.${USER}.*.study.state`);
    const state = JSON.parse(new TextDecoder().decode(bus.published[0].data));
    expect(state).toMatchObject({ type: 'study_state', deviceId: USER, userDid: 'u-ada', token: 'sess' });
  });

  it("answers the home's directed delta request on its own name", async () => {
    node.setBetween({
      client: bus, nodeId: 'rn-1', householdId: 'default', companionDid: 'did:wyrd:companion:rn-1',
      zoneId: ZONE, accountUserId: 'u-ada', sessionToken: 'sess', busUser: USER,
    });
    await node.studyStore!.putItem({
      id: 'si-1', userDid: 'u-ada', itemType: 'journal', title: 'a note', content: 'a note from the phone',
      collection: '', timestamp: Date.now(), version: 1, vectorClock: { [USER]: 1 }, lastModifiedBy: USER,
    });
    bus.published.length = 0;
    bus.publish(`between.${ZONE}.server-${ZONE}.${USER}.study.sync`, new TextEncoder().encode(JSON.stringify({
      type: 'study_delta_request', deviceId: `server-${ZONE}`, userDid: 'u-ada', clockSummary: {},
    })));
    await new Promise((r) => setTimeout(r, 20));
    const own = bus.published.filter((p) => p.subject.startsWith(`between.${ZONE}.${USER}.`));
    expect(own.map((p) => p.subject)).toContain(`between.${ZONE}.${USER}.server-${ZONE}.study.sync`);
    const delta = JSON.parse(new TextDecoder().decode(own[own.length - 1].data));
    expect(delta.type).toBe('study_delta');
    expect(delta.items.map((i: { content: string }) => i.content)).toContain('a note from the phone');
  });

  it('without busUser (a household machine on the LAN) everything starts as before', () => {
    node.setBetween({ client: bus, nodeId: 'rn-1', householdId: 'hh', companionDid: 'did:wyrd:companion:rn-1' });
    expect(node.presenceManager).not.toBeNull();
    expect(bus.subscribed).toContain('between.hh.*.*.study.state');
  });
});
