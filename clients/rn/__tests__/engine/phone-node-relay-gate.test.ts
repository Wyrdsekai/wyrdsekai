/**
 * Over a relay, PhoneNode must start study-sync ONLY.
 *
 * A relay grants a phone the tunnel and study-sync and nothing else. The rest
 * of the Between layer is LAN machinery, and starting it over a relay produced
 * ~8 permission violations per connect plus a rejected presence publish every
 * 30 seconds — which a user experiences as the connection dropping constantly.
 *
 * This is deliberately NOT keyed on "has a home zone": a mode-4 phone has one
 * and legitimately runs its own node. The question is what the TRANSPORT
 * permits.
 *
 *b.
 */
import { PhoneNode } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { InMemoryBetweenClient } from '../../src/engine/between/BetweenClient';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

const mockInference = {
  async complete(_role: ModelRole, _messages: ChatMessage[]): Promise<ChatResponse> {
    return { content: 'ok', promptTokens: 1, completionTokens: 1 };
  },
};

/** The six LAN-only subsystems, by their public accessor. */
const LAN_ONLY = [
  'presenceManager',
  'phoneDock',
  'itemExchange',
  'headlineSyncClient',
] as const;

function baseConfig(client: InMemoryBetweenClient) {
  return {
    client,
    nodeId: 'rn-test',
    householdId: 'hh-test',
    companionDid: 'did:wyrd:companion:rn-test',
    zoneId: 'testzone',
    accountUserId: 'user-1',
    sessionToken: 'tok',
  };
}

describe('PhoneNode Between gating by transport', () => {
  let node: PhoneNode;
  let between: InMemoryBetweenClient;

  beforeEach(async () => {
    node = new PhoneNode(new InMemoryEventJournal(), new InMemoryVitalityStore(), mockInference);
    await node.start();
    between = new InMemoryBetweenClient();
    await between.connect('ws://test');
  });

  afterEach(() => node.stop());

  it('starts the LAN Between subsystems on a household connection', () => {
    node.setBetween({ ...baseConfig(between), viaRelay: false });
    for (const key of LAN_ONLY) {
      expect(node[key]).not.toBeNull();
    }
  });

  it('starts NONE of them over a relay', () => {
    node.setBetween({ ...baseConfig(between), viaRelay: true });
    for (const key of LAN_ONLY) {
      expect(node[key]).toBeNull();
    }
  });

  it('still reports Between as wired over a relay — study-sync is live', () => {
    node.setBetween({ ...baseConfig(between), viaRelay: true });
    expect(node.hasBetween).toBe(true);
  });

  it('omitting viaRelay behaves as a household connection', () => {
    // Default must stay LAN: every existing caller predates the flag, and a
    // silent switch to relay behaviour would disable the Between layer on the
    // LAN, where it is both permitted and required.
    node.setBetween(baseConfig(between));
    expect(node.presenceManager).not.toBeNull();
  });
});
