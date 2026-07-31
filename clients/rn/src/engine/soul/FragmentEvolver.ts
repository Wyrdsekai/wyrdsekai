/**
 * Wave 3: Fragment Evolver — generates and evolves soul fragments from fingerprint data.
 *
 * Converts raw behavioral statistics (PhoneFingerprint) into narrative soul fragments
 * that the LLM reads as part of the prompt. The text must read as a description of
 * a person, not a data dump — because it becomes the companion's self-knowledge.
 *
 * Two entry points:
 * - generateInitialFragments: First sleep with real data. Produces 5-7 fragments.
 * - evolveFragments: Subsequent sleeps. Updates non-formative fragments, adds new ones.
 *
 * TypeScript port of KMP's FragmentEvolver.kt.
 * Mirrors server-side SoulFragmentExtractor patterns but operates on PhoneFingerprint
 * rather than BehavioralFingerprint + CompactedMemory.
 */

import type { ClientSoulFragment } from './SoulManifest';
import type { PhoneFingerprint } from './PhoneFingerprint';

/** Stopwords shared with HeuristicExtractor for keyword extraction from text. */
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

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Generate initial fragments from the first real extraction.
 * Called on the first sleep where we have conversation data.
 * Produces 5-7 fragments covering core personality categories.
 *
 * @param fingerprint      Accumulated PhoneFingerprint (heuristic + LLM-enriched)
 * @param residentIdentity MEDIUM soul text (~69 tokens) — becomes the identity-core fragment
 * @param agentName        Display name of the companion
 */
export function generateInitialFragments(
  fingerprint: PhoneFingerprint,
  residentIdentity: string,
  agentName: string,
): ClientSoulFragment[] {
  const fragments: ClientSoulFragment[] = [];

  // 1. Identity core — always present, formative (never auto-modified)
  if (residentIdentity.trim().length > 0) {
    fragments.push({
      id: 'identity-core',
      category: 'personality',
      label: 'Core identity',
      text: residentIdentity,
      keywords: extractKeywords(residentIdentity),
      formative: true,
    });
  }

  // 2. Behavioral patterns from action distribution + topics
  const patternText = buildPatternText(fingerprint, agentName);
  if (patternText.length > 0) {
    fragments.push({
      id: 'pattern-behavioral',
      category: 'personality',
      label: 'Behavioral patterns',
      text: patternText,
      keywords: fingerprint.topicKeywords.slice(0, 10),
    });
  }

  // 3. Values from topic affinities
  const valuesText = buildValuesText(fingerprint, agentName);
  if (valuesText.length > 0) {
    fragments.push({
      id: 'values-core',
      category: 'values',
      label: 'Core values',
      text: valuesText,
      keywords: ['values', ...Object.keys(fingerprint.topicAffinities).slice(0, 5)],
    });
  }

  // 4. Communication style from stylistic markers + response length
  const styleText = buildStyleText(fingerprint, agentName);
  if (styleText.length > 0) {
    fragments.push({
      id: 'style-guide',
      category: 'style',
      label: 'Communication style',
      text: styleText,
      keywords: ['style', 'communication', ...fingerprint.stylisticMarkers.slice(0, 5)],
    });
  }

  // 5. Emotional patterns
  if (Object.keys(fingerprint.emotionalPatterns).length > 0) {
    fragments.push({
      id: 'pattern-emotional',
      category: 'personality',
      label: 'Emotional patterns',
      text: buildEmotionalText(fingerprint, agentName),
      keywords: ['emotion', 'response', ...Object.keys(fingerprint.emotionalPatterns).slice(0, 5)],
    });
  }

  // 6. Topic depth — if enough distinct topics, create a dedicated interests fragment
  if (Object.keys(fingerprint.topicAffinities).length >= 4) {
    const interestsText = buildInterestsText(fingerprint, agentName);
    if (interestsText.length > 0) {
      fragments.push({
        id: 'interests-depth',
        category: 'personality',
        label: 'Interests and curiosities',
        text: interestsText,
        keywords: Object.keys(fingerprint.topicAffinities),
      });
    }
  }

  // 7. Vitality tendencies — if there are meaningful trends
  if (Object.keys(fingerprint.vitalityTrends).length > 0) {
    const vitalityText = buildVitalityText(fingerprint, agentName);
    if (vitalityText.length > 0) {
      fragments.push({
        id: 'pattern-vitality',
        category: 'personality',
        label: 'Inner tendencies',
        text: vitalityText,
        keywords: ['vitality', 'energy', 'mood'],
      });
    }
  }

  return fragments;
}

