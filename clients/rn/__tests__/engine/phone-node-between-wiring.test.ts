/**
 * Tests for PhoneNode.setBetween() — Between subsystem wiring.
 *
 * Verifies:
 * - setBetween() creates all subsystems (PresenceManager, BetweenHeadlineSyncClient,
 *   ItemExchangeManager, PhoneDock, HouseholdEventListener, McpGatewayLite)
 * - stop() cleans up all Between subsystems
 * - PhoneNode still works without setBetween() (offline mode)
 * - Calling setBetween() twice tears down the first
 * - Between subsystems receive messages via InMemoryBetweenClient
 */
import { PhoneNode, type PhoneNodeEvent } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { InMemoryBetweenClient } from '../../src/engine/between/BetweenClient';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';
import type { TextMessage, ItemGift } from '../../src/engine/between/PhoneDock';
import type { HouseholdEvent } from '../../src/engine/between/HouseholdEventListener';

const mockInference = {
  async complete(_role: ModelRole, messages: ChatMessage[]): Promise<ChatResponse> {
    const lastUser = messages.filter(m => m.role === 'user').pop();
    return {
      content: `Echo: ${lastUser?.content ?? 'silence'}`,
      promptTokens: 10,
      completionTokens: 5,
    };
  },
};

describe('PhoneNode Between wiring', () => {
  let node: PhoneNode;
  let between: InMemoryBetweenClient;

  beforeEach(async () => {
    node = new PhoneNode(
      new InMemoryEventJournal(),
      new InMemoryVitalityStore(),
      mockInference,
    );
    await node.start();

    between = new InMemoryBetweenClient();
    await between.connect('ws://test');
  });

  afterEach(() => {
    node.stop();
  });

  it('starts without Between (offline mode)', () => {
    expect(node.state).toBe('running');
    expect(node.hasBetween).toBe(false);
    expect(node.presenceManager).toBeNull();
    expect(node.phoneDock).toBeNull();
    expect(node.itemExchange).toBeNull();
    expect(node.headlineSyncClient).toBeNull();
    expect(node.visitingRoom).toBeNull();
    expect(node.mcpGateway).not.toBeNull(); // always present, just unconfigured
    expect(node.mcpGateway.betweenClient).toBeNull();
  });

  it('setBetween creates all subsystems', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'did:wyrd:companion-1',
    });

    expect(node.hasBetween).toBe(true);
    expect(node.presenceManager).not.toBeNull();
    expect(node.phoneDock).not.toBeNull();
    expect(node.itemExchange).not.toBeNull();
    expect(node.headlineSyncClient).not.toBeNull();
    expect(node.mcpGateway.betweenClient).toBe(between);
    expect(node.mcpGateway.nodeId).toBe('node-1');
    expect(node.mcpGateway.householdId).toBe('household-1');
  });

  it('setBetween announces presence as online', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'did:wyrd:companion-1',
    });

    // PresenceManager.announce('online') publishes to between
    const presenceMessages = between.published.filter(
      m => m.subject.includes('presence'),
    );
    expect(presenceMessages.length).toBe(1);
    expect(presenceMessages[0].subject).toBe('between.household-1.presence.node-1');

    const payload = JSON.parse(new TextDecoder().decode(presenceMessages[0].data));
    expect(payload.status).toBe('online');
    expect(payload.nodeId).toBe('node-1');
  });

  it('stop cleans up all Between subsystems', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'did:wyrd:companion-1',
    });

    expect(node.hasBetween).toBe(true);

    node.stop();

    expect(node.hasBetween).toBe(false);
    expect(node.presenceManager).toBeNull();
    expect(node.phoneDock).toBeNull();
    expect(node.itemExchange).toBeNull();
    expect(node.headlineSyncClient).toBeNull();
    expect(node.mcpGateway.betweenClient).toBeNull();
  });

  it('stop announces offline before teardown', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'did:wyrd:companion-1',
    });

    // Clear published to see only stop-related messages
    between.published.length = 0;

    node.stop();

    // Should have announced offline
    const presenceMessages = between.published.filter(
      m => m.subject.includes('presence'),
    );
    expect(presenceMessages.length).toBe(1);
    const payload = JSON.parse(new TextDecoder().decode(presenceMessages[0].data));
    expect(payload.status).toBe('offline');
  });

  it('calling setBetween twice tears down first', async () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'did:wyrd:companion-1',
    });

    const firstPresence = node.presenceManager;
    expect(firstPresence).not.toBeNull();

    const between2 = new InMemoryBetweenClient();
    await between2.connect('ws://test2');

    node.setBetween({
      client: between2,
      nodeId: 'node-2',
      householdId: 'household-2',
      companionDid: 'did:wyrd:companion-2',
    });

    // New subsystems should be wired
    expect(node.presenceManager).not.toBe(firstPresence);
    expect(node.mcpGateway.betweenClient).toBe(between2);
    expect(node.mcpGateway.nodeId).toBe('node-2');
    expect(node.mcpGateway.householdId).toBe('household-2');
  });

  it('PhoneDock receives messages after setBetween', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const msg: TextMessage = {
      type: 'text_message',
      from: 'agent-2',
      content: 'Hello via Between!',
      timestamp: 1000,
    };
    const data = new TextEncoder().encode(JSON.stringify(msg));
    between.publish('between.household-1.dock.companion-1.inbox', data);

    expect(node.phoneDock!.getInbox().length).toBe(1);
    expect((node.phoneDock!.getInbox()[0] as TextMessage).content).toBe('Hello via Between!');
  });

  it('ItemExchangeManager receives items after setBetween', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const transfer = {
      fromDid: 'agent-2',
      toDid: 'companion-1',
      itemJson: { name: 'crystal' },
      timestamp: 1000,
    };
    const data = new TextEncoder().encode(JSON.stringify(transfer));
    between.publish('between.household-1.items.companion-1.inbox', data);

    expect(node.itemExchange!.getQuarantinedItems().length).toBe(1);
  });

  it('HouseholdEventListener receives events after setBetween', () => {
    const events: PhoneNodeEvent[] = [];
    node.onEvent(e => events.push(e));

    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    const event: HouseholdEvent = {
      type: 'agent_arrived',
      agentDid: 'did:wyrd:new-agent',
      agentName: 'NewAgent',
      timestamp: 1000,
    };
    const data = new TextEncoder().encode(JSON.stringify(event));
    between.publish('between.household-1.events', data);

    // Should receive the household event as a state_changed PhoneNodeEvent
    const stateEvents = events.filter(e => e.type === 'state_changed');
    expect(stateEvents.length).toBeGreaterThanOrEqual(1);
  });

  it('householdEvents accumulates received events', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    expect(node.householdEvents.length).toBe(0);

    const event: HouseholdEvent = {
      type: 'steward_announcement',
      stewardDid: 'did:wyrd:steward',
      message: 'Welcome all!',
      timestamp: 1000,
    };
    const data = new TextEncoder().encode(JSON.stringify(event));
    between.publish('between.household-1.events', data);

    expect(node.householdEvents.length).toBe(1);
    expect(node.householdEvents[0].type).toBe('steward_announcement');
  });

  it('BetweenHeadlineSyncClient publishes headlines after setBetween', () => {
    node.setBetween({
      client: between,
      nodeId: 'node-1',
      householdId: 'household-1',
      companionDid: 'companion-1',
    });

    // Clear the initial presence publish
    between.published.length = 0;

    node.headlineSyncClient!.postHeadline({
      budDid: 'node-1',
      summary: 'Exploring the nexus',
      vitalitySnapshot: { energy: 0.8 },
      itemCount: 3,
      timestamp: Date.now(),
    });

    const headlineMessages = between.published.filter(
      m => m.subject.includes('headlines'),
    );
    expect(headlineMessages.length).toBe(1);
  });
});
