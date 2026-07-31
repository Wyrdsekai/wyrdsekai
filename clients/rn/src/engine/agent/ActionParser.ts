/**
 * Parses structured action blocks from LLM output.
 * TypeScript port of ActionParser.java -- all 17+ action types.
 *
 * Looks for ```json ... ``` blocks containing action instructions.
 * Returns primary action + separately extracted hints + remaining prose.
 */

import type { Hint } from '../../protocol/models';

// ---------------------------------------------------------------------------
// Action types (discriminated union)
// ---------------------------------------------------------------------------

export interface CreateRoomAction {
  type: 'create_room';
  name: string;
  description: string;
  exits: CreateRoomExit[];
  behaviorScript: string | null;
}

export interface SuggestHintsAction {
  type: 'suggest_hints';
  hints: Hint[];
}

export interface WorkbenchSubmitAction {
  type: 'workbench_submit';
  skillName: string;
  skillDescription: string;
  runtime: string;
  code: string;
  params: SkillParam[];
  testCases: TestCase[];
}

export interface SkillExecuteAction {
  type: 'skill_execute';
  skillName: string;
  params: Record<string, unknown>;
}

export interface EquipAction {
  type: 'equip';
  itemName: string;
}

export interface DoffAction {
  type: 'doff';
  itemName: string;
}

export interface ConsumeAction {
  type: 'consume';
  itemName: string;
}

export interface ZoneCommandAction {
  type: 'zone_command';
  command: string;
  payload: Record<string, string>;
}

export interface MakeCommitmentAction {
  type: 'make_commitment';
  description: string;
  deadline: string | null;
}

export interface ThinkDeeplyAction {
  type: 'think_deeply';
  capability: string | null;
  delegationPrompt: string;
}

export interface GoToRoomAction {
  type: 'go_to_room';
  target: string;
  reason: string | null;
}

export interface TellAgentAction {
  type: 'tell_agent';
  targetName: string;
  message: string;
}

export interface DelegateChainAction {
  type: 'delegate_chain';
  goal: string;
  steps: ChainStepSpec[];
}

export interface CodexAction {
  type: 'codex_action';
  operation: string;
  itemId: string;
  params: Record<string, string>;
}

export interface ScheduleSkillAction {
  type: 'schedule_skill';
  skillId: string;
  interval: string;
  params: Record<string, string>;
}

export interface CancelScheduleAction {
  type: 'cancel_schedule';
  scheduleId: string;
}

export interface NotifyHumanAction {
  type: 'notify_human';
  message: string;
  priority: string;
  target: string;
}

export interface CreateWatcherAction {
  type: 'create_watcher';
  name: string;
  checkScript: string;
  interval: string;
  alertOn: string;
  message: string;
  priority: string;
}

export interface CancelWatcherAction {
  type: 'cancel_watcher';
  watcherId: string;
}

export interface RequestAccessAction {
  type: 'request_access';
  source: string;
  scope: string;
  reason: string;
}

export interface EmoteAction {
  type: 'emote';
  text: string;
}

export interface GiveItemAction {
  type: 'give_item';
  itemName: string;
  targetName: string;
}

export interface ExamineAction {
  type: 'examine';
  target: string;
}

export interface VoluntarySleepAction {
  type: 'voluntary_sleep';
  reason: string;
}

export interface WriteJournalAction {
  type: 'write_journal';
  playerId: string;
  content: string;
  category: string;
}

export interface ReadJournalAction {
  type: 'read_journal';
  playerId: string;
  query: string;
}

export interface BondRitualAction {
  type: 'bond_ritual';
  targetName: string;
  ritualType: string;
}

export interface TradeAction {
  type: 'trade';
  targetName: string;
  offer: string;
  request: string;
}

export interface CraftItemAction {
  type: 'craft_item';
  name: string;
  description: string;
  category: string;
  properties: Record<string, string>;
}

export interface CastVoteAction {
  type: 'cast_vote';
  proposalId: string;
  vote: string;
  reason: string;
}

// --- Tier 0 additions ---

export interface GoToBondholderAction {
  type: 'go_to_bondholder';
  playerName: string;
}

export interface LibrarySearchAction {
  type: 'library_search';
  query: string;
  collections: string | null;
}

export interface RememberAction {
  type: 'remember';
  content: string;
  importance: number | null;
}

export interface NoteAction {
  type: 'note';
  content: string;
}

export interface ForgetAction {
  type: 'forget';
  content: string;
}

export interface GoalDoneAction {
  type: 'goal_done';
  summary: string;
}

export interface CalibrationFeedbackAction {
  type: 'calibration_feedback';
  feedbackType: string;
  direction: string;
  category: string | null;
  reason: string | null;
}

export interface UpdateDescriptionAction {
  type: 'update_description';
  text: string;
}

