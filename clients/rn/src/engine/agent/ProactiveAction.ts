/**
 * An action the agent wants to take proactively (not in response to human speech).
 * TypeScript port of core/agent/ProactiveAction.java.
 *
 * Three tiers of intrusion, each costing different amounts of proactivity budget.
 * Uses TypeScript discriminated union pattern (tagged by `tier` field).
 */

/**
 * Low-intrusion: emotes, room presence, idle behaviors.
 * Examples: *adjusts crystal thoughtfully*, *glances at bookshelf*
 */
export interface AmbientAction {
  tier: 'ambient';
  emoteText: string;
  driveName: string;
  budgetCost: 0.1;
}

/**
 * Medium-intrusion: share what the agent noticed.
 * Examples: "The Oracle noticed a pattern...", "I found something interesting..."
 */
export interface ObservationAction {
  tier: 'observation';
  speechText: string;
  driveName: string;
  category: string;
  budgetCost: 0.3;
}

/**
 * High-intrusion: autonomous navigation, search, commitment fulfillment.
 * Examples: navigate to Library and search, act on a commitment
 */
export interface InitiativeAction {
  tier: 'initiative';
  actionJson: string;
  driveName: string;
  description: string;
  budgetCost: 0.7;
}

/** Discriminated union of all proactive action types. */
export type ProactiveAction = AmbientAction | ObservationAction | InitiativeAction;

// ── Constructors ────────────────────────────────────────────────────────

export function ambient(emoteText: string, driveName: string): AmbientAction {
  return { tier: 'ambient', emoteText, driveName, budgetCost: 0.1 };
}

export function observation(speechText: string, driveName: string, category: string): ObservationAction {
  return { tier: 'observation', speechText, driveName, category, budgetCost: 0.3 };
}

export function initiative(actionJson: string, driveName: string, description: string): InitiativeAction {
  return { tier: 'initiative', actionJson, driveName, description, budgetCost: 0.7 };
}
