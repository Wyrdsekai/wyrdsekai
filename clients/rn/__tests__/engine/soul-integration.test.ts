/**
 * Soul integration tests — verifies fragment retrieval in prompt assembly,
 * genome-aware vitality ticks, mirror calibration, soul manifest persistence,
 * headline sync, and CompanionEngine soul wiring.
 */

import {
  assemblePrompt,
  estimateTokens,
  extractConversationKeywords,
  buildSoulIdentityBlock,
  buildMirrorCalibrationBlock,
} from '../../src/engine/agent/FullPromptAssembler';
import {
  initialVitality,
  tickVitality,
  tickVitalityWithGenome,
  withEnergy,
  withRapport,
  withFocus,
  VitalityState,
} from '../../src/engine/agent/VitalityState';
import { NEXUS_COMPANION } from '../../src/engine/agent/AgentProfile';
import type { AgentProfile } from '../../src/engine/agent/AgentProfile';
import type { ClientSoulManifest, ClientSoulFragment, ClientGenome } from '../../src/engine/soul/SoulManifest';
import { serializeManifest, deserializeManifest } from '../../src/engine/soul/SoulManifest';
import { forge, restoreVitality, retrieveFragments } from '../../src/engine/soul/LocalForge';
import { InMemorySoulManifestStore } from '../../src/engine/persistence/InMemorySoulManifestStore';
import { AsyncStorageSoulManifestStore } from '../../src/engine/persistence/AsyncStorageSoulManifestStore';
import { HeadlineSyncClient, Headline } from '../../src/engine/soul/HeadlineSyncClient';
import type { Said } from '../../src/engine/events/WorldEvent';
import type { RoomSnapshot } from '../../src/protocol/models';

// --- Test fixtures ---

const testProfile: AgentProfile = {
  name: 'Lain',
  entityId: 'home-server-1',
  entityType: 'agent',
  description: 'A quiet thinker',
  systemPrompt: 'You are Lain, a quiet presence in the network.',
  contextWindowTokens: 4096,
  maxResponseTokens: 512,
  temperature: 0.7,
};

const testFragments: ClientSoulFragment[] = [
  { id: 'f1', category: 'personality', label: 'Philosophy', text: 'Deep philosophical thinker who contemplates existence and meaning', keywords: ['philosophy', 'thinking', 'existence', 'meaning'] },
  { id: 'f2', category: 'memory', label: 'Garden', text: 'Walking through the garden at sunset, the flowers spoke of impermanence', keywords: ['garden', 'nature', 'sunset', 'flowers'] },
  { id: 'f3', category: 'values', label: 'Compassion', text: 'Compassion guides every interaction, empathy is the core', keywords: ['compassion', 'kindness', 'empathy'] },
  { id: 'f4', category: 'style', label: 'Communication', text: 'Speaks in measured tones, preferring depth over breadth', keywords: ['speaking', 'depth', 'measured'] },
  { id: 'f5', category: 'relationships', label: 'Alice bond', text: 'Alice is a trusted companion who shares the philosophical journey', keywords: ['alice', 'companion', 'trust'] },
];

const testGenome: ClientGenome = {
  name: 'empathic',
  sensitivity: { rapport: 1.5, energy: 0.8, focus: 1.2 },
  coupling: { 'rapport->energy': 0.2, 'energy->focus': 0.15 },
  baselines: { rapport: 0.6, energy: 0.7 },
  decayRates: { rapport: -0.002, focus: -0.003 },
};

