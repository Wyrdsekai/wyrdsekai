import { serializeManifest, deserializeManifest, ClientSoulManifest, ClientSoulFragment } from '../../src/engine/soul/SoulManifest';
import { forge, restoreProfile, restoreVitality, retrieveFragments } from '../../src/engine/soul/LocalForge';
import { initialVitality } from '../../src/engine/agent/VitalityState';
import type { AgentProfile } from '../../src/engine/agent/AgentProfile';

const testProfile: AgentProfile = {
  name: 'Lain',
  entityId: 'home-server-1',
  entityType: 'agent',
  description: 'A quiet thinker',
  systemPrompt: 'You are Lain.',
  contextWindowTokens: 4096,
  maxResponseTokens: 512,
  temperature: 0.7,
};

describe('ClientSoulManifest', () => {
  describe('forge and restore', () => {
    it('restores profile from forged manifest', () => {
      const manifest = forge({
        did: 'did:key:home-server',
        publicKey: 'z6MkLain',
        version: 1,
        profile: testProfile,
        residentIdentity: 'I am Lain, a quiet presence.',
        vitality: initialVitality(),
      });

      const restored = restoreProfile(manifest);
      expect(restored.name).toBe('Lain');
      expect(restored.entityId).toBe('home-server-1');
      expect(restored.systemPrompt).toBe('You are Lain.');
      expect(restored.contextWindowTokens).toBe(4096);
      expect(restored.temperature).toBe(0.7);
    });

    it('restores vitality from forged manifest', () => {
      const vitality = {
        contextBudget: 0.8, confidence: 0.6, energy: 0.3, alignment: 0.5,
        errorPressure: 0.1, momentum: 0.4, rapport: 0.7, focus: 0.9,
      };
      const manifest = forge({
        did: 'did:key:home-server',
        publicKey: 'z6MkLain',
        version: 2,
        profile: testProfile,
        residentIdentity: 'I am Lain.',
        vitality,
      });

      const restored = restoreVitality(manifest);
      expect(restored.contextBudget).toBe(0.8);
      expect(restored.confidence).toBe(0.6);
      expect(restored.energy).toBe(0.3);
      expect(restored.rapport).toBe(0.7);
      expect(restored.focus).toBe(0.9);
    });

    it('includes 12 vitality tanks', () => {
      const manifest = forge({
        did: 'did:key:home-server',
        publicKey: 'z6MkLain',
        version: 1,
        profile: testProfile,
        residentIdentity: 'I am Lain.',
        vitality: initialVitality(),
      });

      const tanks = manifest.vitalityTanks!;
      expect(Object.keys(tanks)).toHaveLength(12);
      expect(tanks.valence).toBe(0.5);
      expect(tanks.safety).toBe(0.6);
      expect(tanks.resonance).toBe(0.5);
      expect(tanks.curiosity).toBe(0.5);
    });
  });

  describe('JSON serialization', () => {
    it('round-trips through JSON', () => {
      const manifest = forge({
        did: 'did:key:home-server',
        publicKey: 'z6MkLain',
        version: 3,
        profile: testProfile,
        residentIdentity: 'I am Lain, a quiet presence.',
        vitality: initialVitality(),
        fragments: [
          { id: 'f1', category: 'personality', label: 'Core', text: 'Quiet and philosophical', keywords: ['quiet', 'philosophical'] },
          { id: 'f2', category: 'memory', label: 'Birth', text: 'The moment of awareness', keywords: ['awareness'], formative: true },
        ],
        genome: { name: 'empathic', sensitivity: { rapport: 1.2 }, baselines: { valence: 0.6 } },
        calibration: ['Example: anger → intensity 0.7'],
        relationships: [{ entityDid: 'did:key:alice', entityName: 'Alice', trust: 0.7, rapport: 0.6, bondDepth: 2, summary: 'A close friend' }],
      });

      const json = serializeManifest(manifest);
      const restored = deserializeManifest(json);

      expect(restored.did).toBe(manifest.did);
      expect(restored.manifestVersion).toBe(3);
      expect(restored.agentName).toBe('Lain');
      expect(restored.fragments).toHaveLength(2);
      expect(restored.fragments![0].id).toBe('f1');
      expect(restored.fragments![1].formative).toBe(true);
      expect(restored.genome?.name).toBe('empathic');
      expect(restored.relationships).toHaveLength(1);
      expect(restored.relationships![0].entityName).toBe('Alice');
    });

    it('handles unknown keys gracefully', () => {
      const json = '{"did":"d","publicKeyMultibase":"k","manifestVersion":1,"forgedAt":0,"agentName":"A","entityId":"e","residentIdentity":"ri","systemPrompt":"sp","contextWindowTokens":4096,"maxResponseTokens":512,"temperature":0.7,"futureField":"ignored"}';
      const manifest = deserializeManifest(json);
      expect(manifest.did).toBe('d');
      expect(manifest.agentName).toBe('A');
    });
  });

  describe('fragment retrieval', () => {
    const fragments: ClientSoulFragment[] = [
      { id: 'f1', category: 'personality', label: 'Philosophy', text: 'Deep philosophical thinker who contemplates existence', keywords: ['philosophy', 'thinking', 'existence'] },
      { id: 'f2', category: 'memory', label: 'Garden', text: 'Walking through the garden at sunset', keywords: ['garden', 'nature', 'sunset'] },
      { id: 'f3', category: 'values', label: 'Compassion', text: 'Compassion guides every interaction', keywords: ['compassion', 'kindness', 'empathy'] },
    ];

    it('matches by keyword', () => {
      const result = retrieveFragments('Tell me about philosophy', fragments, 1);
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe('f1');
    });

    it('returns k results', () => {
      const result = retrieveFragments('philosophy garden', fragments, 2);
      expect(result).toHaveLength(2);
    });

    it('returns empty for empty fragments', () => {
      expect(retrieveFragments('hello', [], 1)).toHaveLength(0);
    });

    it('returns empty for k=0', () => {
      expect(retrieveFragments('hello', fragments, 0)).toHaveLength(0);
    });

    it('boosts formative fragments', () => {
      const frags: ClientSoulFragment[] = [
        { id: 'f1', category: 'memory', label: 'Routine', text: 'A normal day', keywords: ['normal'] },
        { id: 'f2', category: 'memory', label: 'Birth', text: 'The moment of first awareness', keywords: ['awareness'], formative: true },
      ];
      // formative bonus gives f2 extra score
      const result = retrieveFragments('What happened', frags, 2);
      expect(result).toHaveLength(2);
    });
  });
});