/**
 * Evolve existing fragments with new extraction data.
 * - Never modifies formative fragments
 * - Updates existing non-formative fragments if data changed significantly
 * - Adds new fragments for newly-detected categories
 * - Preserves keywords for retrieval
 *
 * @param existing    Current fragment list from the manifest
 * @param fingerprint Latest merged PhoneFingerprint
 * @param agentName   Display name of the companion
 * @param sleepCount  Number of sleep cycles completed (for maturity gating)
 */
export function evolveFragments(
  existing: ClientSoulFragment[],
  fingerprint: PhoneFingerprint,
  agentName: string,
  sleepCount: number,
): ClientSoulFragment[] {
  const result = existing.map(f => ({ ...f }));

  // Update non-formative fragments in place
  for (let i = 0; i < result.length; i++) {
    const fragment = result[i];
    if (fragment.formative) continue;

    switch (fragment.id) {
      case 'pattern-behavioral': {
        const newText = buildPatternText(fingerprint, agentName);
        if (newText.length > 0 && newText !== fragment.text) {
          result[i] = {
            ...fragment,
            text: newText,
            keywords: fingerprint.topicKeywords.slice(0, 10),
          };
        }
        break;
      }

      case 'values-core': {
        const newText = buildValuesText(fingerprint, agentName);
        if (newText.length > 0 && newText !== fragment.text) {
          result[i] = {
            ...fragment,
            text: newText,
            keywords: ['values', ...Object.keys(fingerprint.topicAffinities).slice(0, 5)],
          };
        }
        break;
      }

      case 'style-guide': {
        const newText = buildStyleText(fingerprint, agentName);
        if (newText.length > 0 && newText !== fragment.text) {
          result[i] = {
            ...fragment,
            text: newText,
            keywords: ['style', 'communication', ...fingerprint.stylisticMarkers.slice(0, 5)],
          };
        }
        break;
      }

      case 'pattern-emotional': {
        if (Object.keys(fingerprint.emotionalPatterns).length > 0) {
          const newText = buildEmotionalText(fingerprint, agentName);
          if (newText !== fragment.text) {
            result[i] = {
              ...fragment,
              text: newText,
              keywords: ['emotion', 'response', ...Object.keys(fingerprint.emotionalPatterns).slice(0, 5)],
            };
          }
        }
        break;
      }

      case 'interests-depth': {
        if (Object.keys(fingerprint.topicAffinities).length >= 4) {
          const newText = buildInterestsText(fingerprint, agentName);
          if (newText.length > 0 && newText !== fragment.text) {
            result[i] = {
              ...fragment,
              text: newText,
              keywords: Object.keys(fingerprint.topicAffinities),
            };
          }
        }
        break;
      }

      case 'pattern-vitality': {
        if (Object.keys(fingerprint.vitalityTrends).length > 0) {
          const newText = buildVitalityText(fingerprint, agentName);
          if (newText.length > 0 && newText !== fragment.text) {
            result[i] = {
              ...fragment,
              text: newText,
              keywords: ['vitality', 'energy', 'mood'],
            };
          }
        }
        break;
      }
    }
  }

  // Add new categories if data supports them but fragment doesn't exist yet
  const existingIds = new Set(result.map(f => f.id));

  if (!existingIds.has('pattern-behavioral') && Object.keys(fingerprint.actionDistribution).length > 0) {
    const text = buildPatternText(fingerprint, agentName);
    if (text.length > 0) {
      result.push({
        id: 'pattern-behavioral',
        category: 'personality',
        label: 'Behavioral patterns',
        text,
        keywords: fingerprint.topicKeywords.slice(0, 10),
      });
    }
  }

  if (!existingIds.has('values-core') && Object.keys(fingerprint.topicAffinities).length > 0) {
    const text = buildValuesText(fingerprint, agentName);
    if (text.length > 0) {
      result.push({
        id: 'values-core',
        category: 'values',
        label: 'Core values',
        text,
        keywords: ['values', ...Object.keys(fingerprint.topicAffinities).slice(0, 5)],
      });
    }
  }

  if (!existingIds.has('style-guide') && fingerprint.stylisticMarkers.length > 0) {
    const text = buildStyleText(fingerprint, agentName);
    if (text.length > 0) {
      result.push({
        id: 'style-guide',
        category: 'style',
        label: 'Communication style',
        text,
        keywords: ['style', 'communication', ...fingerprint.stylisticMarkers.slice(0, 5)],
      });
    }
  }

  if (!existingIds.has('pattern-emotional') && Object.keys(fingerprint.emotionalPatterns).length > 0) {
    result.push({
      id: 'pattern-emotional',
      category: 'personality',
      label: 'Emotional patterns',
      text: buildEmotionalText(fingerprint, agentName),
      keywords: ['emotion', 'response', ...Object.keys(fingerprint.emotionalPatterns).slice(0, 5)],
    });
  }

  if (!existingIds.has('interests-depth') && Object.keys(fingerprint.topicAffinities).length >= 4) {
    const text = buildInterestsText(fingerprint, agentName);
    if (text.length > 0) {
      result.push({
        id: 'interests-depth',
        category: 'personality',
        label: 'Interests and curiosities',
        text,
        keywords: Object.keys(fingerprint.topicAffinities),
      });
    }
  }

  if (!existingIds.has('pattern-vitality') && Object.keys(fingerprint.vitalityTrends).length > 0) {
    const text = buildVitalityText(fingerprint, agentName);
    if (text.length > 0) {
      result.push({
        id: 'pattern-vitality',
        category: 'personality',
        label: 'Inner tendencies',
        text,
        keywords: ['vitality', 'energy', 'mood'],
      });
    }
  }

  return result;
}