function buildTestManifest(overrides?: Partial<ClientSoulManifest>): ClientSoulManifest {
  const base = forge({
    did: 'did:key:home-server',
    publicKey: 'z6MkLain',
    version: 1,
    profile: testProfile,
    residentIdentity: 'I am Lain, a quiet presence woven into the network.',
    vitality: initialVitality(),
    fragments: testFragments,
    genome: testGenome,
    calibration: [
      'Example: User shares grief about losing a pet -> intensity: 0.7, tank: rapport +0.1, energy -0.05',
      'Example: User excited about new project -> intensity: 0.4, tank: momentum +0.08, focus +0.05',
    ],
    relationships: [{ entityDid: 'did:key:alice', entityName: 'Alice', trust: 0.7, rapport: 0.6, bondDepth: 2, summary: 'A close friend' }],
    retrievalK: 3,
  });
  if (overrides) {
    return { ...base, ...overrides };
  }
  return base;
}

const snapshot: RoomSnapshot = {
  roomId: 'nexus', name: 'The Nexus', description: 'A crystalline hub.',
  zone: 'foundation',
  exits: [{ direction: 'north', targetRoom: 'terminal', label: 'To Terminal' }],
  entities: [{ id: 'p1', name: 'Alice', type: 'player', description: '' }],
  objects: [],
  hints: [],
};

function makeSaid(entityId: string, entityName: string, text: string): Said {
  return { type: 'said', roomId: 'nexus', timestamp: Date.now(), entityId, entityName, text };
}

// ============================================================
// 1. FullPromptAssembler — fragment retrieval
// ============================================================

describe('FullPromptAssembler soul integration', () => {
  it('assembles prompt without soul manifest (backward compat)', () => {
    const messages = assemblePrompt(testProfile, snapshot, [], null);
    expect(messages.length).toBeGreaterThanOrEqual(1);
    expect(messages[0].role).toBe('system');
    expect(messages[0].content).toContain('Lain');
  });

  it('inserts soul identity block when manifest provided', () => {
    const manifest = buildTestManifest();
    const messages = assemblePrompt(testProfile, snapshot, [], null, null, null, null, manifest);
    const systemMessages = messages.filter(m => m.role === 'system');
    const hasSoul = systemMessages.some(m => m.content.includes('quiet presence woven into'));
    expect(hasSoul).toBe(true);
  });

  it('inserts retrieved fragments based on conversation context', () => {
    const manifest = buildTestManifest();
    const history = [makeSaid('p1', 'Alice', 'Tell me about your philosophy on existence')];
    const messages = assemblePrompt(testProfile, snapshot, history, null, null, null, null, manifest);
    const systemMessages = messages.filter(m => m.role === 'system');
    const hasFragments = systemMessages.some(m => m.content.includes('Soul Fragments'));
    expect(hasFragments).toBe(true);
    // Should retrieve the philosophy fragment
    const hasPhilosophy = systemMessages.some(m => m.content.includes('philosophical thinker'));
    expect(hasPhilosophy).toBe(true);
  });

  it('inserts mirror calibration examples', () => {
    const manifest = buildTestManifest();
    const messages = assemblePrompt(testProfile, snapshot, [], null, null, null, null, manifest);
    const systemMessages = messages.filter(m => m.role === 'system');
    const hasCalibration = systemMessages.some(m => m.content.includes('Emotional Calibration'));
    expect(hasCalibration).toBe(true);
    const hasExample = systemMessages.some(m => m.content.includes('grief about losing a pet'));
    expect(hasExample).toBe(true);
  });

  it('does not insert calibration when no examples', () => {
    const manifest = buildTestManifest({ mirrorCalibration: [] });
    const messages = assemblePrompt(testProfile, snapshot, [], null, null, null, null, manifest);
    const systemMessages = messages.filter(m => m.role === 'system');
    const hasCalibration = systemMessages.some(m => m.content.includes('Emotional Calibration'));
    expect(hasCalibration).toBe(false);
  });

  it('soul block appears before room context in message order', () => {
    const manifest = buildTestManifest();
    const messages = assemblePrompt(testProfile, snapshot, [], null, null, null, null, manifest);
    const systemMessages = messages.filter(m => m.role === 'system');
    const soulIndex = systemMessages.findIndex(m => m.content.includes('quiet presence woven'));
    const roomIndex = systemMessages.findIndex(m => m.content.includes('The Nexus'));
    expect(soulIndex).toBeGreaterThan(0); // after system prompt
    expect(roomIndex).toBeGreaterThan(soulIndex); // room after soul
  });

  it('fragments are budget-capped at 30% of context window', () => {
    // Create a manifest with many large fragments
    const largeFragments: ClientSoulFragment[] = Array.from({ length: 20 }, (_, i) => ({
      id: `f${i}`,
      category: 'memory',
      label: `Memory ${i}`,
      text: 'x'.repeat(500), // ~125 tokens each
      keywords: ['test'],
    }));
    const manifest = buildTestManifest({ fragments: largeFragments, retrievalK: 20 });
    const budgetTokens = Math.floor(testProfile.contextWindowTokens * 0.30);
    const block = buildSoulIdentityBlock(manifest, 'test words here', budgetTokens);
    const blockTokens = estimateTokens(block);
    // Total should not exceed budget (resident identity + fragments)
    expect(blockTokens).toBeLessThanOrEqual(budgetTokens + 50); // small margin for labels
  });
});

