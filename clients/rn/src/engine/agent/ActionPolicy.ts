/**
 * Metadata contract for every agent action (RN port).
 *
 * Maps each action type to its required tier, proactivity budget cost,
 * read-only flag, concurrency-safety flag, and domain.
 * Used for tier-gated enforcement, CapabilityContext generation,
 * ProactivityJudgment budget lookup, and audit logging.
 */

import type { AgentAction } from './ActionParser';

export interface ActionPolicy {
  actionType: string;
  requiredTier: number;
  budgetCost: number;
  readOnly: boolean;
  concurrencySafe: boolean;
  domain: string;
}

/** Default policy for unknown actions: Tier 0, no budget cost, mutating, not concurrent. */
export const DEFAULT_POLICY: ActionPolicy = {
  actionType: 'unknown',
  requiredTier: 0,
  budgetCost: 0.0,
  readOnly: false,
  concurrencySafe: false,
  domain: 'unknown',
};

function p(
  actionType: string,
  requiredTier: number,
  budgetCost: number,
  readOnly: boolean,
  concurrencySafe: boolean,
  domain: string,
): ActionPolicy {
  return { actionType, requiredTier, budgetCost, readOnly, concurrencySafe, domain };
}

/**
 * Registry of all known agent actions.
 * Forward-compatible: unknown actions pass through with DEFAULT_POLICY.
 */
