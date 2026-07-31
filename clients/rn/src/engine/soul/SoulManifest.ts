/**
 * Client-side soul manifest — portable agent identity for phones/web.
 *
 * Mirrors the server's SoulManifest (4-layer model) adapted for
 * constrained devices:
 * - Layer D: Identity (DID, public key, manifest version)
 * - Layer A: Profile + resident identity (MEDIUM soul text, ~69 tokens)
 * - Layer A.5: Genome (12-tank sensitivity/coupling/decay)
 * - Layer B: Memory fragments (for retrieval — k=1 on phone, k=3 on 7B+)
 * - Layer C: Vitality snapshot (12 tanks)
 *
 * Phone constraints (validated by experiments):
 * - Prompt injection only (no steering vectors — Exp 16)
 * - Retrieval k=1 (single most relevant fragment)
 * - Genome computation is pure arithmetic
 * - MirrorResonance calibration examples included
 */

export interface ClientSoulManifest {
  // Layer D: Identity
  did: string;
  publicKeyMultibase: string;
  manifestVersion: number;
  forgedAt: number; // epoch millis

  // Layer A: Profile
  agentName: string;
  entityId: string;
  residentIdentity: string; // MEDIUM soul text (~69 tokens)
  systemPrompt: string;
  contextWindowTokens: number;
  maxResponseTokens: number;
  temperature: number;

  // Layer A.5: Genome
  genome?: ClientGenome;
  mirrorCalibration?: string[];

  // Layer B: Memory fragments
  fragments?: ClientSoulFragment[];
  retrievalK?: number; // k=1 for phone, k=3 for 7B+

  // Layer C: Vitality (12 tanks)
  vitalityTanks?: Record<string, number>;

  // Relationships
  relationships?: ClientRelationship[];
}

export interface ClientSoulFragment {
  id: string;
  category: string; // personality, memory, values, style, relationships
  label: string;
  text: string;
  keywords?: string[];
  formative?: boolean;
}

export interface ClientGenome {
  name: string;
  sensitivity?: Record<string, number>;
  coupling?: Record<string, number>;
  baselines?: Record<string, number>;
  decayRates?: Record<string, number>;
}

export interface ClientRelationship {
  entityDid: string;
  entityName: string;
  trust?: number;
  rapport?: number;
  bondDepth?: number;
  summary?: string;
}

/**
 * Serialize a soul manifest to JSON string.
 */
export function serializeManifest(manifest: ClientSoulManifest): string {
  return JSON.stringify(manifest);
}

/**
 * Deserialize a soul manifest from JSON string.
 * Ignores unknown keys (forward-compatible).
 */
export function deserializeManifest(json: string): ClientSoulManifest {
  return JSON.parse(json) as ClientSoulManifest;
}
