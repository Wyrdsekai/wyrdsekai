/**
 * Pass 1 heuristic behavioral extraction — free, instant, no LLM required.
 * TypeScript port of KMP's HeuristicExtractor.kt.
 *
 * Produces a rough PhoneFingerprint from event statistics:
 * action distribution, response timing, topic keywords, vitality trends.
 */

import type { WorldEvent } from '../events/WorldEvent';
import type { VitalityState } from '../agent/VitalityState';
import type { PhoneFingerprint } from './PhoneFingerprint';
import { TANK_NAMES } from '../agent/VitalityState';

/** Compact English stopword set (~100 words). */
const STOPWORDS = new Set([
  'the', 'a', 'an', 'is', 'are', 'was', 'were', 'be', 'been',
  'being', 'have', 'has', 'had', 'do', 'does', 'did', 'will', 'would', 'could', 'should',
  'may', 'might', 'shall', 'can', 'to', 'of', 'in', 'for', 'on', 'with', 'at', 'by',
  'from', 'as', 'into', 'through', 'during', 'before', 'after', 'above', 'below',
  'between', 'out', 'off', 'over', 'under', 'again', 'further', 'then', 'once',
  'here', 'there', 'when', 'where', 'why', 'how', 'all', 'each', 'every', 'both',
  'few', 'more', 'most', 'other', 'some', 'such', 'no', 'nor', 'not', 'only', 'own',
  'same', 'so', 'than', 'too', 'very', 'just', 'because', 'but', 'and', 'or', 'if',
  'while', 'about', 'up', 'down', 'it', 'its', 'he', 'she', 'they', 'them', 'his',
  'her', 'their', 'this', 'that', 'these', 'those', 'what', 'which', 'who', 'whom',
  'i', 'me', 'my', 'we', 'us', 'our', 'you', 'your',
]);

/**
 * Extract a heuristic fingerprint from agent events and vitality history.
 *
 * @param agentEntityId  The agent's entity ID (to distinguish agent vs others in Said events)
 * @param events         WorldEvents to analyze (typically since last sleep)
 * @param vitalityHistory Vitality snapshots over time (sampled periodically)
 */
export function extractHeuristic(
  agentEntityId: string,
  events: WorldEvent[],
  vitalityHistory: VitalityState[] = [],
): PhoneFingerprint {
  return {
    actionDistribution: computeActionDistribution(events),
    averageResponseLength: computeAverageResponseLength(agentEntityId, events),
    averageLatency: computeAverageLatency(agentEntityId, events),
    topicKeywords: extractTopicKeywords(events, 20),
    vitalityTrends: computeVitalityTrends(vitalityHistory),
    // LLM-derived fields left empty for Wave 2:
    topicAffinities: {},
    stylisticMarkers: [],
    emotionalPatterns: {},
  };
}

/**
 * Count WorldEvent types and return normalized distribution.
 * Maps event types to semantic action names matching the server convention.
 */
export function computeActionDistribution(events: WorldEvent[]): Record<string, number> {
  if (events.length === 0) return {};

  const counts: Record<string, number> = {};
  for (const event of events) {
    let actionType: string;
    switch (event.type) {
      case 'said': actionType = 'say'; break;
      case 'entity_entered': actionType = 'move'; break;
      case 'entity_left': actionType = 'leave'; break;
      case 'object_used': actionType = 'use'; break;
      case 'object_taken': actionType = 'take'; break;
      case 'object_dropped': actionType = 'drop'; break;
      case 'whispered': actionType = 'whisper'; break;
      default: actionType = 'other'; break;
    }
    counts[actionType] = (counts[actionType] ?? 0) + 1;
  }

  const total = Object.values(counts).reduce((sum, c) => sum + c, 0);
  if (total === 0) return {};

  const dist: Record<string, number> = {};
  for (const [key, count] of Object.entries(counts)) {
    dist[key] = count / total;
  }
  return dist;
}

/**
 * Average response latency in seconds.
 * Walks events chronologically; for each non-agent Said event, finds
 * the next agent Said event and measures the time delta.
 */
export function computeAverageLatency(agentEntityId: string, events: WorldEvent[]): number {
  const gaps: number[] = [];
  let lastOtherTimestamp: number | null = null;

  for (const event of events) {
    if (event.type === 'said') {
      if (event.entityId === agentEntityId) {
        if (lastOtherTimestamp !== null) {
          const deltaMs = event.timestamp - lastOtherTimestamp;
          if (deltaMs > 0) {
            gaps.push(deltaMs / 1000);
          }
          lastOtherTimestamp = null;
        }
      } else {
        lastOtherTimestamp = event.timestamp;
      }
    }
  }

  if (gaps.length === 0) return 0;
  return gaps.reduce((sum, g) => sum + g, 0) / gaps.length;
}

/**
 * Average word count in agent's Said events.
 */
export function computeAverageResponseLength(agentEntityId: string, events: WorldEvent[]): number {
  const lengths: number[] = [];
  for (const event of events) {
    if (event.type === 'said' && event.entityId === agentEntityId) {
      lengths.push(event.text.split(/\s+/).length);
    }
  }
  if (lengths.length === 0) return 0;
  return lengths.reduce((sum, l) => sum + l, 0) / lengths.length;
}

/**
 * Extract top-K non-stopword keywords from all Said events.
 * Tokenizes on whitespace + punctuation, lowercases, filters stopwords and short tokens.
 */
export function extractTopicKeywords(events: WorldEvent[], topK: number): string[] {
  const freq = new Map<string, number>();

  for (const event of events) {
    if (event.type === 'said') {
      const tokens = event.text
        .toLowerCase()
        .split(/[\s,.!?;:"'()\[\]{}<>]+/)
        .filter(t => t.length >= 4 && !STOPWORDS.has(t));

      for (const token of tokens) {
        freq.set(token, (freq.get(token) ?? 0) + 1);
      }
    }
  }

  return Array.from(freq.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, topK)
    .map(([word]) => word);
}

/**
 * Compute vitality trends: delta between first and last snapshot for each tank.
 * Returns empty record if fewer than 2 snapshots.
 */
export function computeVitalityTrends(history: VitalityState[]): Record<string, number> {
  if (history.length < 2) return {};

  const first = history[0];
  const last = history[history.length - 1];

  const trends: Record<string, number> = {};
  for (const tank of TANK_NAMES) {
    trends[tank] = last[tank] - first[tank];
  }
  return trends;
}