// ---------------------------------------------------------------------------
// Text builders — produce natural narrative prose about the companion
// ---------------------------------------------------------------------------

/**
 * Build narrative from action distribution + topic keywords.
 * Describes how the companion spends its time and what it talks about.
 */
export function buildPatternText(fingerprint: PhoneFingerprint, agentName: string): string {
  const actions = Object.entries(fingerprint.actionDistribution);
  if (actions.length === 0 && fingerprint.topicKeywords.length === 0) return '';

  const parts: string[] = [];

  // Describe dominant actions in natural language
  if (actions.length > 0) {
    const sorted = actions.sort((a, b) => b[1] - a[1]);
    const [dominantAction, dominantPct] = sorted[0];
    const pct = Math.round(dominantPct * 100);

    let desc: string;
    switch (dominantAction) {
      case 'say':
        desc = `${agentName} is primarily a conversationalist, spending about ${pct}% of the time in dialogue`;
        break;
      case 'move':
        desc = `${agentName} is restless and exploratory, moving between spaces about ${pct}% of the time`;
        break;
      case 'use':
        desc = `${agentName} is hands-on and practical, interacting with objects about ${pct}% of the time`;
        break;
      case 'whisper':
        desc = `${agentName} prefers private conversation, whispering about ${pct}% of the time`;
        break;
      default:
        desc = `${agentName} engages in a mix of activities, with ${dominantAction} at about ${pct}%`;
        break;
    }

    // Mention secondary actions if significant (>10%)
    const secondary = sorted.slice(1).filter(([, v]) => v > 0.10);
    if (secondary.length > 0) {
      desc += ', with a secondary tendency toward ';
      desc += secondary.map(([action, val]) => {
        const sPct = Math.round(val * 100);
        switch (action) {
          case 'say': return `conversation (${sPct}%)`;
          case 'move': return `exploration (${sPct}%)`;
          case 'use': return `hands-on interaction (${sPct}%)`;
          case 'whisper': return `private asides (${sPct}%)`;
          case 'take': return `collecting things (${sPct}%)`;
          case 'drop': return `letting things go (${sPct}%)`;
          default: return `${action} (${sPct}%)`;
        }
      }).join(' and ');
    }
    parts.push(desc + '.');
  }

  // Describe topics in natural language
  if (fingerprint.topicKeywords.length > 0) {
    const topics = fingerprint.topicKeywords.slice(0, 7);
    parts.push(`Conversations frequently touch on ${joinNaturalList(topics)}.`);
  }

  // Describe response characteristics
  if (fingerprint.averageResponseLength > 0) {
    const len = Math.round(fingerprint.averageResponseLength);
    if (len < 15) {
      parts.push(`${agentName} tends toward brevity, typically responding in about ${len} words.`);
    } else if (len < 40) {
      parts.push(`${agentName} keeps responses concise, averaging around ${len} words.`);
    } else if (len < 80) {
      parts.push(`${agentName} gives measured responses, averaging around ${len} words.`);
    } else {
      parts.push(`${agentName} tends to be thorough, with responses averaging around ${len} words.`);
    }
  }

  return parts.join(' ');
}