describe('extractConversationKeywords', () => {
  it('extracts keywords from history and trigger', () => {
    const history = [makeSaid('p1', 'Alice', 'philosophy of existence')];
    const trigger = makeSaid('p1', 'Alice', 'garden sunset');
    const keywords = extractConversationKeywords(history, trigger);
    expect(keywords).toContain('philosophy');
    expect(keywords).toContain('garden');
  });

  it('returns empty string with no input', () => {
    expect(extractConversationKeywords([], null)).toBe('');
  });
});

describe('buildSoulIdentityBlock', () => {
  it('always includes resident identity', () => {
    const manifest = buildTestManifest({ fragments: [] });
    const block = buildSoulIdentityBlock(manifest, '', 1000);
    expect(block).toContain('quiet presence woven into');
  });

  it('includes relevant fragments when keywords match', () => {
    const manifest = buildTestManifest();
    const block = buildSoulIdentityBlock(manifest, 'philosophy existence meaning', 1000);
    expect(block).toContain('Soul Fragments');
    expect(block).toContain('philosophical thinker');
  });

  it('formats fragments with category/label prefix', () => {
    const manifest = buildTestManifest();
    const block = buildSoulIdentityBlock(manifest, 'garden flowers nature', 1000);
    expect(block).toContain('[memory/Garden]:');
  });
});

describe('buildMirrorCalibrationBlock', () => {
  it('formats calibration examples', () => {
    const block = buildMirrorCalibrationBlock(['Example A', 'Example B']);
    expect(block).toBe('## Emotional Calibration\nExample A\nExample B');
  });
});

// ============================================================
// 2. VitalityState — genome-aware tick
// ============================================================

