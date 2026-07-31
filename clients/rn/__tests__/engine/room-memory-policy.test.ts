import { RoomMemoryPolicy } from '../../src/engine/room/RoomMemoryPolicy';
import type { Said } from '../../src/engine/events/WorldEvent';

function makeSaid(text: string): Said {
  return {
    type: 'said', roomId: 'test', timestamp: Date.now(),
    entityId: 'p1', entityName: 'Alice', text,
  };
}

describe('RoomMemoryPolicy', () => {
  it('adds events to hot buffer', () => {
    const policy = new RoomMemoryPolicy(10, 20);
    policy.add(makeSaid('Hello'));
    expect(policy.getHotEvents()).toHaveLength(1);
    expect(policy.getWarmEvents()).toHaveLength(0);
  });

  it('overflows hot to warm when hot exceeds size', () => {
    const policy = new RoomMemoryPolicy(3, 20);
    for (let i = 0; i < 5; i++) {
      policy.add(makeSaid(`msg-${i}`));
    }
    expect(policy.getHotEvents()).toHaveLength(3);
    expect(policy.getWarmEvents()).toHaveLength(2);
    expect(policy.getHotEvents()[0].text).toBe('msg-2');
    expect(policy.getWarmEvents()[0].text).toBe('msg-0');
  });

  it('drops warm events when warm exceeds size', () => {
    const policy = new RoomMemoryPolicy(2, 3);
    for (let i = 0; i < 10; i++) {
      policy.add(makeSaid(`msg-${i}`));
    }
    expect(policy.getHotEvents()).toHaveLength(2);
    expect(policy.getWarmEvents()).toHaveLength(3);
    // Oldest warm events were dropped
    expect(policy.getAllEvents()).toHaveLength(5);
  });

  it('clear resets all buffers', () => {
    const policy = new RoomMemoryPolicy(5, 5);
    for (let i = 0; i < 8; i++) {
      policy.add(makeSaid(`msg-${i}`));
    }
    policy.clear();
    expect(policy.getHotEvents()).toHaveLength(0);
    expect(policy.getWarmEvents()).toHaveLength(0);
    expect(policy.getAllEvents()).toHaveLength(0);
  });
});