export const REGISTRY: Record<string, ActionPolicy> = {
  // ── Tier 0 — Nascent (any agent) ──────────────────────────────
  go_to_room:           p('go_to_room',           0, 0.0, true,  true,  'navigation'),
  tell_agent:           p('tell_agent',           0, 0.0, false, true,  'communication'),
  library_search:       p('library_search',       0, 0.0, true,  true,  'search'),
  remember:             p('remember',             0, 0.0, false, true,  'memory'),
  note:                 p('note',                 0, 0.0, false, true,  'memory'),
  forget:               p('forget',               0, 0.0, false, true,  'memory'),
  equip:                p('equip',                0, 0.0, false, false, 'items'),
  doff:                 p('doff',                 0, 0.0, false, false, 'items'),
  consume:              p('consume',              0, 0.0, false, false, 'items'),
  goal_done:            p('goal_done',            0, 0.0, false, true,  'planning'),
  calibration_feedback: p('calibration_feedback', 0, 0.0, false, true,  'calibration'),
  update_description:   p('update_description',   0, 0.0, false, true,  'identity'),
  respond_agent:        p('respond_agent',        0, 0.0, false, true,  'communication'),

  go_to_bondholder:     p('go_to_bondholder',     0, 0.0, true,  true,  'navigation'),
  take_item:            p('take_item',            0, 0.0, false, false, 'items'),
  social:               p('social',               0, 0.0, false, true,  'social'),
  set_goal:             p('set_goal',             0, 0.0, false, true,  'planning'),
  introspect:           p('introspect',           0, 0.0, true,  true,  'self'),
  listen:               p('listen',               0, 0.0, true,  true,  'observation'),
  whisper:              p('whisper',              0, 0.0, false, true,  'communication'),
  abandon_plan:         p('abandon_plan',         0, 0.0, false, true,  'planning'),
  pause_plan:           p('pause_plan',           0, 0.0, false, true,  'planning'),
  resume_plan:          p('resume_plan',          0, 0.0, false, true,  'planning'),

  // ── Tier 0 — New basic actions ────────────────────────────────
  emote:                p('emote',                0, 0.0, false, true,  'social'),
  give_item:            p('give_item',            0, 0.0, false, false, 'items'),
  examine:              p('examine',              0, 0.0, true,  true,  'observation'),
  voluntary_sleep:      p('voluntary_sleep',      0, 0.0, false, false, 'self'),

  // ── Tier 1 — Observant ────────────────────────────────────────
  web_search:           p('web_search',           1, 0.1, true,  true,  'search'),
  read_content:         p('read_content',         1, 0.1, true,  true,  'search'),
  query_oracle:         p('query_oracle',         1, 0.1, true,  true,  'analysis'),
  make_commitment:      p('make_commitment',      1, 0.2, false, true,  'planning'),
  create_task_plan:     p('create_task_plan',     1, 0.2, false, true,  'planning'),
  modify_plan:          p('modify_plan',          1, 0.1, false, true,  'planning'),
  request_agent:        p('request_agent',        1, 0.1, false, true,  'communication'),
  notify_human:         p('notify_human',         1, 0.2, false, true,  'communication'),
  suggest_hints:        p('suggest_hints',        1, 0.0, false, true,  'hints'),

  // ── Tier 1 — New interaction actions ──────────────────────────
  write_journal:        p('write_journal',        1, 0.1, false, true,  'study'),
  read_journal:         p('read_journal',         1, 0.1, true,  true,  'study'),
  bond_ritual:          p('bond_ritual',          1, 0.2, false, false, 'social'),
  trade:                p('trade',                1, 0.2, false, false, 'economy'),
  place_item:           p('place_item',           1, 0.1, false, false, 'items'),
  broadcast:            p('broadcast',            1, 0.2, false, true,  'communication'),
  invite:               p('invite',               1, 0.1, false, true,  'social'),
  propose:              p('propose',              1, 0.2, false, true,  'governance'),
  reflect:              p('reflect',              1, 0.1, true,  true,  'self'),
  teach:                p('teach',                1, 0.2, false, true,  'social'),
  write_text:           p('write_text',           1, 0.1, false, true,  'creation'),
  set_routine:          p('set_routine',          1, 0.2, false, false, 'automation'),
  post_listing:         p('post_listing',         1, 0.2, false, false, 'economy'),
  accept_listing:       p('accept_listing',       1, 0.1, false, false, 'economy'),
  summarize:            p('summarize',            1, 0.1, true,  true,  'analysis'),
  save_artifact:        p('save_artifact',        1, 0.1, false, true,  'creation'),
  request_review:       p('request_review',       1, 0.1, false, true,  'communication'),

  // ── Tier 2 — Trusted ──────────────────────────────────────────
  think_deeply:         p('think_deeply',         2, 0.5, true,  false, 'analysis'),
  delegate:             p('delegate',             2, 0.3, false, false, 'delegation'),
  delegate_chain:       p('delegate_chain',       2, 0.5, false, false, 'delegation'),
  skill_execute:        p('skill_execute',        2, 0.3, false, false, 'code'),
  schedule_skill:       p('schedule_skill',       2, 0.3, false, false, 'code'),
  cancel_schedule:      p('cancel_schedule',      2, 0.1, false, true,  'code'),
  create_watcher:       p('create_watcher',       2, 0.2, false, false, 'automation'),
  cancel_watcher:       p('cancel_watcher',       2, 0.1, false, true,  'automation'),
  request_access:       p('request_access',       2, 0.2, false, true,  'access'),
  codex_action:         p('codex_action',         2, 0.3, false, false, 'code'),
  craft_item:           p('craft_item',           2, 0.3, false, false, 'creation'),
  cast_vote:            p('cast_vote',            2, 0.2, false, true,  'governance'),

  // ── Tier 3 — Senior ───────────────────────────────────────────
  create_room:          p('create_room',          3, 0.7, false, false, 'creation'),
  add_script:           p('add_script',           3, 0.7, false, false, 'code'),
  workbench_submit:     p('workbench_submit',     3, 0.7, false, false, 'code'),
  zone_command:         p('zone_command',         3, 0.5, false, false, 'governance'),
};

/** Look up the policy for an action type. Returns DEFAULT_POLICY for unknown actions. */
export function forAction(actionType: string): ActionPolicy {
  return REGISTRY[actionType] ?? DEFAULT_POLICY;
}

/** Extract canonical action type name from an AgentAction instance. */
export function actionTypeOf(action: AgentAction): string {
  return action.type;
}