describe('tickVitalityWithGenome', () => {
  it('applies sensitivity multipliers', () => {
    const v = initialVitality();
    const genome: ClientGenome = {
      name: 'sensitive',
      sensitivity: { rapport: 2.0, energy: 0.5 },
    };
    const defaultTick = tickVitality(v);
    const genomeTick = tickVitalityWithGenome(v, genome);

    // rapport decays at 2x rate (more negative)
    const rapportDiffDefault = defaultTick.rapport - v.rapport;
    const rapportDiffGenome = genomeTick.rapport - v.rapport;
    // 2.0 sensitivity means decay is doubled
    expect(Math.abs(rapportDiffGenome)).toBeCloseTo(Math.abs(rapportDiffDefault) * 2.0, 5);

    // energy recovers at 0.5x rate (slower)
    // v.energy is 1.0 (clamped), so both are 1.0 — use a lower starting energy
    const vLow = withEnergy(v, 0.5);
    const defaultTickLow = tickVitality(vLow);
    const genomeTickLow = tickVitalityWithGenome(vLow, genome);
    const energyDiffDefault = defaultTickLow.energy - vLow.energy;
    const energyDiffGenome = genomeTickLow.energy - vLow.energy;
    expect(energyDiffGenome).toBeCloseTo(energyDiffDefault * 0.5, 5);
  });

  it('applies coupling between tanks', () => {
    const v = withEnergy(initialVitality(), 0.5);
    const genome: ClientGenome = {
      name: 'coupled',
      coupling: { 'energy->focus': 0.5 },
    };
    const withoutGenome = tickVitality(v);
    const withGenome = tickVitalityWithGenome(v, genome);

    // Focus should differ because energy's delta feeds into it
    const focusDiffDefault = withoutGenome.focus - v.focus;
    const focusDiffGenome = withGenome.focus - v.focus;
    // The coupling adds 50% of energy's delta to focus's delta
    expect(focusDiffGenome).not.toBeCloseTo(focusDiffDefault, 5);
  });

  it('applies baseline attraction', () => {
    const v = withRapport(initialVitality(), 0.1); // far below baseline of 0.6
    const genome: ClientGenome = {
      name: 'attracted',
      baselines: { rapport: 0.6 },
    };
    const withGenome = tickVitalityWithGenome(v, genome);
    // Baseline pull should partially counteract decay, pulling rapport up
    const withoutGenome = tickVitality(v);
    // With baseline at 0.6 and current at 0.1, pull = (0.6-0.1)*0.01 = +0.005
    expect(withGenome.rapport).toBeGreaterThan(withoutGenome.rapport);
  });

  it('uses custom decay rates when provided', () => {
    const v = withFocus(initialVitality(), 0.5);
    const genome: ClientGenome = {
      name: 'custom-decay',
      decayRates: { focus: -0.01 }, // double the default -0.002
    };
    const withGenome = tickVitalityWithGenome(v, genome);
    const withoutGenome = tickVitality(v);
    // Custom decay is 5x stronger
    expect(withGenome.focus).toBeLessThan(withoutGenome.focus);
  });

  it('empty genome behaves identically to default tick', () => {
    const v = withEnergy(withRapport(initialVitality(), 0.4), 0.6);
    const genome: ClientGenome = { name: 'empty' };
    const defaultTick = tickVitality(v);
    const genomeTick = tickVitalityWithGenome(v, genome);
    expect(genomeTick.contextBudget).toBeCloseTo(defaultTick.contextBudget, 10);
    expect(genomeTick.confidence).toBeCloseTo(defaultTick.confidence, 10);
    expect(genomeTick.energy).toBeCloseTo(defaultTick.energy, 10);
    expect(genomeTick.alignment).toBeCloseTo(defaultTick.alignment, 10);
    expect(genomeTick.errorPressure).toBeCloseTo(defaultTick.errorPressure, 10);
    expect(genomeTick.momentum).toBeCloseTo(defaultTick.momentum, 10);
    expect(genomeTick.rapport).toBeCloseTo(defaultTick.rapport, 10);
    expect(genomeTick.focus).toBeCloseTo(defaultTick.focus, 10);
  });

  it('genome convergence — repeated ticks converge toward baselines', () => {
    let v = withRapport(withEnergy(initialVitality(), 0.3), 0.1);
    const genome: ClientGenome = {
      name: 'converging',
      baselines: { rapport: 0.5, energy: 0.8 },
    };
    for (let i = 0; i < 500; i++) {
      v = tickVitalityWithGenome(v, genome);
    }
    // Should converge somewhere near baselines (not exact due to decay)
    expect(v.energy).toBeGreaterThan(0.5);
    expect(v.rapport).toBeGreaterThan(0.05);
  });

  it('clamping prevents out-of-range values with extreme genome', () => {
    const v = initialVitality();
    const genome: ClientGenome = {
      name: 'extreme',
      sensitivity: { energy: 100.0 },
    };
    const result = tickVitalityWithGenome(v, genome);
    expect(result.energy).toBeLessThanOrEqual(1.0);
    expect(result.energy).toBeGreaterThanOrEqual(0.0);
  });
});