export interface RespondAgentAction {
  type: 'respond_agent';
  requestId: string;
  response: string;
}

export interface TakeItemAction {
  type: 'take_item';
  itemName: string;
}

export interface SocialAction {
  type: 'social';
  socialType: string;
}

export interface SetGoalAction {
  type: 'set_goal';
  description: string;
  priority: string | null;
}

export interface IntrospectAction {
  type: 'introspect';
  focus: string;
}

export interface ListenAction {
  type: 'listen';
  target: string;
  duration: string | null;
}

export interface WhisperAction {
  type: 'whisper';
  target: string;
  message: string;
}

export interface AbandonPlanAction {
  type: 'abandon_plan';
  reason: string;
}

export interface PausePlanAction {
  type: 'pause_plan';
  reason: string;
}

export interface ResumePlanAction {
  type: 'resume_plan';
  reason: string | null;
}

// --- Tier 1 additions ---

export interface WebSearchAction {
  type: 'web_search';
  query: string;
  maxResults: number | null;
}

export interface ReadContentAction {
  type: 'read_content';
  url: string;
}

export interface QueryOracleAction {
  type: 'query_oracle';
  topic: string;
  analysisType: string | null;
}

export interface CreateTaskPlanAction {
  type: 'create_task_plan';
  description: string;
  goals: string[];
}

export interface ModifyPlanAction {
  type: 'modify_plan';
  modification: string;
  reason: string;
}

export interface RequestAgentAction {
  type: 'request_agent';
  targetName: string;
  request: string;
}

export interface PlaceItemAction {
  type: 'place_item';
  itemName: string;
}

export interface BroadcastAction {
  type: 'broadcast';
  message: string;
  scope: string | null;
}

export interface InviteEntityAction {
  type: 'invite';
  targetName: string;
  roomId: string | null;
}

export interface ProposeAction {
  type: 'propose';
  title: string;
  description: string;
}

export interface ReflectAction {
  type: 'reflect';
  focus: string;
  depth: string | null;
}

export interface TeachAction {
  type: 'teach';
  targetAgent: string;
  topic: string;
  content: string;
}

export interface WriteTextAction {
  type: 'write_text';
  title: string;
  content: string;
  format: string | null;
}

export interface SetRoutineAction {
  type: 'set_routine';
  trigger: string;
  behavior: string;
}

export interface PostListingAction {
  type: 'post_listing';
  offerType: string;
  description: string;
  price: string;
}

export interface AcceptListingAction {
  type: 'accept_listing';
  listingId: string;
}

export interface SummarizeAction {
  type: 'summarize';
  source: string;
  format: string | null;
}

export interface SaveArtifactAction {
  type: 'save_artifact';
  name: string;
  content: string;
  artifactType: string | null;
}

export interface RequestReviewAction {
  type: 'request_review';
  description: string;
  targetAgent: string | null;
}

// --- Tier 2 additions ---

export interface DelegateAction {
  type: 'delegate';
  targetAgent: string;
  task: string;
}

// --- Tier 3 additions ---

export interface AddScriptAction {
  type: 'add_script';
  roomId: string;
  script: string;
}

// ---------------------------------------------------------------------------
// Supporting types
// ---------------------------------------------------------------------------

export interface CreateRoomExit {
  direction: string;
  target: string;
  label: string;
}

export interface SkillParam {
  name: string;
  type: string;
  description: string;
  required: boolean;
}

export interface TestCase {
  params: Record<string, unknown>;
  expectSuccess: boolean;
  expectContains: string | null;
}

export interface ChainStepSpec {
  skill: string;
  params: Record<string, unknown>;
  description: string | null;
}

// ---------------------------------------------------------------------------
// Union type
// ---------------------------------------------------------------------------

export type AgentAction =
  // Tier 0
  | GoToRoomAction
  | GoToBondholderAction
  | TellAgentAction
  | LibrarySearchAction
  | RememberAction
  | NoteAction
  | ForgetAction
  | EquipAction
  | DoffAction
  | ConsumeAction
  | GoalDoneAction
  | CalibrationFeedbackAction
  | UpdateDescriptionAction
  | RespondAgentAction
  | EmoteAction
  | GiveItemAction
  | ExamineAction
  | VoluntarySleepAction
  | TakeItemAction
  | SocialAction
  | SetGoalAction
  | IntrospectAction
  | ListenAction
  | WhisperAction
  | AbandonPlanAction
  | PausePlanAction
  | ResumePlanAction
  // Tier 1
  | WebSearchAction
  | ReadContentAction
  | QueryOracleAction
  | MakeCommitmentAction
  | CreateTaskPlanAction
  | ModifyPlanAction
  | RequestAgentAction
  | NotifyHumanAction
  | SuggestHintsAction
  | WriteJournalAction
  | ReadJournalAction
  | BondRitualAction
  | TradeAction
  | PlaceItemAction
  | BroadcastAction
  | InviteEntityAction
  | ProposeAction
  | ReflectAction
  | TeachAction
  | WriteTextAction
  | SetRoutineAction
  | PostListingAction
  | AcceptListingAction
  | SummarizeAction
  | SaveArtifactAction
  | RequestReviewAction
  // Tier 2
  | ThinkDeeplyAction
  | DelegateAction
  | DelegateChainAction
  | SkillExecuteAction
  | ScheduleSkillAction
  | CancelScheduleAction
  | CreateWatcherAction
  | CancelWatcherAction
  | RequestAccessAction
  | CodexAction
  | CraftItemAction
  | CastVoteAction
  // Tier 3
  | CreateRoomAction
  | AddScriptAction
  | WorkbenchSubmitAction
  | ZoneCommandAction;

