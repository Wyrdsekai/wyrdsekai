import { InMemoryBetweenClient } from '../../../src/engine/between/BetweenClient';
import { ItemExchangeManager, type ItemTransfer } from '../../../src/engine/between/ItemExchangeManager';

const sampleItem = { id: 'item-sacred-01', name: 'Sacred Crystal', significance: 0.9 };

describe('ItemExchangeManager', () => {
  it('send publishes to correct inbox subject', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new ItemExchangeManager(between, 'bud-1', 'household-1');

    manager.sendItem('bud-2', sampleItem, 'A gift for you');

    expect(between.published.length).toBe(1);
    expect(between.published[0].subject).toBe('between.household-1.items.bud-2.inbox');
  });

  it('send includes item and message', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new ItemExchangeManager(between, 'bud-1', 'household-1');

    manager.sendItem('bud-2', sampleItem, 'A gift for you');

    const payload = JSON.parse(
      new TextDecoder().decode(between.published[0].data),
    ) as ItemTransfer;
    expect(payload.fromDid).toBe('bud-1');
    expect(payload.toDid).toBe('bud-2');
    expect(payload.message).toBe('A gift for you');
  });

  it('received items go to quarantine', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new ItemExchangeManager(between, 'bud-1', 'household-1');
    manager.startListening();

    // Simulate inbound item
    const transfer: ItemTransfer = {
      fromDid: 'bud-2',
      toDid: 'bud-1',
      itemJson: sampleItem,
      message: "Here's a crystal",
      timestamp: 1000,
    };
    const data = new TextEncoder().encode(JSON.stringify(transfer));
    between.publish('between.household-1.items.bud-1.inbox', data);

    const quarantined = manager.getQuarantinedItems();
    expect(quarantined.length).toBe(1);
    expect(quarantined[0].fromDid).toBe('bud-2');
    expect(quarantined[0].message).toBe("Here's a crystal");

    manager.stopListening();
  });

  it('clear quarantine removes all items', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new ItemExchangeManager(between, 'bud-1', 'household-1');
    manager.startListening();

    // Simulate two inbound items
    for (let i = 1; i <= 2; i++) {
      const transfer: ItemTransfer = {
        fromDid: `bud-${i}`,
        toDid: 'bud-1',
        itemJson: `item-${i}`,
        timestamp: 1000 + i,
      };
      const data = new TextEncoder().encode(JSON.stringify(transfer));
      between.publish('between.household-1.items.bud-1.inbox', data);
    }

    expect(manager.getQuarantinedItems().length).toBe(2);

    manager.clearQuarantine();
    expect(manager.getQuarantinedItems().length).toBe(0);

    manager.stopListening();
  });

  it('send without message omits field', async () => {
    const between = new InMemoryBetweenClient();
    await between.connect('ws://test');
    const manager = new ItemExchangeManager(between, 'bud-1', 'household-1');

    manager.sendItem('bud-2', sampleItem);

    const payload = JSON.parse(
      new TextDecoder().decode(between.published[0].data),
    ) as ItemTransfer;
    expect(payload.message).toBeUndefined();
  });
});