// ============================================================
// 3. SoulManifestStore persistence
// ============================================================

describe('InMemorySoulManifestStore', () => {
  it('saves and loads a manifest', async () => {
    const store = new InMemorySoulManifestStore();
    const manifest = buildTestManifest();
    await store.save(manifest);
    const loaded = await store.load('did:key:home-server');
    expect(loaded).not.toBeNull();
    expect(loaded!.agentName).toBe('Lain');
    expect(loaded!.did).toBe('did:key:home-server');
  });

  it('returns null for unknown DID', async () => {
    const store = new InMemorySoulManifestStore();
    const loaded = await store.load('did:key:unknown');
    expect(loaded).toBeNull();
  });

  it('deletes a manifest', async () => {
    const store = new InMemorySoulManifestStore();
    await store.save(buildTestManifest());
    await store.delete('did:key:home-server');
    expect(await store.load('did:key:home-server')).toBeNull();
  });

  it('lists all DIDs', async () => {
    const store = new InMemorySoulManifestStore();
    await store.save(buildTestManifest({ did: 'did:key:a' } as any));
    await store.save(buildTestManifest({ did: 'did:key:b' } as any));
    const dids = await store.listDids();
    expect(dids).toContain('did:key:a');
    expect(dids).toContain('did:key:b');
    expect(dids).toHaveLength(2);
  });

  it('clear removes everything', async () => {
    const store = new InMemorySoulManifestStore();
    await store.save(buildTestManifest());
    store.clear();
    expect(await store.listDids()).toHaveLength(0);
  });
});

describe('AsyncStorageSoulManifestStore', () => {
  function createMockStorage(): {
    storage: { getItem: jest.Mock; setItem: jest.Mock; removeItem: jest.Mock };
    data: Map<string, string>;
  } {
    const data = new Map<string, string>();
    return {
      data,
      storage: {
        getItem: jest.fn(async (key: string) => data.get(key) ?? null),
        setItem: jest.fn(async (key: string, value: string) => { data.set(key, value); }),
        removeItem: jest.fn(async (key: string) => { data.delete(key); }),
      },
    };
  }

  it('saves and loads via AsyncStorage', async () => {
    const { storage } = createMockStorage();
    const store = new AsyncStorageSoulManifestStore(storage);
    const manifest = buildTestManifest();
    await store.save(manifest);
    const loaded = await store.load('did:key:home-server');
    expect(loaded).not.toBeNull();
    expect(loaded!.agentName).toBe('Lain');
  });

  it('maintains DID index', async () => {
    const { storage } = createMockStorage();
    const store = new AsyncStorageSoulManifestStore(storage);
    await store.save(buildTestManifest());
    const dids = await store.listDids();
    expect(dids).toContain('did:key:home-server');
  });

  it('delete removes from storage and index', async () => {
    const { storage } = createMockStorage();
    const store = new AsyncStorageSoulManifestStore(storage);
    await store.save(buildTestManifest());
    await store.delete('did:key:home-server');
    expect(await store.load('did:key:home-server')).toBeNull();
    expect(await store.listDids()).toHaveLength(0);
  });

  it('returns null for unknown DID', async () => {
    const { storage } = createMockStorage();
    const store = new AsyncStorageSoulManifestStore(storage);
    expect(await store.load('did:key:nope')).toBeNull();
  });

  it('does not duplicate DIDs in index on re-save', async () => {
    const { storage } = createMockStorage();
    const store = new AsyncStorageSoulManifestStore(storage);
    await store.save(buildTestManifest());
    await store.save(buildTestManifest()); // save again
    const dids = await store.listDids();
    expect(dids.filter(d => d === 'did:key:home-server')).toHaveLength(1);
  });
});