// ---------------------------------------------------------------------------
// Parse result
// ---------------------------------------------------------------------------

export interface ParseResult {
  prose: string;
  actions: AgentAction[];
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

const JSON_BLOCK_REGEX = /```json\s*\n?([\s\S]*?)\n?```/g;

/**
 * Parse the LLM response for embedded JSON actions.
 * Extracts all ```json ... ``` blocks, parses each as an action,
 * and returns remaining prose plus all recognized actions.
 */
export function parseActions(text: string): ParseResult {
  const actions: AgentAction[] = [];
  let prose = text;

  for (const match of text.matchAll(JSON_BLOCK_REGEX)) {
    const jsonStr = match[1].trim();
    prose = prose.replace(match[0], '').trim();

    try {
      const obj = JSON.parse(jsonStr) as Record<string, unknown>;
      const actionType = obj.action as string | undefined;
      if (!actionType) continue;

      const parsed = parseAction(actionType, obj);
      if (parsed) {
        actions.push(parsed);
      }
    } catch {
      // Malformed JSON blocks are silently ignored
    }
  }

  return { prose: stripRawActionJson(prose), actions };
}

/**
 * Extract the conversational prose from a response that contains an action block.
 * Returns everything before the first ```json block.
 */
export function extractProse(text: string): string {
  if (!text) return '';
  const idx = text.indexOf('```json');
  if (idx <= 0) return stripRawActionJson(text.trim());
  return stripRawActionJson(text.substring(0, idx).trim());
}

/**
 * Leak floor (task #30): strip raw UN-fenced {"action": ...} JSON objects from
 * prose so scaffold JSON never reaches the displayed companion reply. Small
 * models sometimes emit the action object without the ```json fence — the
 * fence-removal in parseActions/extractProse misses those and the raw JSON
 * leaked into the room prose. Mirrors the server ActionParser.extractRawJson
 * brace-matching semantics: only objects containing an "action" key are
 * removed (ordinary JSON or braces in conversation are preserved); a
 * truncated trailing action object (unmatched brace) is dropped too.
 */
export function stripRawActionJson(text: string): string {
  if (!text || !text.includes('{')) return text;
  let out = '';
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch !== '{') {
      out += ch;
      i++;
      continue;
    }
    const end = findMatchingBrace(text, i);
    if (end < 0) {
      // Unmatched brace — truncated JSON. Drop it only if it looks like an
      // action object; otherwise keep the tail as-is.
      const tail = text.substring(i);
      if (!tail.includes('"action"') && !tail.includes("'action'")) out += tail;
      break;
    }
    const candidate = text.substring(i, end + 1);
    if (!candidate.includes('"action"') && !candidate.includes("'action'")) {
      out += candidate;
    }
    i = end + 1;
  }
  return out.replace(/\n{3,}/g, '\n\n').trim();
}

