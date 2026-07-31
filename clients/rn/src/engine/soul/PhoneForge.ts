/**
 * Wave 4: PhoneForge orchestrator — the centerpiece of phone-side soul evolution.
 *
 * Replaces the previous sleep snapshot (re-forge manifest with same fragments)
 * with real soul evolution by orchestrating:
 * 1. HeuristicExtractor (Wave 1) — instant, always
 * 2. LlmExtractor (Wave 2) — one inference call, if available
 * 3. FragmentEvolver + IdentityEvolver (Wave 3) — fragment generation/evolution
 *
 * The result is a new manifest with evolved fragments, tuned genome, and
 * optionally a regenerated identity — the companion becomes more itself
 * with each sleep cycle.
 *
 * TypeScript port of KMP's PhoneForge.kt.
 * Phone port of the server's ForgeActor sleep consolidation pipeline.
 */

import type { ClientSoulManifest, ClientSoulFragment, ClientGenome } from './SoulManifest';
import type { PhoneFingerprint } from './PhoneFingerprint';
import type { WorldEvent } from '../events/WorldEvent';
import type { VitalityState } from '../agent/VitalityState';
import type { ChatMessage, ChatResponse, CompletionOptions } from '../../inference/types';
import { extractHeuristic } from './HeuristicExtractor';
import { extractWithLlm } from './LlmExtractor';
import { mergeFingerprints } from './PhoneFingerprint';
import { generateInitialFragments, evolveFragments } from './FragmentEvolver';
import { shouldRegenerateIdentity, regenerateIdentity } from './IdentityEvolver';
import { forge, restoreProfile } from './LocalForge';

/**
 * Input to a full Forge cycle during phone sleep.
 */
export interface PhoneForgeInput {
  /** Current soul manifest. */
  manifest: ClientSoulManifest;
  /** WorldEvents accumulated since last sleep. */
  events: WorldEvent[];
  /** Vitality snapshots over time (sampled periodically). */
  vitalityHistory: VitalityState[];
  /** Current vitality state at sleep time. */
  vitality: VitalityState;
  /** The agent's entity ID. */
  agentEntityId: string;
  /** Inference function, or null for heuristic-only mode. */
  infer: ((messages: ChatMessage[], options: CompletionOptions) => Promise<ChatResponse>) | null;
  /** Number of sleep cycles completed so far. */
  sleepCount: number;
  /** Fingerprint from the previous sleep, for merging. */
  previousFingerprint: PhoneFingerprint | null;
}

/**
 * Result of a full PhoneForge cycle.
 */
export interface PhoneForgeResult {
  /** The forged manifest with updated fragments, genome, vitality. */
  newManifest: ClientSoulManifest;
  /** The merged behavioral fingerprint (saved for next sleep). */
  fingerprint: PhoneFingerprint;
  /** Energy points recovered during sleep. */
  energyRecovery: number;
  /** Focus points recovered during sleep. */
  focusRecovery: number;
  /** Overall sleep quality 0.0-1.0. */
  sleepQuality: number;
  /** "heuristic" or "llm" — which level of extraction succeeded. */
  extractionLevel: string;
  /** Number of fragments that were created or modified. */
  fragmentsChanged: number;
}

/** Minimum events required before attempting LLM extraction. */
const LLM_MIN_EVENTS = 10;
/** Weight for fresh fingerprint data when merging with historical. */
const MERGE_ALPHA = 0.3;

/**
 * Run a full Forge cycle during phone sleep.
 *
 * Pipeline:
 * 1. Heuristic extraction (instant, always)
 * 2. LLM extraction (one call, if client available and events >= 10)
 * 3. Merge with previous fingerprint (weighted average)
 * 4. Generate or evolve fragments
 * 5. Optionally regenerate identity (after 5+ sleeps, bootstrap DID)
 * 6. Tune genome based on observed vitality trends
 * 7. Compute sleep quality + recovery
 * 8. Forge new manifest
 */