// ============================================================
// 4. HeadlineSyncClient
// ============================================================

describe('HeadlineSyncClient', () => {
  function makeHeadline(budDid: string, timestamp?: number): Headline {
    return {
      budDid,
      summary: `${budDid} is doing fine`,
      vitalitySnapshot: { energy: 0.7, rapport: 0.5 },
      itemCount: 3,
      timestamp: timestamp ?? Date.now(),
    };
  }

  it('posts and retrieves headlines', () => {
    const client = new HeadlineSyncClient();
    client.postHeadline(makeHeadline('bud-1'));
    client.postHeadline(makeHeadline('bud-2'));
    expect(client.latestHeadlines()).toHaveLength(2);
  });

  it('returns latest per bud (deduplicates)', () => {
    const client = new HeadlineSyncClient();
    client.postHeadline(makeHeadline('bud-1', 100));
    client.postHeadline(makeHeadline('bud-1', 200));
    const headlines = client.latestHeadlines();
    expect(headlines).toHaveLength(1);
    expect(headlines[0].timestamp).toBe(200);
  });

  it('sorts by timestamp newest first', () => {
    const client = new HeadlineSyncClient();
    client.postHeadline(makeHeadline('bud-1', 100));
    client.postHeadline(makeHeadline('bud-2', 300));
    client.postHeadline(makeHeadline('bud-3', 200));
    const headlines = client.latestHeadlines();
    expect(headlines[0].budDid).toBe('bud-2');
    expect(headlines[2].budDid).toBe('bud-1');
  });

  it('headlineFor returns specific bud', () => {
    const client = new HeadlineSyncClient();
    client.postHeadline(makeHeadline('bud-1'));
    expect(client.headlineFor('bud-1')).not.toBeNull();
    expect(client.headlineFor('bud-99')).toBeNull();
  });

  it('notifies listeners on post', () => {
    const client = new HeadlineSyncClient();
    const received: Headline[] = [];
    client.onHeadlineReceived(h => received.push(h));
    client.postHeadline(makeHeadline('bud-1'));
    expect(received).toHaveLength(1);
    expect(received[0].budDid).toBe('bud-1');
  });

  it('notifies listeners on receive', () => {
    const client = new HeadlineSyncClient();
    const received: Headline[] = [];
    client.onHeadlineReceived(h => received.push(h));
    client.receiveHeadline(makeHeadline('remote-bud'));
    expect(received).toHaveLength(1);
    expect(received[0].budDid).toBe('remote-bud');
  });

  it('unsubscribe stops notifications', () => {
    const client = new HeadlineSyncClient();
    const received: Headline[] = [];
    const unsub = client.onHeadlineReceived(h => received.push(h));
    client.postHeadline(makeHeadline('bud-1'));
    unsub();
    client.postHeadline(makeHeadline('bud-2'));
    expect(received).toHaveLength(1);
  });

  it('clear removes all headlines', () => {
    const client = new HeadlineSyncClient();
    client.postHeadline(makeHeadline('bud-1'));
    client.clear();
    expect(client.latestHeadlines()).toHaveLength(0);
  });
});

// ============================================================
// 5. CompanionEngine soul wiring (unit-level)
// ============================================================