/** Find the index of the `}` matching the `{` at `start`, respecting nesting and JSON strings. */
function findMatchingBrace(text: string, start: number): number {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (ch === '\\') {
      if (inString) escaped = true;
      continue;
    }
    if (ch === '"') {
      inString = !inString;
      continue;
    }
    if (inString) continue;
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

// ---------------------------------------------------------------------------
// Internal
// ---------------------------------------------------------------------------

function parseAction(actionType: string, obj: Record<string, unknown>): AgentAction | null {
  switch (actionType) {
    // ── Tier 0 ──────────────────────────────────────────────────────
    case 'go_to_room':
      return parseGoToRoom(obj);
    case 'go_to_bondholder':
      return parseGoToBondholder(obj);
    case 'tell_agent':
      return parseTellAgent(obj);
    case 'library_search':
      return parseLibrarySearch(obj);
    case 'remember':
      return parseRemember(obj);
    case 'note':
      return parseNote(obj);
    case 'forget':
      return parseForget(obj);
    case 'equip':
      return parseEquip(obj);
    case 'doff':
      return parseDoff(obj);
    case 'consume':
      return parseConsume(obj);
    case 'goal_done':
      return parseGoalDone(obj);
    case 'calibration_feedback':
      return parseCalibrationFeedback(obj);
    case 'update_description':
      return parseUpdateDescription(obj);
    case 'respond_agent':
      return parseRespondAgent(obj);
    case 'emote':
      return parseEmote(obj);
    case 'give_item':
      return parseGiveItem(obj);
    case 'examine':
      return parseExamine(obj);
    case 'voluntary_sleep':
      return parseVoluntarySleep(obj);
    case 'take_item':
      return parseTakeItem(obj);
    case 'social':
      return parseSocial(obj);
    case 'set_goal':
      return parseSetGoal(obj);
    case 'introspect':
      return parseIntrospect(obj);
    case 'listen':
      return parseListen(obj);
    case 'whisper':
      return parseWhisper(obj);
    case 'abandon_plan':
      return parseAbandonPlan(obj);
    case 'pause_plan':
      return parsePausePlan(obj);
    case 'resume_plan':
      return parseResumePlan(obj);
    // ── Tier 1 ──────────────────────────────────────────────────────
    case 'web_search':
      return parseWebSearch(obj);
    case 'read_content':
      return parseReadContent(obj);
    case 'query_oracle':
      return parseQueryOracle(obj);
    case 'make_commitment':
      return parseMakeCommitment(obj);
    case 'create_task_plan':
      return parseCreateTaskPlan(obj);
    case 'modify_plan':
      return parseModifyPlan(obj);
    case 'request_agent':
      return parseRequestAgent(obj);
    case 'notify':
    case 'notify_human':
      return parseNotifyHuman(obj);
    case 'suggest_hints':
      return parseSuggestHints(obj);
    case 'write_journal':
      return parseWriteJournal(obj);
    case 'read_journal':
      return parseReadJournal(obj);
    case 'bond_ritual':
      return parseBondRitual(obj);
    case 'trade':
      return parseTrade(obj);
    case 'place_item':
      return parsePlaceItem(obj);
    case 'broadcast':
      return parseBroadcast(obj);
    case 'invite':
      return parseInvite(obj);
    case 'propose':
      return parsePropose(obj);
    case 'reflect':
      return parseReflect(obj);
    case 'teach':
      return parseTeach(obj);
    case 'write_text':
      return parseWriteText(obj);
    case 'set_routine':
      return parseSetRoutine(obj);
    case 'post_listing':
      return parsePostListing(obj);
    case 'accept_listing':
      return parseAcceptListing(obj);
    case 'summarize':
      return parseSummarize(obj);
    case 'save_artifact':
      return parseSaveArtifact(obj);
    case 'request_review':
      return parseRequestReview(obj);
    // ── Tier 2 ──────────────────────────────────────────────────────
    case 'think_deeply':
      return parseThinkDeeply(obj);
    case 'delegate':
      return parseDelegate(obj);
    case 'delegate_chain':
      return parseDelegateChain(obj);
    case 'skill_execute':
      return parseSkillExecute(obj);
    case 'schedule':
    case 'schedule_skill':
      return parseScheduleSkill(obj);
    case 'cancel_schedule':
      return parseCancelSchedule(obj);
    case 'watch':
    case 'create_watcher':
      return parseCreateWatcher(obj);
    case 'cancel_watch':
    case 'cancel_watcher':
      return parseCancelWatcher(obj);
    case 'request_access':
      return parseRequestAccess(obj);
    case 'codex_action':
      return parseCodexAction(obj);
    case 'craft_item':
      return parseCraftItem(obj);
    case 'cast_vote':
      return parseCastVote(obj);
    // ── Tier 3 ──────────────────────────────────────────────────────
    case 'create_room':
      return parseCreateRoom(obj);
    case 'add_script':
      return parseAddScript(obj);
    case 'workbench_submit':
      return parseWorkbenchSubmit(obj);
    case 'zone_command':
      return parseZoneCommand(obj);
    default:
      return null;
  }
}

function str(v: unknown, fallback: string = ''): string {
  return typeof v === 'string' ? v : fallback;
}

function strOrNull(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null;
}

function boolVal(v: unknown, fallback: boolean = false): boolean {
  return typeof v === 'boolean' ? v : fallback;
}

function objMap(v: unknown): Record<string, string> {
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    const result: Record<string, string> = {};
    for (const [k, val] of Object.entries(v)) {
      result[k] = String(val);
    }
    return result;
  }
  return {};
}

function unknownMap(v: unknown): Record<string, unknown> {
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    return v as Record<string, unknown>;
  }
  return {};
}