export async function forgeFromSleep(input: PhoneForgeInput): Promise<PhoneForgeResult> {
  const agentName = input.manifest.agentName;

  // Step 1: Heuristic extraction (always — free, instant)
  let fingerprint = extractHeuristic(
    input.agentEntityId,
    input.events,
    input.vitalityHistory,
  );
  let extractionLevel = 'heuristic';

  // Step 2: LLM extraction (if available and enough events)
  if (input.infer !== null && input.events.length >= LLM_MIN_EVENTS) {
    fingerprint = await extractWithLlm(
      input.infer,
      fingerprint,
      input.events,
      agentName,
    );
    // If LLM enriched the fingerprint with any new data, mark as llm level
    if (
      Object.keys(fingerprint.topicAffinities).length > 0 ||
      fingerprint.stylisticMarkers.length > 0
    ) {
      extractionLevel = 'llm';
    }
  }

  // Step 3: Merge with previous fingerprint (weighted average preserves history)
  if (input.previousFingerprint !== null) {
    fingerprint = mergeFingerprints(input.previousFingerprint, fingerprint, MERGE_ALPHA);
  }

  // Step 4: Generate or evolve fragments
  const existingFragments = input.manifest.fragments ?? [];
  const isFirstRealSleep =
    existingFragments.length === 0 ||
    existingFragments.every(f => f.id.startsWith('bootstrap-'));
  const newFragments = isFirstRealSleep
    ? generateInitialFragments(
        fingerprint,
        input.manifest.residentIdentity,
        agentName,
      )
    : evolveFragments(
        existingFragments,
        fingerprint,
        agentName,
        input.sleepCount,
      );
  const fragmentsChanged = countChanges(existingFragments, newFragments);

  // Step 5: Optionally regenerate identity (bootstrap DID, 5+ sleeps, 3+ fragments)
  let residentIdentity = input.manifest.residentIdentity;
  if (input.infer !== null) {
    const tempManifest: ClientSoulManifest = {
      ...input.manifest,
      fragments: newFragments,
    };
    if (shouldRegenerateIdentity(tempManifest, input.sleepCount)) {
      const newIdentity = await regenerateIdentity(input.infer, tempManifest);
      if (newIdentity !== null) {
        residentIdentity = newIdentity;
      }
    }
  }

  // Step 6: Tune genome based on observed vitality trends
  const genome = tuneGenome(input.manifest.genome, fingerprint);

  // Step 7: Sleep quality + recovery
  const sleepQuality = computeSleepQuality(input.events.length, input.vitality.energy);
  const energyRecovery = 0.2 * sleepQuality;
  const focusRecovery = 0.15 * sleepQuality;

  // Step 8: Forge new manifest with evolved soul
  const profile = restoreProfile(input.manifest);
  const newManifest = forge({
    did: input.manifest.did,
    publicKey: input.manifest.publicKeyMultibase,
    version: input.manifest.manifestVersion + 1,
    profile,
    residentIdentity,
    vitality: input.vitality,
    fragments: newFragments,
    genome,
    calibration: input.manifest.mirrorCalibration ?? [],
    relationships: input.manifest.relationships ?? [],
    retrievalK: input.manifest.retrievalK ?? 1,
  });

  return {
    newManifest,
    fingerprint,
    energyRecovery,
    focusRecovery,
    sleepQuality,
    extractionLevel,
    fragmentsChanged,
  };
}

/**
 * Tune genome based on observed vitality trends.
 *
 * Adjusts sensitivity toward observed patterns, clamped to +/- 0.05 per sleep.
 * If a tank showed large movement (|trend| > 0.1), increase sensitivity slightly;
 * if it barely moved, decrease slightly.
 */
export function tuneGenome(
  genome: ClientGenome | undefined,
  fingerprint: PhoneFingerprint,
): ClientGenome | undefined {
  if (!genome) return undefined;
  const trends = fingerprint.vitalityTrends;
  if (Object.keys(trends).length === 0) return genome;

  const newSensitivity: Record<string, number> = { ...(genome.sensitivity ?? {}) };
  for (const [tank, trend] of Object.entries(trends)) {
    const current = newSensitivity[tank] ?? 1.0;
    const adjustment = Math.abs(trend) > 0.1 ? 0.05 : -0.02;
    newSensitivity[tank] = Math.max(0.1, Math.min(3.0, current + adjustment));
  }

  return { ...genome, sensitivity: newSensitivity };
}

/**
 * Compute sleep quality from event count and current energy level.
 *
 * Quality scales with:
 * - Fatigue bonus: how depleted energy is (more tired = better quality rest)
 * - Event factor: how much material the Forge has to work with (capped at 100 events)
 *
 * Returns 0.0-1.0.
 */
export function computeSleepQuality(eventCount: number, energy: number): number {
  const eventFactor = Math.min(eventCount / 100, 1.0);
  const fatigueBonus = Math.max(0, Math.min(1, 1.0 - energy));
  return Math.max(0, Math.min(1, 0.3 + 0.5 * fatigueBonus + 0.2 * eventFactor));
}

/**
 * Count how many fragments were created or had their text changed.
 */
function countChanges(
  oldFragments: ClientSoulFragment[],
  newFragments: ClientSoulFragment[],
): number {
  const oldMap = new Map<string, ClientSoulFragment>();
  for (const f of oldFragments) {
    oldMap.set(f.id, f);
  }
  let changed = 0;
  for (const fragment of newFragments) {
    const existing = oldMap.get(fragment.id);
    if (!existing || existing.text !== fragment.text) {
      changed++;
    }
  }
  return changed;
}