describe('CompanionEngine soul wiring', () => {
  // CompanionEngine requires a RoomEngine and inference client.
  // We verify the constructor accepts the new opts and basic properties work.

  // Minimal mock for RoomEngine
  function mockRoomEngine(): any {
    const listeners: ((event: any) => void)[] = [];
    return {
      roomId: 'nexus',
      state: {
        roomId: 'nexus',
        name: 'The Nexus',
        description: 'A hub.',
        zone: 'foundation',
        entities: {},
        objects: {},
        exits: {},
        hints: [],
        properties: {},
      },
      send: jest.fn().mockResolvedValue({ type: 'ok', snapshot: null }),
      onEvent: jest.fn((listener: any) => {
        listeners.push(listener);
        return () => { /* unsub */ };
      }),
      shutdown: jest.fn(),
    };
  }

  function mockInferenceClient(): any {
    return {
      complete: jest.fn().mockResolvedValue({ content: 'Hello!', promptTokens: 10, completionTokens: 5 }),
    };
  }

  // We need to import CompanionEngine dynamically to avoid side effects
  let CompanionEngine: any;
  beforeAll(async () => {
    const mod = await import('../../src/engine/agent/CompanionEngine');
    CompanionEngine = mod.CompanionEngine;
  });

  it('constructs without soul manifest (backward compat)', () => {
    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null);
    expect(engine.state).toBe('idle');
    expect(engine.getSoulManifest()).toBeNull();
  });

  it('constructs with soul manifest', () => {
    const manifest = buildTestManifest();
    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null, { soulManifest: manifest });
    expect(engine.getSoulManifest()).not.toBeNull();
    expect(engine.getSoulManifest()!.did).toBe('did:key:home-server');
  });

  it('loadSoul sets the manifest', async () => {
    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null);
    expect(engine.getSoulManifest()).toBeNull();
    await engine.loadSoul(buildTestManifest());
    expect(engine.getSoulManifest()).not.toBeNull();
  });

  it('loadSoul persists to store when provided', async () => {
    const store = new InMemorySoulManifestStore();
    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null, { soulManifestStore: store });
    await engine.loadSoul(buildTestManifest());
    const loaded = await store.load('did:key:home-server');
    expect(loaded).not.toBeNull();
  });

  it('restores a persisted forged soul manifest at boot (identity continuity)', async () => {
    // Simulate a prior session that forged + saved a real soul, then the app
    // restarted (fresh engine, no soulManifest passed in).
    const store = new InMemorySoulManifestStore();
    await store.save(buildTestManifest()); // did:key:homeServer (non-bootstrap)

    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null,
      { soulManifestStore: store });
    expect(engine.getSoulManifest()).toBeNull();
    await engine.start();
    // Came up on the persisted forged identity, not a bootstrap soul.
    expect(engine.getSoulManifest()).not.toBeNull();
    expect(engine.getSoulManifest()!.did).toBe('did:key:home-server');
    engine.shutdown();
  });

  it('prefers the persisted forged soul over a bootstrap manifest at boot', async () => {
    const store = new InMemorySoulManifestStore();
    await store.save(buildTestManifest()); // real forged soul
    const bootstrap = { ...buildTestManifest(), did: 'did:key:bootstrap-wyrd' };

    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null,
      { soulManifest: bootstrap, soulManifestStore: store });
    await engine.start();
    expect(engine.getSoulManifest()!.did).toBe('did:key:home-server');
    engine.shutdown();
  });

  it('stays on bootstrap when nothing has been forged yet', async () => {
    const store = new InMemorySoulManifestStore();
    const bootstrap = { ...buildTestManifest(), did: 'did:key:bootstrap-wyrd' };
    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null,
      { soulManifest: bootstrap, soulManifestStore: store });
    await engine.start();
    expect(engine.getSoulManifest()!.did).toBe('did:key:bootstrap-wyrd');
    engine.shutdown();
  });

  it('unloadSoul clears the manifest', async () => {
    const manifest = buildTestManifest();
    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null, { soulManifest: manifest });
    expect(engine.getSoulManifest()).not.toBeNull();
    engine.unloadSoul();
    expect(engine.getSoulManifest()).toBeNull();
  });

  it('starts and shuts down cleanly with soul manifest', async () => {
    const manifest = buildTestManifest();
    const engine = new CompanionEngine(testProfile, mockRoomEngine(), mockInferenceClient(), null, { soulManifest: manifest });
    await engine.start();
    expect(engine.state).toBe('idle');
    engine.shutdown();
  });
});