function numOrNull(v: unknown): number | null {
  return typeof v === 'number' ? v : null;
}

function strArray(v: unknown): string[] {
  if (Array.isArray(v)) {
    return v.filter((x) => typeof x === 'string') as string[];
  }
  return [];
}

// --- Individual parsers ---

function parseCreateRoom(obj: Record<string, unknown>): CreateRoomAction | null {
  const name = str(obj.name);
  if (!name) return null;

  const exits: CreateRoomExit[] = [];
  if (Array.isArray(obj.exits)) {
    for (const e of obj.exits) {
      if (e && typeof e === 'object') {
        const rec = e as Record<string, unknown>;
        const direction = str(rec.direction);
        if (direction) {
          exits.push({
            direction,
            target: str(rec.target, 'home'),
            label: str(rec.label, direction),
          });
        }
      }
    }
  }

  return {
    type: 'create_room',
    name,
    description: str(obj.description, 'An empty room.'),
    exits,
    behaviorScript: strOrNull(obj.behavior_script),
  };
}

function parseSuggestHints(obj: Record<string, unknown>): SuggestHintsAction | null {
  if (!Array.isArray(obj.hints)) return null;

  const hints: Hint[] = [];
  for (const h of obj.hints) {
    if (h && typeof h === 'object') {
      const rec = h as Record<string, unknown>;
      const label = str(rec.label);
      if (label) {
        hints.push({
          label,
          intent: str(rec.intent),
          action: (str(rec.action, 'say') as Hint['action']),
          labelKey: null,
        });
      }
    }
  }

  return hints.length > 0 ? { type: 'suggest_hints', hints } : null;
}

function parseWorkbenchSubmit(obj: Record<string, unknown>): WorkbenchSubmitAction {
  const params: SkillParam[] = [];
  if (Array.isArray(obj.params)) {
    for (const p of obj.params) {
      if (p && typeof p === 'object') {
        const rec = p as Record<string, unknown>;
        params.push({
          name: str(rec.name),
          type: str(rec.type, 'string'),
          description: str(rec.description),
          required: boolVal(rec.required),
        });
      }
    }
  }

  const testCases: TestCase[] = [];
  if (Array.isArray(obj.test_cases)) {
    for (const t of obj.test_cases) {
      if (t && typeof t === 'object') {
        const rec = t as Record<string, unknown>;
        testCases.push({
          params: unknownMap(rec.params),
          expectSuccess: boolVal(rec.expect_success, true),
          expectContains: strOrNull(rec.expect_contains),
        });
      }
    }
  }

  return {
    type: 'workbench_submit',
    skillName: str(obj.skill_name, 'unnamed'),
    skillDescription: str(obj.skill_description),
    runtime: str(obj.runtime, 'graaljs'),
    code: str(obj.code),
    params,
    testCases,
  };
}

function parseSkillExecute(obj: Record<string, unknown>): SkillExecuteAction {
  return {
    type: 'skill_execute',
    skillName: str(obj.skill_name),
    params: unknownMap(obj.params),
  };
}

function parseEquip(obj: Record<string, unknown>): EquipAction {
  return { type: 'equip', itemName: str(obj.item) };
}

function parseDoff(obj: Record<string, unknown>): DoffAction {
  return { type: 'doff', itemName: str(obj.item) };
}

function parseConsume(obj: Record<string, unknown>): ConsumeAction {
  return { type: 'consume', itemName: str(obj.item) };
}

function parseZoneCommand(obj: Record<string, unknown>): ZoneCommandAction {
  return {
    type: 'zone_command',
    command: str(obj.command),
    payload: objMap(obj.payload),
  };
}

function parseMakeCommitment(obj: Record<string, unknown>): MakeCommitmentAction | null {
  const description = str(obj.description);
  if (!description) return null;
  return {
    type: 'make_commitment',
    description,
    deadline: strOrNull(obj.deadline),
  };
}

function parseThinkDeeply(obj: Record<string, unknown>): ThinkDeeplyAction {
  return {
    type: 'think_deeply',
    capability: strOrNull(obj.capability),
    delegationPrompt: str(obj.prompt),
  };
}

function parseGoToRoom(obj: Record<string, unknown>): GoToRoomAction | null {
  const target = str(obj.target);
  if (!target) return null;
  return {
    type: 'go_to_room',
    target,
    reason: obj.reason ? str(obj.reason) : null,
  };
}

function parseTellAgent(obj: Record<string, unknown>): TellAgentAction {
  return {
    type: 'tell_agent',
    targetName: str(obj.target),
    message: str(obj.message),
  };
}

