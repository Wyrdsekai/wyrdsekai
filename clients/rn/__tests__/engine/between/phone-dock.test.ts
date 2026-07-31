import { InMemoryBetweenClient } from '../../../src/engine/between/BetweenClient';
import {
  PhoneDock,
  MAX_MESSAGE_LENGTH,
  type DockMessage,
  type TextMessage,
  type ItemGift,
  type Introduction,
} from '../../../src/engine/between/PhoneDock';

function publishDockMessage(
  between: InMemoryBetweenClient,
  companionDid: string,
  householdId: string,
  message: DockMessage,
  subtopic = 'inbox',
): void {
  const data = new TextEncoder().encode(JSON.stringify(message));
  between.publish(`between.${householdId}.dock.${companionDid}.${subtopic}`, data);
}

describe('PhoneDock', () => {
  it('dock subscribes to correct subject', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');
    dock.startListening();

    const msg: TextMessage = {
      type: 'text_message',
      from: 'agent-2',
      content: 'Hello!',
      timestamp: 1000,
    };
    publishDockMessage(between, 'companion-1', 'household-1', msg);

    expect(dock.getInbox().length).toBe(1);
    const received = dock.getInbox()[0] as TextMessage;
    expect(received.content).toBe('Hello!');
    expect(received.from).toBe('agent-2');

    dock.stopListening();
  });

  it('rate limiting blocks excess messages', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');
    dock.startListening();

    // Send 11 messages from the same agent (limit is 10/hour)
    for (let i = 1; i <= 11; i++) {
      const msg: TextMessage = {
        type: 'text_message',
        from: 'spammer-1',
        content: `Message ${i}`,
        timestamp: 1000 + i,
      };
      publishDockMessage(between, 'companion-1', 'household-1', msg);
    }

    // Only 10 should get through
    expect(dock.getInbox().length).toBe(10);

    dock.stopListening();
  });

  it('different agents have separate rate limits', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');
    dock.startListening();

    // 10 messages from agent-a and 10 from agent-b — both should pass
    for (let i = 1; i <= 10; i++) {
      const msgA: TextMessage = {
        type: 'text_message', from: 'agent-a', content: `A-${i}`, timestamp: 1000 + i,
      };
      publishDockMessage(between, 'companion-1', 'household-1', msgA);
      const msgB: TextMessage = {
        type: 'text_message', from: 'agent-b', content: `B-${i}`, timestamp: 2000 + i,
      };
      publishDockMessage(between, 'companion-1', 'household-1', msgB);
    }

    expect(dock.getInbox().length).toBe(20);

    dock.stopListening();
  });

  it('message sanitization strips long content', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');
    dock.startListening();

    // Send a message longer than 4096 characters
    const longContent = 'x'.repeat(5000);
    const msg: TextMessage = {
      type: 'text_message',
      from: 'agent-2',
      content: longContent,
      timestamp: 1000,
    };
    publishDockMessage(between, 'companion-1', 'household-1', msg);

    expect(dock.getInbox().length).toBe(1);
    const received = dock.getInbox()[0] as TextMessage;
    expect(received.content.length).toBe(MAX_MESSAGE_LENGTH);

    dock.stopListening();
  });

  it('message sanitization strips control characters', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');
    dock.startListening();

    // Content with control characters (preserve newlines and tabs)
    const content = 'Hello\x00World\x07!\nNew line\tTab';
    const msg: TextMessage = {
      type: 'text_message',
      from: 'agent-2',
      content,
      timestamp: 1000,
    };
    publishDockMessage(between, 'companion-1', 'household-1', msg);

    expect(dock.getInbox().length).toBe(1);
    const received = dock.getInbox()[0] as TextMessage;
    expect(received.content).toBe('HelloWorld!\nNew line\tTab');

    dock.stopListening();
  });

  it('blank sender DID rejected', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');
    dock.startListening();

    const msg: TextMessage = {
      type: 'text_message',
      from: '',
      content: 'Should be rejected',
      timestamp: 1000,
    };
    publishDockMessage(between, 'companion-1', 'household-1', msg);

    expect(dock.getInbox().length).toBe(0);

    dock.stopListening();
  });

  it('item gifts go to quarantine', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');
    dock.startListening();

    const gift: ItemGift = {
      type: 'item_gift',
      from: 'agent-2',
      itemJson: 'sacred-crystal',
      message: 'A gift for you',
      timestamp: 1000,
    };
    publishDockMessage(between, 'companion-1', 'household-1', gift);

    // Items go to quarantine, not inbox
    expect(dock.getInbox().length).toBe(0);
    expect(dock.getQuarantinedItems().length).toBe(1);
    expect(dock.getQuarantinedItems()[0].message).toBe('A gift for you');

    dock.stopListening();
  });

  it('send message publishes to target dock', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');

    const msg: TextMessage = {
      type: 'text_message',
      from: 'companion-1',
      content: 'Hello there',
      timestamp: 2000,
    };
    dock.sendMessage('agent-2', msg);

    expect(between.published.length).toBe(1);
    expect(between.published[0].subject).toBe('between.household-1.dock.agent-2.inbox');

    dock.stopListening();
  });

  it('introduction message accepted', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const dock = new PhoneDock(between, 'companion-1', 'household-1');
    dock.startListening();

    const intro: Introduction = {
      type: 'introduction',
      agentDid: 'agent-3',
      agentName: 'Visitor',
      timestamp: 1000,
    };
    publishDockMessage(between, 'companion-1', 'household-1', intro);

    expect(dock.getInbox().length).toBe(1);
    expect(dock.getInbox()[0].type).toBe('introduction');

    dock.stopListening();
  });
});
