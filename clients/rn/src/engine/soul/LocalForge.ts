/**
 * Local Forge — serialize/restore agent state to/from soul manifests on phones.
 *
 * The Forge crystallizes runtime agent state into portable form.
 * On phones: stateless serializer (no event sourcing).
 * On server: ForgeActor (event-sourced, persistent).
 */

import type {AgentProfile} from '../agent/AgentProfile';
import type {VitalityState} from '../agent/VitalityState';
import type {
  ClientSoulManifest,
  ClientSoulFragment,
  ClientGenome,
  ClientRelationship,
} from './SoulManifest';

/**
 * Forge a soul manifest from current runtime state.
 */
export function forge(opts: {
  did: string;
  publicKey: string;
  version: number;
  profile: AgentProfile;
  residentIdentity: string;
  vitality: VitalityState;
  fragments?: ClientSoulFragment[];
  genome?: ClientGenome;
  calibration?: string[];
  relationships?: ClientRelationship[];
  retrievalK?: number;
}): ClientSoulManifest {
  return {
    did: opts.did,
    publicKeyMultibase: opts.publicKey,
    manifestVersion: opts.version,
    forgedAt: Date.now(),
    agentName: opts.profile.name,
    entityId: opts.profile.entityId,
    residentIdentity: opts.residentIdentity,
    systemPrompt: opts.profile.systemPrompt,
    contextWindowTokens: opts.profile.contextWindowTokens,
    maxResponseTokens: opts.profile.maxResponseTokens,
    temperature: opts.profile.temperature,
    genome: opts.genome,
    mirrorCalibration: opts.calibration ?? [],
    fragments: opts.fragments ?? [],
    retrievalK: opts.retrievalK ?? 1,
    vitalityTanks: vitalityToTanks(opts.vitality),
    relationships: opts.relationships ?? [],
  };
}

/**
 * Restore an AgentProfile from a soul manifest.
 */
export function restoreProfile(manifest: ClientSoulManifest): AgentProfile {
  return {
    name: manifest.agentName,
    entityId: manifest.entityId,
    entityType: 'agent',
    description: '',
    systemPrompt: manifest.systemPrompt,
    contextWindowTokens: manifest.contextWindowTokens,
    maxResponseTokens: manifest.maxResponseTokens,
    temperature: manifest.temperature,
  };
}

/**
 * Restore vitality state from a soul manifest (8 runtime tanks from 12 stored).
 */
export function restoreVitality(manifest: ClientSoulManifest): VitalityState {
  const t = manifest.vitalityTanks ?? {};
  return {
    contextBudget: t.contextBudget ?? 0.5,
    confidence: t.confidence ?? 0.5,
    energy: t.energy ?? 1.0,
    alignment: t.alignment ?? 0.5,
    errorPressure: t.errorPressure ?? 0.0,
    momentum: t.momentum ?? 0.4,
    rapport: t.rapport ?? 0.5,
    focus: t.focus ?? 0.5,
  };
}

/**
 * Find the best matching fragments for a given input (keyword match for phones).
 * On phones without embedding models, simple keyword overlap is used.
 */
export function retrieveFragments(
  input: string,
  fragments: ClientSoulFragment[],
  k: number = 1,
): ClientSoulFragment[] {
  if (fragments.length === 0 || k <= 0) return [];

  const inputWords = new Set(
    input
      .toLowerCase()
      .split(/\W+/)
      .filter(w => w.length > 3),
  );
  if (inputWords.size === 0) return fragments.slice(0, k);

  const scored = fragments.map(fragment => {
    const textWords = new Set(fragment.text.toLowerCase().split(/\W+/));
    const keywordOverlap = (fragment.keywords ?? []).filter(kw =>
      inputWords.has(kw.toLowerCase()),
    ).length;
    const textOverlap = [...inputWords].filter(w => textWords.has(w)).length;
    const formativeBonus = fragment.formative ? 2 : 0;
    const score = keywordOverlap * 3 + textOverlap + formativeBonus;
    return {fragment, score};
  });

  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, k).map(s => s.fragment);
}

/** Convert 8-tank VitalityState to 12-tank map. */
function vitalityToTanks(v: VitalityState): Record<string, number> {
  return {
    contextBudget: v.contextBudget,
    confidence: v.confidence,
    energy: v.energy,
    alignment: v.alignment,
    errorPressure: v.errorPressure,
    momentum: v.momentum,
    rapport: v.rapport,
    focus: v.focus,
    valence: 0.5,
    safety: 0.6,
    resonance: 0.5,
    curiosity: 0.5,
  };
}