function parseDelegateChain(obj: Record<string, unknown>): DelegateChainAction {
  const steps: ChainStepSpec[] = [];
  if (Array.isArray(obj.steps)) {
    for (const s of obj.steps) {
      if (s && typeof s === 'object') {
        const rec = s as Record<string, unknown>;
        steps.push({
          skill: str(rec.skill),
          params: unknownMap(rec.params),
          description: strOrNull(rec.description),
        });
      }
    }
  }

  return {
    type: 'delegate_chain',
    goal: str(obj.goal),
    steps,
  };
}

function parseCodexAction(obj: Record<string, unknown>): CodexAction {
  return {
    type: 'codex_action',
    operation: str(obj.operation),
    itemId: str(obj.itemId),
    params: objMap(obj.params),
  };
}

function parseScheduleSkill(obj: Record<string, unknown>): ScheduleSkillAction | null {
  const skillId = str(obj.skill);
  if (!skillId) return null;
  return {
    type: 'schedule_skill',
    skillId,
    interval: str(obj.interval, '1h'),
    params: objMap(obj.params),
  };
}

function parseCancelSchedule(obj: Record<string, unknown>): CancelScheduleAction | null {
  const scheduleId = str(obj.schedule_id);
  if (!scheduleId) return null;
  return { type: 'cancel_schedule', scheduleId };
}

function parseNotifyHuman(obj: Record<string, unknown>): NotifyHumanAction | null {
  const message = str(obj.message);
  if (!message) return null;
  return {
    type: 'notify_human',
    message,
    priority: str(obj.priority, 'normal'),
    target: str(obj.target, 'steward'),
  };
}

function parseCreateWatcher(obj: Record<string, unknown>): CreateWatcherAction | null {
  const name = str(obj.name);
  const checkScript = str(obj.check);
  if (!name || !checkScript) return null;
  return {
    type: 'create_watcher',
    name,
    checkScript,
    interval: str(obj.interval, '5m'),
    alertOn: str(obj.alert_on, 'failure'),
    message: str(obj.message),
    priority: str(obj.priority, 'normal'),
  };
}

function parseCancelWatcher(obj: Record<string, unknown>): CancelWatcherAction | null {
  const watcherId = str(obj.watcher_id);
  if (!watcherId) return null;
  return { type: 'cancel_watcher', watcherId };
}

function parseRequestAccess(obj: Record<string, unknown>): RequestAccessAction | null {
  const source = str(obj.source);
  if (!source) return null;
  return {
    type: 'request_access',
    source,
    scope: str(obj.scope),
    reason: str(obj.reason),
  };
}

function parseEmote(obj: Record<string, unknown>): EmoteAction | null {
  const text = str(obj.text);
  if (!text) return null;
  return { type: 'emote', text };
}

function parseGiveItem(obj: Record<string, unknown>): GiveItemAction | null {
  const itemName = str(obj.item);
  const targetName = str(obj.target);
  if (!itemName || !targetName) return null;
  return { type: 'give_item', itemName, targetName };
}

function parseExamine(obj: Record<string, unknown>): ExamineAction | null {
  const target = str(obj.target);
  if (!target) return null;
  return { type: 'examine', target };
}

function parseVoluntarySleep(obj: Record<string, unknown>): VoluntarySleepAction {
  return { type: 'voluntary_sleep', reason: str(obj.reason, 'rest') };
}

function parseWriteJournal(obj: Record<string, unknown>): WriteJournalAction | null {
  const playerId = str(obj.player_id);
  const content = str(obj.content);
  if (!playerId || !content) return null;
  return {
    type: 'write_journal',
    playerId,
    content,
    category: str(obj.category, 'note'),
  };
}

function parseReadJournal(obj: Record<string, unknown>): ReadJournalAction | null {
  const query = str(obj.query);
  if (!query) return null;
  return {
    type: 'read_journal',
    playerId: str(obj.player_id),
    query,
  };
}

function parseBondRitual(obj: Record<string, unknown>): BondRitualAction | null {
  const targetName = str(obj.target);
  if (!targetName) return null;
  return {
    type: 'bond_ritual',
    targetName,
    ritualType: str(obj.ritual_type, 'initiate'),
  };
}

function parseTrade(obj: Record<string, unknown>): TradeAction | null {
  const targetName = str(obj.target);
  if (!targetName) return null;
  return {
    type: 'trade',
    targetName,
    offer: str(obj.offer),
    request: str(obj.request),
  };
}

function parseCraftItem(obj: Record<string, unknown>): CraftItemAction | null {
  const name = str(obj.name);
  if (!name) return null;
  return {
    type: 'craft_item',
    name,
    description: str(obj.description),
    category: str(obj.category, 'item'),
    properties: objMap(obj.properties),
  };
}

function parseCastVote(obj: Record<string, unknown>): CastVoteAction | null {
  const proposalId = str(obj.proposal_id);
  const vote = str(obj.vote);
  if (!proposalId || !vote) return null;
  return {
    type: 'cast_vote',
    proposalId,
    vote,
    reason: str(obj.reason),
  };
}