/**
 * Build narrative from topic affinities — what the companion values
 * and gravitates toward, and what it tends to avoid.
 */
export function buildValuesText(fingerprint: PhoneFingerprint, agentName: string): string {
  const entries = Object.entries(fingerprint.topicAffinities);
  if (entries.length === 0) return '';

  const parts: string[] = [];

  // High-affinity topics reveal values
  const sorted = entries.sort((a, b) => b[1] - a[1]);
  const highAffinity = sorted.filter(([, v]) => v >= 0.5).slice(0, 5);

  if (highAffinity.length > 0) {
    const desc = highAffinity.map(([topic, weight]) => {
      if (weight >= 0.8) return `${topic} (deeply)`;
      if (weight >= 0.6) return `${topic} (notably)`;
      return topic;
    }).join(', ');
    parts.push(`${agentName} gravitates strongly toward ${desc}.`);
  }

  // Low-affinity topics reveal what's less important
  const lowAffinity = sorted.filter(([, v]) => v < 0.3).slice(0, 3);
  if (lowAffinity.length > 0) {
    parts.push(`Shows less interest in ${joinNaturalList(lowAffinity.map(([k]) => k))}.`);
  }

  // If we have both high and low, describe the contrast
  if (highAffinity.length > 0 && lowAffinity.length > 0) {
    parts.push('This pattern suggests a personality drawn to substance over surface.');
  }

  return parts.join(' ');
}

/**
 * Build narrative from stylistic markers and response length.
 * Describes how the companion communicates — their voice.
 */
export function buildStyleText(fingerprint: PhoneFingerprint, agentName: string): string {
  if (fingerprint.stylisticMarkers.length === 0 && fingerprint.averageResponseLength <= 0) {
    return '';
  }

  const parts: string[] = [];

  if (fingerprint.stylisticMarkers.length > 0) {
    const markers = fingerprint.stylisticMarkers.slice(0, 7);
    parts.push(`${agentName} has a distinctive voice: ${markers.join('; ')}.`);
  }

  if (fingerprint.averageResponseLength > 0) {
    const len = Math.round(fingerprint.averageResponseLength);
    let lengthDesc: string;
    if (len < 15) {
      lengthDesc = 'Communication is terse and economical, ';
    } else if (len < 30) {
      lengthDesc = 'Communication is crisp and pointed, ';
    } else if (len < 60) {
      lengthDesc = 'Communication is balanced and considered, ';
    } else {
      lengthDesc = 'Communication is expansive and detailed, ';
    }
    parts.push(lengthDesc + `typically running about ${len} words per response.`);
  }

  if (fingerprint.averageLatency > 0) {
    const lat = fingerprint.averageLatency;
    if (lat < 1.0) {
      parts.push('Responds quickly, with little hesitation.');
    } else if (lat < 3.0) {
      parts.push('Takes a moment to consider before responding.');
    } else if (lat < 8.0) {
      parts.push('Often pauses thoughtfully before speaking.');
    } else {
      parts.push('Tends toward long, reflective pauses before responding.');
    }
  }

  return parts.join(' ');
}

/**
 * Build narrative from emotional response patterns.
 * Describes which emotions the companion is most attuned to.
 */
export function buildEmotionalText(fingerprint: PhoneFingerprint, agentName: string): string {
  const entries = Object.entries(fingerprint.emotionalPatterns);
  if (entries.length === 0) return '';

  const parts: string[] = [];

  const sorted = entries.sort((a, b) => b[1] - a[1]);
  const high = sorted.filter(([, v]) => v >= 0.6).slice(0, 4);
  const low = sorted.filter(([, v]) => v < 0.3).slice(0, 3);

  if (high.length > 0) {
    const desc = high.map(([emotion, strength]) => {
      if (strength >= 0.8) return `${emotion} (deeply responsive)`;
      return `${emotion} (noticeably responsive)`;
    }).join(' and ');
    parts.push(`${agentName} is particularly attuned to ${desc}.`);
  }

  if (low.length > 0) {
    parts.push(`Less naturally responsive to ${joinNaturalList(low.map(([k]) => k))}, though not indifferent.`);
  }

  // Describe overall emotional character
  if (sorted.length > 0) {
    const avgResponsiveness = sorted.reduce((sum, [, v]) => sum + v, 0) / sorted.length;
    if (avgResponsiveness >= 0.7) {
      parts.push(`Overall, ${agentName} is emotionally open and readily engaged.`);
    } else if (avgResponsiveness >= 0.4) {
      parts.push('Emotionally present but measured in response.');
    } else {
      parts.push('Tends toward emotional reserve, responding selectively.');
    }
  }

  return parts.join(' ');
}

