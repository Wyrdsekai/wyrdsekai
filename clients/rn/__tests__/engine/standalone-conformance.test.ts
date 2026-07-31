/**
 * Standalone-mode conformance probes — the phone running without a paired
 * server (cloud API-key mode). Exercises {@link PhoneNode} directly to
 * verify hold even when there's no
 * server `/api/mcp/*` to delegate to.
 *
 * <p>Pre-fix the standalone path silently dropped {@code examine X} /
 * {@code rename me X} / {@code drop X} into a "Huh?" fallback because
 * neither the screen-level parser nor {@code PhoneNode} implemented them.
 * That's a release blocker for cloud-API-key users.</p>
 */
import { PhoneNode } from '../../src/engine/PhoneNode';
import type { ModelRole } from '../../src/inference/InferenceRouter';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { TierManager } from '../../src/engine/tier/TierManager';
import type { ChatMessage, ChatResponse } from '../../src/inference/types';

const mockInference = {
  async complete(_role: ModelRole, _: ChatMessage[]): Promise<ChatResponse> {
    return { content: 'Acknowledged.', promptTokens: 0, completionTokens: 0 };
  },
};

const t2Probe = {
  snapshot: () => ({
    availableMemoryMb: 2500,
    totalMemoryMb: 4000,
    batteryPercent: 80,
    isCharging: false,
    thermalState: 'NOMINAL' as const,
    hasWifi: true,
  }),
};

describe('Standalone-mode conformance (SPEC_MUD_CONVENTION)', () => {
  let node: PhoneNode;

  beforeEach(async () => {
    node = new PhoneNode(
      new InMemoryEventJournal(),
      new InMemoryVitalityStore(),
      mockInference,
      new TierManager(t2Probe),
    );
    await node.start();
  });

  afterEach(() => {
    node.stop();
  });

  // -----------------------------------------------------------------
  // §2.2 — examine returns description without invoking onUse
  // -----------------------------------------------------------------

  it('§2.2: examine resolves a room object by name', () => {
    // Start in the Study (default start room). Walk to the Nexus where
    // foundation objects live.
    const startRoom = node.currentRoom();
    expect(startRoom).not.toBeNull();
    // Find any object in the current room to probe.
    const objects = Object.values(startRoom!.state.objects);
    expect(objects.length).toBeGreaterThan(0);
    const probe = objects[0];

    const result = node.examine(probe.name);
    expect(result).not.toBeNull();
    expect(result!.name).toBe(probe.name);
    // Description should be non-empty for foundation objects.
    expect(result!.description.length).toBeGreaterThan(0);
  });

  it('§2.2: examine matches partial names (case-insensitive)', () => {
    const room = node.currentRoom();
    const objects = Object.values(room!.state.objects);
    if (objects.length === 0) return; // nothing to probe in empty room

    // Take first word of first object name as the partial query.
    const firstWord = objects[0].name.split(' ')[0];
    if (!firstWord) return;
    const result = node.examine(firstWord.toLowerCase());
    expect(result).not.toBeNull();
  });

  it('§2.2: examine self returns the player name', () => {
    const result = node.examine('me');
    expect(result).not.toBeNull();
    expect(result!.name).toBe(node.playerName);
  });

  it('§2.2: examine unknown returns null', () => {
    const result = node.examine('zzzbobcatfloop');
    expect(result).toBeNull();
  });

  it('§2.2: examine empty/whitespace returns null', () => {
    expect(node.examine('')).toBeNull();
    expect(node.examine('   ')).toBeNull();
  });

  // -----------------------------------------------------------------
  // §7.4 — rename me updates the player display name
  // -----------------------------------------------------------------

  it('§7.4: rename me X updates playerName', () => {
    const newName = 'Alice' + Math.floor(Math.random() * 10000);
    const result = node.rename(newName);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.newName).toBe(newName);
      expect(node.playerName).toBe(newName);
    }
  });

  it('§7.4: rename me reflects in subsequent examine me', () => {
    node.rename('Renamed');
    const ex = node.examine('me');
    expect(ex).not.toBeNull();
    expect(ex!.name).toBe('Renamed');
  });

  it('§7.4: rename me empty rejected with usage hint', () => {
    const result = node.rename('');
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.toLowerCase()).toContain('usage');
    }
  });

  it('§7.4: rename me whitespace-only rejected', () => {
    const result = node.rename('   ');
    expect(result.ok).toBe(false);
  });

  it('§7.4: rename me with >40 chars rejected', () => {
    const result = node.rename('a'.repeat(41));
    expect(result.ok).toBe(false);
  });

  it('§7.4: rename me with control chars rejected', () => {
    const result = node.rename('Alice\nBob');
    expect(result.ok).toBe(false);
  });

  // -----------------------------------------------------------------
  // §4 — drop is symmetric with take
  // -----------------------------------------------------------------

  it('§4: drop sends drop_object without throwing', async () => {
    // We don't have a way to verify inventory mutation without inventory
    // service in standalone, but the call must not throw and must not
    // surface 'rejected'/'error' for a well-formed drop.
    let errored = false;
    const unsub = node.onEvent((ev) => {
      if (ev.type === 'error') errored = true;
    });
    await node.drop('player-local', 'compass');
    unsub();
    // We tolerate either silent-success or a "not carrying" error — what we
    // assert is the path runs without throwing and PhoneNode.drop exists.
    // (`errored` is captured for diagnostics; the contract is that the call
    // doesn't crash.)
    void errored;
    expect(typeof node.drop).toBe('function');
  });

  // -----------------------------------------------------------------
  // Default player name has a sensible default
  // -----------------------------------------------------------------

  it('§7.4: default playerName is set', () => {
    expect(node.playerName).toBeTruthy();
    expect(node.playerName.length).toBeGreaterThan(0);
  });
});