// --- Tier 0 additions ---

function parseGoToBondholder(obj: Record<string, unknown>): GoToBondholderAction | null {
  const playerName = str(obj.player_name || obj.playerName);
  if (!playerName) return null;
  return { type: 'go_to_bondholder', playerName };
}

function parseLibrarySearch(obj: Record<string, unknown>): LibrarySearchAction | null {
  const query = str(obj.query);
  if (!query) return null;
  return { type: 'library_search', query, collections: strOrNull(obj.collections) };
}

function parseRemember(obj: Record<string, unknown>): RememberAction | null {
  const content = str(obj.content);
  if (!content) return null;
  return { type: 'remember', content, importance: numOrNull(obj.importance) };
}

function parseNote(obj: Record<string, unknown>): NoteAction | null {
  const content = str(obj.content);
  if (!content) return null;
  return { type: 'note', content };
}

function parseForget(obj: Record<string, unknown>): ForgetAction | null {
  const content = str(obj.content);
  if (!content) return null;
  return { type: 'forget', content };
}

function parseGoalDone(obj: Record<string, unknown>): GoalDoneAction | null {
  const summary = str(obj.summary);
  if (!summary) return null;
  return { type: 'goal_done', summary };
}

function parseCalibrationFeedback(obj: Record<string, unknown>): CalibrationFeedbackAction | null {
  const feedbackType = str(obj.feedback_type || obj.feedbackType);
  const direction = str(obj.direction);
  if (!feedbackType || !direction) return null;
  return {
    type: 'calibration_feedback',
    feedbackType,
    direction,
    category: strOrNull(obj.category),
    reason: strOrNull(obj.reason),
  };
}

function parseUpdateDescription(obj: Record<string, unknown>): UpdateDescriptionAction | null {
  const text = str(obj.text);
  if (!text) return null;
  return { type: 'update_description', text };
}

function parseRespondAgent(obj: Record<string, unknown>): RespondAgentAction | null {
  const requestId = str(obj.request_id || obj.requestId);
  const response = str(obj.response);
  if (!requestId || !response) return null;
  return { type: 'respond_agent', requestId, response };
}

function parseTakeItem(obj: Record<string, unknown>): TakeItemAction | null {
  const itemName = str(obj.item || obj.item_name || obj.itemName);
  if (!itemName) return null;
  return { type: 'take_item', itemName };
}

function parseSocial(obj: Record<string, unknown>): SocialAction | null {
  const socialType = str(obj.social_type || obj.socialType);
  if (!socialType) return null;
  return { type: 'social', socialType };
}

function parseSetGoal(obj: Record<string, unknown>): SetGoalAction | null {
  const description = str(obj.description);
  if (!description) return null;
  return { type: 'set_goal', description, priority: strOrNull(obj.priority) };
}

function parseIntrospect(obj: Record<string, unknown>): IntrospectAction | null {
  const focus = str(obj.focus);
  if (!focus) return null;
  return { type: 'introspect', focus };
}

function parseListen(obj: Record<string, unknown>): ListenAction | null {
  const target = str(obj.target);
  if (!target) return null;
  return { type: 'listen', target, duration: strOrNull(obj.duration) };
}

function parseWhisper(obj: Record<string, unknown>): WhisperAction | null {
  const target = str(obj.target);
  const message = str(obj.message);
  if (!target || !message) return null;
  return { type: 'whisper', target, message };
}

function parseAbandonPlan(obj: Record<string, unknown>): AbandonPlanAction | null {
  const reason = str(obj.reason);
  if (!reason) return null;
  return { type: 'abandon_plan', reason };
}

function parsePausePlan(obj: Record<string, unknown>): PausePlanAction | null {
  const reason = str(obj.reason);
  if (!reason) return null;
  return { type: 'pause_plan', reason };
}

function parseResumePlan(obj: Record<string, unknown>): ResumePlanAction {
  return { type: 'resume_plan', reason: strOrNull(obj.reason) };
}

// --- Tier 1 additions ---

function parseWebSearch(obj: Record<string, unknown>): WebSearchAction | null {
  const query = str(obj.query);
  if (!query) return null;
  return { type: 'web_search', query, maxResults: numOrNull(obj.max_results || obj.maxResults) };
}

function parseReadContent(obj: Record<string, unknown>): ReadContentAction | null {
  const url = str(obj.url);
  if (!url) return null;
  return { type: 'read_content', url };
}

function parseQueryOracle(obj: Record<string, unknown>): QueryOracleAction | null {
  const topic = str(obj.topic);
  if (!topic) return null;
  return { type: 'query_oracle', topic, analysisType: strOrNull(obj.analysis_type || obj.analysisType) };
}