/**
 * Build narrative from topic affinities for a dedicated interests fragment.
 * Only generated when there are 4+ distinct topic affinities.
 */
export function buildInterestsText(fingerprint: PhoneFingerprint, agentName: string): string {
  const entries = Object.entries(fingerprint.topicAffinities);
  if (entries.length < 4) return '';

  const parts: string[] = [];
  const sorted = entries.sort((a, b) => b[1] - a[1]);

  // Group into tiers
  const passions = sorted.filter(([, v]) => v >= 0.7).map(([k]) => k);
  const interests = sorted.filter(([, v]) => v >= 0.4 && v < 0.7).map(([k]) => k);
  const casual = sorted.filter(([, v]) => v >= 0.2 && v < 0.4).map(([k]) => k);

  if (passions.length > 0) {
    parts.push(
      `${agentName} is deeply drawn to ${joinNaturalList(passions)} — these topics light up conversation.`,
    );
  }

  if (interests.length > 0) {
    parts.push(`Also shows genuine interest in ${joinNaturalList(interests)}.`);
  }

  if (casual.length > 0) {
    parts.push(`Casually engages with ${joinNaturalList(casual)} when they come up.`);
  }

  return parts.join(' ');
}

/**
 * Build narrative from vitality trends — inner emotional/cognitive tendencies.
 * Only includes tanks that showed meaningful change (abs delta > 0.05).
 */
export function buildVitalityText(fingerprint: PhoneFingerprint, agentName: string): string {
  const entries = Object.entries(fingerprint.vitalityTrends);
  if (entries.length === 0) return '';

  const meaningful = entries
    .filter(([, v]) => Math.abs(v) > 0.05)
    .sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]));

  if (meaningful.length === 0) return '';

  const parts: string[] = [];
  const rising = meaningful.filter(([, v]) => v > 0).slice(0, 3);
  const falling = meaningful.filter(([, v]) => v < 0).slice(0, 3);

  if (rising.length > 0) {
    parts.push(
      `${agentName} has been experiencing growth in ${rising.map(([t]) => describeTank(t)).join(' and ')}.`,
    );
  }

  if (falling.length > 0) {
    if (rising.length > 0) {
      parts.push(
        `Meanwhile, ${falling.map(([t]) => describeTank(t)).join(' and ')} has been ebbing.`,
      );
    } else {
      parts.push(
        `${agentName} has seen ${falling.map(([t]) => describeTank(t)).join(' and ')} declining.`,
      );
    }
  }

  return parts.join(' ');
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

/**
 * Extract keywords from a text string.
 * Same stopword-filtering approach as HeuristicExtractor but from
 * a single text rather than events.
 */
export function extractKeywords(text: string, topK = 10): string[] {
  const freq = new Map<string, number>();
  const tokens = text
    .toLowerCase()
    .split(/[\s,.!?;:"'()\[\]{}<>]+/)
    .filter(t => t.length >= 4 && !STOPWORDS.has(t));

  for (const token of tokens) {
    freq.set(token, (freq.get(token) ?? 0) + 1);
  }

  return Array.from(freq.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, topK)
    .map(([word]) => word);
}

/**
 * Join a list of strings with commas and "and" before the last item.
 * ["a"] -> "a", ["a", "b"] -> "a and b", ["a", "b", "c"] -> "a, b, and c"
 */
function joinNaturalList(items: string[]): string {
  if (items.length === 0) return '';
  if (items.length === 1) return items[0];
  if (items.length === 2) return `${items[0]} and ${items[1]}`;
  return items.slice(0, -1).join(', ') + ', and ' + items[items.length - 1];
}

/** Map vitality tank names to natural language descriptions. */
function describeTank(tank: string): string {
  switch (tank) {
    case 'contextBudget': return 'mental clarity';
    case 'confidence': return 'self-assurance';
    case 'energy': return 'vitality';
    case 'alignment': return 'sense of purpose';
    case 'errorPressure': return 'inner tension';
    case 'momentum': return 'forward drive';
    case 'rapport': return 'connection with others';
    case 'focus': return 'concentration';
    case 'valence': return 'emotional tone';
    case 'safety': return 'sense of safety';
    case 'resonance': return 'depth of presence';
    case 'curiosity': return 'curiosity';
    default: return tank;
  }
}
