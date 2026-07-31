/**
 * Lightweight behavioral fingerprint for phone-side extraction.
 * TypeScript port of KMP's PhoneFingerprint.kt.
 *
 * Heuristic fields are filled by HeuristicExtractor (free, instant).
 * LLM-derived fields (topicAffinities, stylisticMarkers, emotionalPatterns)
 * are filled by a future Wave 2 LLM pass.
 */

export interface PhoneFingerprint {
  /** Action type frequencies, e.g. "say" -> 0.72, "move" -> 0.15. */
  actionDistribution: Record<string, number>;
  /** Average word count of agent responses. */
  averageResponseLength: number;
  /** Average response latency in seconds. */
  averageLatency: number;
  /** Top non-stopword keywords from conversation. */
  topicKeywords: string[];
  /** Vitality tank trends: tank name -> total delta over observation window. */
  vitalityTrends: Record<string, number>;
  // --- Filled by LLM pass (Wave 2): ---
  /** Topic affinities: topic -> weight 0-1. */
  topicAffinities: Record<string, number>;
  /** Characteristic phrases and speech patterns. */
  stylisticMarkers: string[];
  /** Emotional responsiveness: emotion -> responsiveness 0-1. */
  emotionalPatterns: Record<string, number>;
}

/** Create an empty fingerprint with no behavioral data. */
export function emptyFingerprint(): PhoneFingerprint {
  return {
    actionDistribution: {},
    averageResponseLength: 0,
    averageLatency: 0,
    topicKeywords: [],
    vitalityTrends: {},
    topicAffinities: {},
    stylisticMarkers: [],
    emotionalPatterns: {},
  };
}

/**
 * Weighted merge of two fingerprints for sleep-cycle consolidation.
 * new = existing * (1 - alpha) + fresh * alpha.
 *
 * @param existing The historical fingerprint
 * @param fresh    The newly extracted fingerprint
 * @param alpha    Weight for fresh data (default 0.3 = 30% new, 70% historical)
 */
export function mergeFingerprints(
  existing: PhoneFingerprint,
  fresh: PhoneFingerprint,
  alpha = 0.3,
): PhoneFingerprint {
  return {
    actionDistribution: mergeMaps(existing.actionDistribution, fresh.actionDistribution, alpha),
    averageResponseLength:
      existing.averageResponseLength * (1 - alpha) + fresh.averageResponseLength * alpha,
    averageLatency: existing.averageLatency * (1 - alpha) + fresh.averageLatency * alpha,
    topicKeywords: mergeKeywords(existing.topicKeywords, fresh.topicKeywords),
    vitalityTrends: mergeMaps(existing.vitalityTrends, fresh.vitalityTrends, alpha),
    topicAffinities: mergeMaps(existing.topicAffinities, fresh.topicAffinities, alpha),
    stylisticMarkers: mergeKeywords(existing.stylisticMarkers, fresh.stylisticMarkers),
    emotionalPatterns: mergeMaps(existing.emotionalPatterns, fresh.emotionalPatterns, alpha),
  };
}

function mergeMaps(
  a: Record<string, number>,
  b: Record<string, number>,
  alpha: number,
): Record<string, number> {
  const aKeys = Object.keys(a);
  const bKeys = Object.keys(b);
  if (aKeys.length === 0) return { ...b };
  if (bKeys.length === 0) return { ...a };
  const result: Record<string, number> = { ...a };
  for (const key of bKeys) {
    if (key in result) {
      result[key] = result[key] * (1 - alpha) + b[key] * alpha;
    } else {
      result[key] = b[key];
    }
  }
  return result;
}

function mergeKeywords(a: string[], b: string[]): string[] {
  if (b.length === 0) return [...a];
  if (a.length === 0) return [...b];
  const seen = new Set(a);
  const result = [...a];
  for (const item of b) {
    if (!seen.has(item)) {
      seen.add(item);
      result.push(item);
    }
  }
  return result;
}