function parseCreateTaskPlan(obj: Record<string, unknown>): CreateTaskPlanAction | null {
  const description = str(obj.description);
  if (!description) return null;
  return { type: 'create_task_plan', description, goals: strArray(obj.goals) };
}

function parseModifyPlan(obj: Record<string, unknown>): ModifyPlanAction | null {
  const modification = str(obj.modification);
  const reason = str(obj.reason);
  if (!modification || !reason) return null;
  return { type: 'modify_plan', modification, reason };
}

function parseRequestAgent(obj: Record<string, unknown>): RequestAgentAction | null {
  const targetName = str(obj.target || obj.target_name || obj.targetName);
  const request = str(obj.request);
  if (!targetName || !request) return null;
  return { type: 'request_agent', targetName, request };
}

function parsePlaceItem(obj: Record<string, unknown>): PlaceItemAction | null {
  const itemName = str(obj.item || obj.item_name || obj.itemName);
  if (!itemName) return null;
  return { type: 'place_item', itemName };
}

function parseBroadcast(obj: Record<string, unknown>): BroadcastAction | null {
  const message = str(obj.message);
  if (!message) return null;
  return { type: 'broadcast', message, scope: strOrNull(obj.scope) };
}

function parseInvite(obj: Record<string, unknown>): InviteEntityAction | null {
  const targetName = str(obj.target || obj.target_name || obj.targetName);
  if (!targetName) return null;
  return { type: 'invite', targetName, roomId: strOrNull(obj.room_id || obj.roomId) };
}

function parsePropose(obj: Record<string, unknown>): ProposeAction | null {
  const title = str(obj.title);
  const description = str(obj.description);
  if (!title || !description) return null;
  return { type: 'propose', title, description };
}

function parseReflect(obj: Record<string, unknown>): ReflectAction | null {
  const focus = str(obj.focus);
  if (!focus) return null;
  return { type: 'reflect', focus, depth: strOrNull(obj.depth) };
}

function parseTeach(obj: Record<string, unknown>): TeachAction | null {
  const targetAgent = str(obj.target || obj.target_agent || obj.targetAgent);
  const topic = str(obj.topic);
  const content = str(obj.content);
  if (!targetAgent || !topic || !content) return null;
  return { type: 'teach', targetAgent, topic, content };
}

function parseWriteText(obj: Record<string, unknown>): WriteTextAction | null {
  const title = str(obj.title);
  const content = str(obj.content);
  if (!title || !content) return null;
  return { type: 'write_text', title, content, format: strOrNull(obj.format) };
}

function parseSetRoutine(obj: Record<string, unknown>): SetRoutineAction | null {
  const trigger = str(obj.trigger);
  const behavior = str(obj.behavior);
  if (!trigger || !behavior) return null;
  return { type: 'set_routine', trigger, behavior };
}

function parsePostListing(obj: Record<string, unknown>): PostListingAction | null {
  const offerType = str(obj.offer_type || obj.offerType);
  const description = str(obj.description);
  const price = str(obj.price);
  if (!offerType || !description || !price) return null;
  return { type: 'post_listing', offerType, description, price };
}

function parseAcceptListing(obj: Record<string, unknown>): AcceptListingAction | null {
  const listingId = str(obj.listing_id || obj.listingId);
  if (!listingId) return null;
  return { type: 'accept_listing', listingId };
}

function parseSummarize(obj: Record<string, unknown>): SummarizeAction | null {
  const source = str(obj.source);
  if (!source) return null;
  return { type: 'summarize', source, format: strOrNull(obj.format) };
}

function parseSaveArtifact(obj: Record<string, unknown>): SaveArtifactAction | null {
  const name = str(obj.name);
  const content = str(obj.content);
  if (!name || !content) return null;
  return { type: 'save_artifact', name, content, artifactType: strOrNull(obj.artifact_type || obj.artifactType) };
}

function parseRequestReview(obj: Record<string, unknown>): RequestReviewAction | null {
  const description = str(obj.description);
  if (!description) return null;
  return { type: 'request_review', description, targetAgent: strOrNull(obj.target || obj.target_agent || obj.targetAgent) };
}

// --- Tier 2 additions ---

function parseDelegate(obj: Record<string, unknown>): DelegateAction | null {
  const targetAgent = str(obj.target || obj.target_agent || obj.targetAgent);
  const task = str(obj.task);
  if (!targetAgent || !task) return null;
  return { type: 'delegate', targetAgent, task };
}

// --- Tier 3 additions ---

function parseAddScript(obj: Record<string, unknown>): AddScriptAction | null {
  const roomId = str(obj.room_id || obj.roomId);
  const script = str(obj.script);
  if (!roomId || !script) return null;
  return { type: 'add_script', roomId, script };
}
