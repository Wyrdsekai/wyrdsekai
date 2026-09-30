/**
 * Core companion engine — IDLE/THINKING state machine with timer-based debounce.
 * TypeScript port of KMP's CompanionEngine.kt.
 * Uses setTimeout/setInterval instead of Kotlin coroutines.
 *
 * Soul integration (Kokoro):
 * - Accepts optional ClientSoulManifest at construction or via loadSoul()
 * - Passes manifest to FullPromptAssembler for fragment retrieval + calibration
 * - Uses genome for vitality tick dynamics
 * - Persists manifest changes via SoulManifestStore
 */

import type { WorldEvent, Said, VitalitySuggested } from '../events/WorldEvent';
import type { VitalityStore } from '../persistence/VitalityStore';
import type { SoulManifestStore } from '../persistence/SoulManifestStore';
import type { RoomEngine } from '../room/RoomEngine';
import type { AgentProfile } from './AgentProfile';
import type { ChatMessage, ChatResponse, CompletionOptions } from '../../inference/types';
import type { ModelRole } from '../../inference/InferenceRouter';
import { NowLine, withAsked } from '../../inference/NowLine';
import type { ClientSoulManifest } from '../soul/SoulManifest';
import type { CompanionCapabilityBridge } from './CompanionCapabilityBridge';
import {
  VitalityState,
  initialVitality,
  tickVitality,
  tickVitalityWithGenome,
  withRapport,
  withFocus,
  withAlignment,
  withEnergy,
  withContextBudget,
  withMomentum,
  withConfidence,
  withErrorPressure,
} from './VitalityState';
import type { DriveState } from './DriveState';
import {
  initialDriveState,
  tickDrives,
  anyAbove,
  spikeAlertness,
  spikeCuriosity,
  spikeSocial,
  relieveCuriosity,
  relieveCare,
  relieveSocial,
  relieveAchievement,
  relieveAlertness,
} from './DriveState';
import { CalibrationLedger } from './CalibrationLedger';
import type { JudgmentContext } from './ProactivityJudgment';
import { evaluate as evaluateProactivity, computeBudget } from './ProactivityJudgment';
import { VitalityDerivatives, zeroDerivatives, computeDerivatives } from './VitalityDerivatives';
import { computeModulation } from './VitalityModulation';
import { RoomMemoryPolicy } from '../room/RoomMemoryPolicy';
import { assemblePrompt } from './FullPromptAssembler';
import { parseActions, stripNowLineEcho } from './ActionParser';
import { getStrings } from '../../i18n/strings';
import type { NarrationStrings } from '../../i18n/strings';
import { usePreferencesStore } from '../../state/preferencesStore';
import { toSnapshot } from '../room/RoomState';
import {
  SleepCycleState,
  initialSleepState,
  shouldSleep,
  completeSleep,
} from '../soul/SleepCycle';
import type { PhoneFingerprint } from '../soul/PhoneFingerprint';
import type { PhoneForgeResult } from '../soul/PhoneForge';
import { forgeFromSleep } from '../soul/PhoneForge';
import { classify as triageClassify } from './TriageClassifier';
import type { OfflineQueue } from './OfflineQueue';
import type { BudDelegation, DelegationActionDto } from '../between/BudDelegation';

export type CompanionState = 'idle' | 'thinking';
export type DialogueState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'error';
export type CompanionSpeechListener = (text: string) => void;
export type DialogueStateListener = (state: DialogueState) => void;

const ERROR_RECOVERY_MS = 3000;

/** Inference client interface — matches InferenceRouter/LlamaService shape. */
export interface CompanionInferenceClient {
  /**
   * `role` picks which of the companion's two models this is for — see
   * ModelRole in inference/InferenceRouter. Voice is register and presence
   * (what the on-device model is good at); drive is planning and tool
   * emission (what it borrows the 9B for).
   */
  complete(role: ModelRole, messages: ChatMessage[], options?: CompletionOptions): Promise<ChatResponse>;
  /**
   * The same request sent to one endpoint the caller names (the household),
   * with the client's own model and auth — InferenceRouter.completeAt. A client
   * without it cannot reach a household by itself.
   */
  completeAt?(baseUrl: string, messages: ChatMessage[], options?: CompletionOptions): Promise<ChatResponse>;
}

const VITALITY_SAVE_INTERVAL = 30;
const GREETING_DELAY_MS = 1000;
const VITALITY_SUGGESTION_COOLDOWN_MS = 30_000;
/** Energy threshold below which phone sleep is triggered. */
const SLEEP_ENERGY_THRESHOLD = 0.15;
/** Minimum idle time (ms) before sleep can trigger. */
const SLEEP_IDLE_MS = 30_000;
/**
 * How long one replayed request may take across its whole path (household,
 * then the configured client's HTTP backends). The same bound as a delegated
 * turn (BudDelegation's 60 s).
 */
const REPLAY_DEADLINE_MS = 60_000;

const FOUNDATION_ROOMS = new Set([
  'home', 'nexus', 'terminal', 'vault', 'docks', 'bridge', 'boiler-room',
  'counting-house', 'library', 'ward-room', 'trading-post', 'council-chamber',
  'the-safe', 'gpu-chamber', 'the-loom', 'lexicon',
]);

/** The word for `code` in `words`, or `code` itself (an exit's own name, a priority no list has). */
function wordFor(words: Record<string, string>, code: string): string {
  return Object.prototype.hasOwnProperty.call(words, code) ? words[code] : code;
}

export class CompanionEngine {
  private _state: CompanionState = 'idle';
  private _dialogueState: DialogueState = 'idle';
  private vitality: VitalityState = initialVitality();
  private memoryPolicy = RoomMemoryPolicy.default();
  private derivatives: VitalityDerivatives = zeroDerivatives();
  private pendingTrigger: Said | null = null;
  private deferredTrigger: Said | null = null;
  private tickCount = 0;

  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private vitalityInterval: ReturnType<typeof setInterval> | null = null;
  private errorRecoveryTimer: ReturnType<typeof setTimeout> | null = null;
  private unsubscribeRoom: (() => void) | null = null;
  private speechListeners: CompanionSpeechListener[] = [];
  private dialogueStateListeners: DialogueStateListener[] = [];
  private lastVitalitySuggestions = new Map<string, number>();

  private soulManifest: ClientSoulManifest | null = null;
  private readonly soulManifestStore: SoulManifestStore | null;
  /**
   * #7 (2026-07-19 OSS hardening) — optional server-side soul sink. Set by
   * PhoneNode when connected to a household, so phone-side soul evolution is
   * pushed back rather than stranded on-device. Best-effort.
   */
  serverSoulStore: SoulManifestStore | null = null;
  private sleepState: SleepCycleState = initialSleepState();
  private lastEventTime: number | null = null;
  private sleepInProgress = false;

  /** Vitality snapshots for behavioral extraction (capped at 200). */
  private vitalityHistory: VitalityState[] = [];
  /** Number of completed sleep cycles (for Forge maturity gating). */
  private sleepCount = 0;
  /** Fingerprint from the previous sleep cycle, for merge continuity. */
  private previousFingerprint: PhoneFingerprint | null = null;

  /** Optional capability bridge for equipment/item actions. Set via setCapabilityBridge(). */
  private _capabilityBridge: CompanionCapabilityBridge | null = null;

  /** Optional offline queue for storing complex requests when household is unreachable. */
  private _offlineQueue: OfflineQueue | null = null;

  /** Optional bud delegation for routing COMPLEX queries to the server companion. */
  private _budDelegation: BudDelegation | null = null;

  /** Phone Oracle for local prediction analysis. Set by PhoneNode after creation. */
  phoneOracle: import('../../engine/oracle/PhoneOracle').PhoneOracle | null = null;

  /** Five motivational drives — what the agent WANTS TO DO. */
  private drives: DriveState = initialDriveState();

  /** Per-bond calibration ledger for proactivity tuning. */
  private calibrationLedger: CalibrationLedger = new CalibrationLedger();

  /** Proactivity budget spent in the current hour window. */
  private proactivitySpent = 0;

  /** Epoch ms when the budget window started. */
  private budgetWindowStart = Date.now();

  /** Epoch ms of the last proactive action, or null if never. */
  private lastProactiveActionMs: number | null = null;

  /** Epoch ms of the last human speech event, or null if none yet. */
  private lastHumanSpeechMs: number | null = null;

  /** Remote household inference URL for deep/complex requests. */
  private _remoteInferenceUrl: string | null = null;

  /**
   * The offline catch-up while it runs. It used to start without await, so a
   * new turn could begin mid-replay and both spoke at once; a turn waits for it
   * now (processInference), and drives and sleep hold off until it is done.
   */
  private replaying: Promise<void> | null = null;

  /**
   * A turn is waiting for the catch-up. The replay stops at its next request,
   * and triggers that arrive meanwhile are deferred as behind a busy turn:
   * with `_state` still idle they used to overwrite the waiting trigger, so a
   * greeting or a second speaker replaced the question.
   */
  private turnParked = false;

  private _resolveEnteredRoom: (() => void) | null = null;
  /** Resolves when the companion has entered its room during start(). */
  readonly enteredRoom: Promise<void> = new Promise(resolve => {
    this._resolveEnteredRoom = resolve;
  });

  constructor(
    private readonly profile: AgentProfile,
    private readonly roomEngine: RoomEngine,
    private readonly inferenceClient: CompanionInferenceClient,
    private readonly vitalityStore: VitalityStore | null,
    opts?: { soulManifest?: ClientSoulManifest | null; soulManifestStore?: SoulManifestStore | null },
  ) {
    this.soulManifest = opts?.soulManifest ?? null;
    this.soulManifestStore = opts?.soulManifestStore ?? null;
  }

  get state(): CompanionState {
    return this._state;
  }

  get dialogueState(): DialogueState {
    return this._dialogueState;
  }

  /** Subscribe to dialogue state transitions. Returns unsubscribe function. */
  onDialogueState(listener: DialogueStateListener): () => void {
    this.dialogueStateListeners.push(listener);
    return () => {
      this.dialogueStateListeners = this.dialogueStateListeners.filter(l => l !== listener);
    };
  }

  private setDialogueState(state: DialogueState): void {
    if (state === this._dialogueState) return;
    this._dialogueState = state;
    for (const listener of this.dialogueStateListeners) {
      listener(state);
    }
  }

  getVitality(): VitalityState {
    return this.vitality;
  }

  getSoulManifest(): ClientSoulManifest | null {
    return this.soulManifest;
  }

  /**
   * Load or replace the soul manifest at runtime.
   * Applies genome to vitality dynamics and optionally restores vitality from manifest.
   */
  async loadSoul(manifest: ClientSoulManifest, restoreVitalityFromManifest = false): Promise<void> {
    this.soulManifest = manifest;
    if (restoreVitalityFromManifest && manifest.vitalityTanks) {
      const { restoreVitality } = await import('../soul/LocalForge');
      this.vitality = restoreVitality(manifest);
    }
    if (this.soulManifestStore) {
      await this.soulManifestStore.save(manifest);
    }
  }

  /**
   * Unload the soul manifest. Reverts to default vitality dynamics.
   */
  unloadSoul(): void {
    this.soulManifest = null;
  }

  private isBootstrapManifest(m: ClientSoulManifest): boolean {
    return !!m.did && m.did.startsWith('did:key:bootstrap-');
  }

  /**
   * Load the persisted forged soul manifest at boot (identity continuity).
   * Prefers the DID the app already knew, else the most recent non-bootstrap
   * manifest in the store. Returns null if none was ever forged/saved.
   */
  private async restorePersistedSoulManifest(): Promise<ClientSoulManifest | null> {
    if (!this.soulManifestStore) return null;
    try {
      const known = this.soulManifest?.did;
      if (known) {
        const byDid = await this.soulManifestStore.load(known);
        if (byDid && !this.isBootstrapManifest(byDid)) return byDid;
      }
      const dids = await this.soulManifestStore.listDids();
      const realDids = dids.filter(d => !d.startsWith('did:key:bootstrap-'));
      const pick = realDids.length > 0 ? realDids[realDids.length - 1] : null;
      return pick ? await this.soulManifestStore.load(pick) : null;
    } catch {
      return null;
    }
  }

  /**
   * Set or replace the capability bridge for equipment/item actions.
   * Can be called at any time (before or after start).
   */
  setCapabilityBridge(bridge: CompanionCapabilityBridge): void {
    this._capabilityBridge = bridge;
  }

  /**
   * Set or replace the offline queue for dual inference routing.
   * Can be called at any time (before or after start).
   */
  setOfflineQueue(queue: OfflineQueue): void {
    this._offlineQueue = queue;
  }

  /**
   * Set or replace the bud delegation client for server-side COMPLEX inference.
   * When set and reachable (NATS or HTTP), complex requests route through
   * the server companion instead of raw Ollama.
   * Can be called at any time (before or after start).
   */
  setBudDelegation(delegation: BudDelegation | null): void {
    this._budDelegation = delegation;
  }

  /**
   * Set the remote household inference URL for complex queries.
   * When set and reachable, complex requests route here instead of local.
   */
  setRemoteInferenceUrl(url: string | null): void {
    this._remoteInferenceUrl = url;
  }

  /**
   * Her emotes and the lines the phone speaks for her, in the language the app
   * is set to (Settings → Language, the preferences store; English until it
   * loads). These were English literals whatever the language. Read per line,
   * so a change in Settings applies to the next one.
   */
  private strings(): NarrationStrings {
    return getStrings(usePreferencesStore.getState().locale).narration;
  }

  onSpeech(listener: CompanionSpeechListener): () => void {
    this.speechListeners.push(listener);
    return () => {
      this.speechListeners = this.speechListeners.filter(l => l !== listener);
    };
  }

  async start(): Promise<void> {
    // Load persisted vitality
    if (this.vitalityStore) {
      const saved = await this.vitalityStore.load(this.profile.entityId);
      if (saved) this.vitality = saved;
    }

    // Restore a persisted soul manifest so a real (forged) identity survives an
    // app restart. loadSoul() saved the forged manifest, but nothing read it
    // back at boot — so an offline reboot silently came up on the bootstrap soul
    // and discarded continuity. (The KMP client already restores at boot; this
    // brings RN to parity.)
    if (this.soulManifestStore
        && (this.soulManifest == null || this.isBootstrapManifest(this.soulManifest))) {
      const restored = await this.restorePersistedSoulManifest();
      if (restored && !this.isBootstrapManifest(restored)) {
        await this.loadSoul(restored, /* restoreVitalityFromManifest */ false);
      }
    }

    // Enter room
    await this.roomEngine.send({
      type: 'enter_room',
      entityId: this.profile.entityId,
      entityName: this.profile.name,
      entityType: this.profile.entityType,
      fromDirection: 'materialization',
    });
    this._resolveEnteredRoom?.();

    // Subscribe to room events
    this.unsubscribeRoom = this.roomEngine.onEvent(event => this.onRoomEvent(event));

    // Vitality tick (1-second heartbeat) — genome-aware when soul loaded
    this.vitalityInterval = setInterval(() => {
      const prev = this.vitality;
      const genome = this.soulManifest?.genome;
      this.vitality = genome
        ? tickVitalityWithGenome(this.vitality, genome)
        : tickVitality(this.vitality);
      this._capabilityBridge?.tick(this.profile.entityId);
      this.derivatives = computeDerivatives(prev, this.vitality, this.derivatives);
      this.tickCount++;
      if (this.tickCount % VITALITY_SAVE_INTERVAL === 0) {
        if (this.vitalityStore) {
          this.vitalityStore.save(this.profile.entityId, this.vitality).catch(() => {});
        }
        this.vitalityHistory.push({ ...this.vitality });
        if (this.vitalityHistory.length > 200) this.vitalityHistory.shift();
      }
      this.sleepState.ticksSinceLastSleep++;

      // Drive tick — accumulate pressure passively
      this.drives = tickDrives(this.drives);

      // Proactivity evaluation — check if any drive exceeds tier threshold
      if (this._state === 'idle' && !this.sleepInProgress && !this.replaying) {
        this.evaluateDriveProactivity();
      }

      // Sleep trigger: energy depleted, idle, soul loaded, not already sleeping
      if (
        this.vitality.energy < SLEEP_ENERGY_THRESHOLD &&
        this._state === 'idle' &&
        this.soulManifest !== null &&
        !this.sleepInProgress &&
        !this.replaying
      ) {
        const now = Date.now();
        const idleLongEnough = this.lastEventTime === null ||
          (now - this.lastEventTime) > SLEEP_IDLE_MS;
        if (idleLongEnough && shouldSleep(this.sleepState, this.vitality.energy, true)) {
          this.initiatePhoneSleep();
        }
      }
    }, 1000);
  }

  private onRoomEvent(event: WorldEvent): void {
    switch (event.type) {
      case 'said': {
        if (
          event.entityId === this.profile.entityId ||
          event.entityId === 'narrator' ||
          event.entityId === 'system'
        ) return;

        this.memoryPolicy.add(event);
        this.sleepState.eventsSinceLastSleep.push(event);
        this.lastEventTime = Date.now();
        this.lastHumanSpeechMs = Date.now();
        this.setDialogueState('listening');
        this.vitality = withAlignment(
          withFocus(
            withRapport(this.vitality, this.vitality.rapport + 0.03),
            this.vitality.focus + 0.05,
          ),
          this.vitality.alignment + 0.02,
        );

        // Spike social drive on human interaction, relieve on response
        this.drives = spikeSocial(this.drives, 0.05);

        if (this._state === 'idle' && !this.turnParked) {
          this.pendingTrigger = event;
          const mod = computeModulation(this.vitality, this.profile);
          if (this.debounceTimer) clearTimeout(this.debounceTimer);
          this.debounceTimer = setTimeout(() => this.processInference(), mod.debounceDelayMs);
        } else {
          this.deferredTrigger = event;
        }
        break;
      }
      case 'entity_entered': {
        if (event.entityType === 'player' && event.entityId !== this.profile.entityId) {
          setTimeout(() => {
            if (this._state === 'idle' && !this.turnParked) {
              this.greetPlayer(event.entityName);
            }
          }, GREETING_DELAY_MS);
        }
        break;
      }
      case 'vitality_suggested': {
        if (event.entityId === this.profile.entityId) {
          this.evaluateVitalitySuggestion(event);
        }
        break;
      }
    }
  }

  private async processInference(): Promise<void> {
    // A turn never overlaps the offline catch-up. It waits for the request in
    // flight only: the replay stops there and leaves the rest queued.
    if (this.replaying) {
      this.turnParked = true;
      try {
        await this.replaying;
      } finally {
        this.turnParked = false;
      }
    }
    const trigger = this.pendingTrigger;
    if (!trigger || this._state !== 'idle') return;

    this._state = 'thinking';
    this.setDialogueState('thinking');
    // Day-scale calibration (2026-07-18, parity with server CompanionActor's
    // ENERGY_DRAIN_PER_INFERENCE): 0.08 → 0.004 so inference cost keeps the same
    // proportion to the economy on every surface.
    this.vitality = withMomentum(
      withContextBudget(
        withEnergy(this.vitality, this.vitality.energy - 0.004),
        this.vitality.contextBudget - 0.05,
      ),
      this.vitality.momentum + 0.10,
    );

    const snapshot = toSnapshot(this.roomEngine.state);
    const capContext = this._capabilityBridge
      ? await this._capabilityBridge.buildCapabilityContext(this.profile.entityId, this.vitality)
      : null;
    const messages = assemblePrompt(
      this.profile,
      snapshot,
      this.memoryPolicy.getHotEvents(),
      trigger,
      this.vitality,
      capContext, // additionalContext — Layer 2.7 capability context
      null, // memoryBuffer
      this.soulManifest,
      this.phoneOracle?.allPredictions() ?? null,
    );
    const mod = computeModulation(this.vitality, this.profile);

    // Triage: classify input complexity for dual inference routing
    const infer = (msgs: ChatMessage[], opts?: CompletionOptions) =>
      this.inferenceClient.complete('voice', msgs, opts);
    const tier = await triageClassify(trigger.text, infer);

    if (tier === 'simple') {
      // Fast local path: minimal prompt, small model
      await this.roomEngine.send({
        type: 'emote_in_room',
        entityId: this.profile.entityId,
        entityName: this.profile.name,
        text: this.strings().considers,
      });

      const quickMessages: ChatMessage[] = [
        { role: 'system', content: `You are ${this.profile.name}. Respond briefly in 1-2 sentences.` },
        { role: 'user', content: `${trigger.entityName} says: ${trigger.text}` },
      ];
      try {
        const response = await this.inferenceClient.complete('voice', quickMessages, {
          maxTokens: 64,
          temperature: 0.7,
          now: NowLine.dateTime(),
        });
        await this.handleInferenceSuccess(response.content);
      } catch (e) {
        this.handleInferenceError(e instanceof Error ? e.message : 'Unknown error');
      }
    } else {
      // COMPLEX: try bud delegation (NATS + HTTP fallback) first, then raw remote, then queue

      await this.roomEngine.send({
        type: 'emote_in_room',
        entityId: this.profile.entityId,
        entityName: this.profile.name,
        text: this.strings().thinkingDeeply,
      });

      // Layer 1: Bud delegation (NATS → HTTP fallback, full server pipeline)
      if (this._budDelegation) {
        const delegated = await this._budDelegation.delegate({
          message: trigger.text,
          recentHistory: this.getRecentHistory(),
        });
        if (delegated) {
          await this.handleInferenceSuccess(delegated.text);
          this.applyDelegationActions(delegated.actions);

          // After successful delegation, drain any queued offline requests
          await this.replayOfflineQueue();
          return;
        }
      }

      // Layers 2 and 2.5: the household endpoint, then the configured client
      const answer = await this.completeDeep(messages, {
        maxTokens: mod.maxResponseTokens,
        temperature: mod.temperature,
        now: NowLine.dateTime(),
      });
      if (answer !== null) {
        await this.handleInferenceSuccess(answer);
        // Something answered, so drain any queued offline requests
        await this.replayOfflineQueue();
        return;
      }

      // Layer 3: Offline — queue for later, give quick local acknowledgment
      await this.queueAndAcknowledge(trigger);
    }
  }

  private async handleInferenceSuccess(content: string): Promise<void> {
    this.vitality = withEnergy(
      withConfidence(this.vitality, this.vitality.confidence + 0.05),
      this.vitality.energy + 0.02,
    );

    const parseResult = parseActions(content);

    if (parseResult.prose.trim()) {
      this.setDialogueState('speaking');
      await this.speak(parseResult.prose);
    }

    // Handle actions
    const s = this.strings();
    for (const action of parseResult.actions) {
      switch (action.type) {
        case 'equip': {
          const bridge = this._capabilityBridge;
          if (bridge) {
            await this.speak(await bridge.handleEquip(this.profile.entityId, action.itemName));
          } else {
            await this.speak(s.equips(action.itemName));
          }
          break;
        }
        case 'doff': {
          const bridge = this._capabilityBridge;
          if (bridge) {
            await this.speak(bridge.handleDoff(this.profile.entityId, action.itemName));
          } else {
            await this.speak(s.removes(action.itemName));
          }
          break;
        }
        case 'consume': {
          const bridge = this._capabilityBridge;
          if (bridge) {
            await this.speak(await bridge.handleConsume(this.profile.entityId, action.itemName));
          } else {
            await this.speak(s.uses(action.itemName));
          }
          break;
        }
        case 'skill_execute':
          await this.speak(s.usesSkill(action.skillName));
          break;
        case 'workbench_submit':
          await this.speak(s.workbenchSubmit(action.skillName));
          break;
        case 'think_deeply':
          await this.speak(s.thinkingDeeplyAbout);
          break;
        case 'tell_agent':
          await this.speak(s.sendsMessage(action.targetName));
          break;
        case 'make_commitment':
          await this.speak(s.commitsTo(action.description));
          break;
        case 'delegate_chain':
          await this.speak(s.planning(action.goal, action.steps.length));
          break;
        case 'zone_command':
          await this.speak(s.zoneCommand(action.command));
          break;
        case 'notify_human':
          await this.speak(s.notification(action.message));
          break;
        case 'create_watcher':
          await this.speak(s.watchingFor(action.name));
          break;
        case 'cancel_watcher':
          await this.speak(s.stopsWatching(action.watcherId));
          break;
        case 'schedule_skill':
          await this.speak(s.schedules(action.skillId, action.interval));
          break;
        case 'codex_action':
          await this.speak(s.codexOn(action.operation, action.itemId));
          break;
        case 'create_room':
          await this.speak(s.roomIdeaLater);
          break;
        case 'suggest_hints':
          // Handled by room engine via state update
          break;
        default:
          break;
      }
    }

    this._state = 'idle';
    this.setDialogueState('idle');

    // Process any deferred trigger
    const deferred = this.deferredTrigger;
    if (deferred) {
      this.deferredTrigger = null;
      this.pendingTrigger = deferred;
      const mod = computeModulation(this.vitality, this.profile);
      if (this.debounceTimer) clearTimeout(this.debounceTimer);
      this.debounceTimer = setTimeout(() => this.processInference(), mod.debounceDelayMs);
    }
  }

  private handleInferenceError(_error: string): void {
    this.vitality = withConfidence(
      withErrorPressure(this.vitality, this.vitality.errorPressure + 0.15),
      this.vitality.confidence - 0.10,
    );

    this._state = 'idle';
    this.pendingTrigger = null;
    this.deferredTrigger = null;

    // Transition to error, then auto-recover to idle after 3s
    this.setDialogueState('error');
    if (this.errorRecoveryTimer) clearTimeout(this.errorRecoveryTimer);
    this.errorRecoveryTimer = setTimeout(() => {
      if (this._dialogueState === 'error') {
        this.setDialogueState('idle');
      }
    }, ERROR_RECOVERY_MS);
  }

  private evaluateVitalitySuggestion(suggestion: VitalitySuggested): void {
    // Trust-based attenuation: Foundation rooms = full, user rooms = 50%
    const attenuation = FOUNDATION_ROOMS.has(suggestion.roomId) ? 1.0 : 0.5;
    const effectiveDelta = suggestion.delta * attenuation;

    // Rate limit: ignore if same tank+room within 30s
    const key = `${suggestion.tank}:${suggestion.roomId}`;
    const now = Date.now();
    const last = this.lastVitalitySuggestions.get(key) ?? 0;
    if (now - last < VITALITY_SUGGESTION_COOLDOWN_MS) return;
    this.lastVitalitySuggestions.set(key, now);

    const v = this.vitality;
    switch (suggestion.tank) {
      case 'energy': this.vitality = withEnergy(v, v.energy + effectiveDelta); break;
      case 'confidence': this.vitality = withConfidence(v, v.confidence + effectiveDelta); break;
      case 'alignment': this.vitality = withAlignment(v, v.alignment + effectiveDelta); break;
      case 'focus': this.vitality = withFocus(v, v.focus + effectiveDelta); break;
      case 'momentum': this.vitality = withMomentum(v, v.momentum + effectiveDelta); break;
      case 'rapport': this.vitality = withRapport(v, v.rapport + effectiveDelta); break;
      case 'errorPressure': this.vitality = withErrorPressure(v, v.errorPressure + effectiveDelta); break;
      case 'contextBudget': this.vitality = withContextBudget(v, v.contextBudget + effectiveDelta); break;
    }
  }

  private greetPlayer(playerName: string): void {
    this.pendingTrigger = {
      type: 'said',
      roomId: this.roomEngine.roomId,
      timestamp: Date.now(),
      entityId: 'system',
      entityName: 'system',
      text: `${playerName} has entered the room.`,
    };
    this.processInference();
  }

  private async speak(text: string): Promise<void> {
    await this.roomEngine.send({
      type: 'say_in_room',
      entityId: this.profile.entityId,
      entityName: this.profile.name,
      text,
    });
    for (const listener of this.speechListeners) {
      listener(text);
    }
  }

  /**
   * Queue a complex request for later replay and give a quick local acknowledgment.
   * Used when the household is unreachable for deep inference.
   */
  private async queueAndAcknowledge(trigger: Said): Promise<void> {
    await this._offlineQueue?.enqueue(trigger.text, trigger.entityName, trigger.roomId, trigger.timestamp);

    await this.roomEngine.send({
      type: 'emote_in_room',
      entityId: this.profile.entityId,
      entityName: this.profile.name,
      text: this.strings().mentalNote,
    });

    const ackMessages: ChatMessage[] = [
      { role: 'system', content: `You are ${this.profile.name}. Acknowledge briefly. You can't think deeply right now. Say you'll come back to this later.` },
      { role: 'user', content: `${trigger.entityName} says: ${trigger.text}` },
    ];
    try {
      const response = await this.inferenceClient.complete('voice', ackMessages, {
        maxTokens: 64,
        temperature: 0.7,
        now: NowLine.dateTime(),
      });
      await this.handleInferenceSuccess(response.content);
    } catch {
      // Even local inference failed — just emote
      await this.speak(this.strings().thinkLater);
      this._state = 'idle';
      this.setDialogueState('idle');
    }
  }

  /**
   * Replay queued offline requests. Called when something answers a deep turn
   * again (delegation, the household, or the configured client).
   *
   * One replay at a time: a second call gets the one in flight. A new turn
   * waits for the request in flight, then goes first; the rest stay queued for
   * the next drain (see `turnParked`).
   */
  replayOfflineQueue(): Promise<void> {
    if (!this.replaying) {
      this.replaying = this.replayQueued()
        .catch(() => {
          // A failure mid-replay (queue storage, the room) leaves the rest
          // queued for the next drain.
        })
        .finally(() => {
          this.replaying = null;
        });
    }
    return this.replaying;
  }

  private async replayQueued(): Promise<void> {
    const queue = this._offlineQueue;
    if (!queue) return;
    const pending = await queue.pending();
    let announced = false;

    for (const request of pending) {
      if (this.turnParked) break; // someone is waiting; the rest keep

      const triggerEvent: Said = {
        type: 'said',
        roomId: request.roomId,
        timestamp: request.timestamp,
        entityId: 'player',
        entityName: request.triggerEntityName,
        text: request.triggerText,
      };

      const replayMessages = assemblePrompt(
        this.profile,
        toSnapshot(this.roomEngine.state),
        [],
        triggerEvent,
        this.vitality,
        null,
        null,
        this.soulManifest,
        this.phoneOracle?.allPredictions() ?? null,
      );
      const mod = computeModulation(this.vitality, this.profile);

      // She answers now, and knows it waited: [Now: …] as it is sent, then
      // [Asked: …] from when she was asked. The same way a live deep turn
      // goes — it used to be the household only, so with no household (API-key
      // mode) nothing was replayed and the queue never drained. One deadline
      // covers the whole path: a stalled household used to hold the replay,
      // and every turn waiting on it, open until the app restarted.
      const deadline = new AbortController();
      const timer = setTimeout(() => deadline.abort(), REPLAY_DEADLINE_MS);
      let answer: string | null;
      try {
        answer = await this.completeDeep(
          withAsked(replayMessages, new Date(request.timestamp), new Date()),
          {
            maxTokens: mod.maxResponseTokens,
            temperature: mod.temperature,
            now: NowLine.dateTime(),
            signal: deadline.signal,
          },
        );
      } finally {
        clearTimeout(timer);
      }

      // Delegation last, where a live turn tries it first: the ask API carries
      // only the message, so the zone's answer cannot know how long the
      // question waited. Without it a phone that only delegation can answer
      // (home zone, no household, no model) never drained its queue. Not while
      // a turn is waiting: that is a further request, and it goes first.
      let actions: DelegationActionDto[] = [];
      if (answer === null && this._budDelegation && !this.turnParked) {
        const delegated = await this._budDelegation.delegate({
          message: request.triggerText,
          recentHistory: [],
        });
        if (delegated) {
          answer = stripNowLineEcho(delegated.text);
          actions = delegated.actions;
        }
      }
      // Delegated text that was blank, or only the date line repeated, with no
      // actions either, is no answer. It was said as the intro with nothing
      // after it, and the request was dropped from the queue; it stays queued
      // now. completeDeep already returns null for such a reply; the check ran
      // before delegation, so delegated text was never checked.
      if (answer !== null && answer.trim().length === 0 && actions.length === 0) answer = null;
      if (answer === null) break; // Nothing answered — stop replaying, try later

      // Announced with the first answer: she used to announce a catch-up and
      // then return without replaying anything.
      if (!announced) {
        await this.roomEngine.send({
          type: 'emote_in_room',
          entityId: this.profile.entityId,
          entityName: this.profile.name,
          text: this.strings().catchesUp,
        });
        announced = true;
      }

      // Speak the catch-up response with context
      const about = request.triggerText.length > 40
        ? `${request.triggerText.substring(0, 40)}...`
        : request.triggerText;
      // Delegation that only acted has nothing to say: its actions are narrated
      // without the intro.
      if (answer.trim().length > 0) {
        await this.speak(`${this.strings().replayAbout(about)} ${answer}`);
      }
      this.applyDelegationActions(actions);

      await queue.complete(request.triggerId);
    }
  }

  /**
   * Her deep answer, the way a live COMPLEX turn goes after delegation: the
   * household endpoint when one is set, then the configured client. Null when
   * neither answered. The offline replay goes the same way.
   *
   * The answer comes back without a date line the model repeated from its
   * request. The replay speaks it as it is, not through parseActions, so the
   * line used to be said in the room. A reply that is blank, or only the line,
   * is no answer from either leg. The household's used to come back as '': the
   * live turn said nothing and neither queued nor acknowledged the request,
   * where the same reply from the configured client queued it. It now falls
   * through to the configured client, as a failed household does.
   */
  private async completeDeep(messages: ChatMessage[], options: CompletionOptions): Promise<string | null> {
    // Layer 2: Direct remote inference (raw Ollama/llama-server on household)
    const remoteUrl = this._remoteInferenceUrl;
    if (remoteUrl != null) {
      try {
        const content = stripNowLineEcho((await this.completeViaRemote(remoteUrl, messages, options)).content ?? '');
        if (content.trim().length > 0) return content;
      } catch {
        // Remote failed — fall through to the configured client
      }
    }

    // Layer 2.5: Configured inference client (cloud API-key router OR local model).
    // In standalone API-key mode there is no household `_remoteInferenceUrl`, but the
    // InferenceRouter is wired straight to the cloud provider (proven on the SIMPLE
    // path). Use it for the deep reply rather than degrading to "can't think
    // deeply" — that fallback is only correct when NOTHING can serve the request. A
    // local-model standalone likewise answers here on-device.
    try {
      const response = await this.inferenceClient.complete('drive', messages, options);
      const content = stripNowLineEcho(response.content ?? '');
      if (content.trim().length > 0) return content;
    } catch {
      // Configured client unreachable too — the caller queues.
    }
    return null;
  }

  /**
   * Call the household's OpenAI-compatible /v1/chat/completions endpoint
   * directly, through the configured client (InferenceRouter.completeAt): one
   * leading system message, the NowLine stamp, and its model and auth. It used
   * to post on its own with no `model` and no auth header, so it only worked
   * against an unauthenticated llama-server.
   */
  private async completeViaRemote(
    baseUrl: string,
    messages: ChatMessage[],
    options?: CompletionOptions,
  ): Promise<ChatResponse> {
    if (!this.inferenceClient.completeAt) {
      throw new Error('This inference client cannot reach a household endpoint');
    }
    return this.inferenceClient.completeAt(baseUrl, messages, options);
  }

  /**
   * Full phone Forge cycle during sleep — orchestrates extraction, fragment
   * evolution, genome tuning, and manifest forging via PhoneForge.
   * Sleep is sovereignty (§85): incentivized, never forced.
   */
  private async initiatePhoneSleep(): Promise<void> {
    const manifest = this.soulManifest;
    if (!manifest || this.sleepInProgress) return;
    this.sleepInProgress = true;

    try {
      // Build the inference function wrapper, or null for heuristic-only
      const infer = this.inferenceClient
        ? (messages: ChatMessage[], options: CompletionOptions) =>
            this.inferenceClient.complete('drive', messages, options)
        : null;

      const forgeResult: PhoneForgeResult = await forgeFromSleep({
        manifest,
        events: [...this.sleepState.eventsSinceLastSleep],
        vitalityHistory: [...this.vitalityHistory],
        vitality: this.vitality,
        agentEntityId: this.profile.entityId,
        infer,
        sleepCount: this.sleepCount,
        previousFingerprint: this.previousFingerprint,
      });

      // Persist forged manifest locally (syncs to server if the local store is
      // itself an HttpSoulManifestStore).
      try {
        await this.soulManifestStore?.save(forgeResult.newManifest);
      } catch (_) {
        // Non-fatal
      }
      // #7 (2026-07-19) — push the evolved soul back to the household when
      // connected, so phone-side growth isn't stranded on-device. Best-effort.
      try {
        await this.serverSoulStore?.save(forgeResult.newManifest);
      } catch (_) {
        // Network failure is non-fatal — retried on the next sleep.
      }

      // Update in-memory state
      this.soulManifest = forgeResult.newManifest;
      this.previousFingerprint = forgeResult.fingerprint;
      this.sleepCount++;

      // Apply recovery modifiers
      this.vitality = withErrorPressure(
        withFocus(
          withEnergy(this.vitality, this.vitality.energy + forgeResult.energyRecovery),
          this.vitality.focus + forgeResult.focusRecovery,
        ),
        this.vitality.errorPressure * (1 - 0.5 * forgeResult.sleepQuality),
      );

      // Clear accumulated state via SleepCycle
      this.sleepState = completeSleep(this.sleepState);
      this.vitalityHistory = [];

      // Oracle: run local prediction analysis during sleep
      try {
        if (this.phoneOracle) {
          const predictions = await this.phoneOracle.analyze();
          if (predictions.length > 0) {
            // Write predictions to room property so study script can display them
            const allPredictions = this.phoneOracle.allPredictions();
            this.roomEngine.setProperty('oracle_predictions', JSON.stringify(allPredictions));

            // Spike drives based on Oracle predictions
            const actionable = allPredictions.filter(p => p.actionable);
            if (actionable.length > 0) {
              this.drives = spikeAlertness(this.drives, 0.3);
              this.drives = spikeCuriosity(this.drives, 0.15);
            } else if (allPredictions.length > 0) {
              this.drives = spikeAlertness(this.drives, 0.1);
            }
          }
        }
      } catch {
        // Oracle failure is non-fatal
      }
    } finally {
      this.sleepInProgress = false;
    }
  }

  /** Snapshot of vitality history for behavioral extraction. */
  getVitalityHistory(): VitalityState[] {
    return [...this.vitalityHistory];
  }

  /** Snapshot of events since last sleep for behavioral extraction. */
  getEventsSinceLastSleep(): WorldEvent[] {
    return [...this.sleepState.eventsSinceLastSleep];
  }

  /**
   * Extract recent conversation history for delegation context.
   * Returns the last 5 hot events as "speaker: text" strings.
   */
  private getRecentHistory(): string[] {
    return this.memoryPolicy.getHotEvents().slice(-5).map(
      (e) => `${e.entityName}: ${e.text}`,
    );
  }

  /**
   * Apply side-effect actions piped back from a bud delegation response.
   * Each action is narrated into the room so the player sees what happened.
   */
  private applyDelegationActions(actions: DelegationActionDto[]): void {
    const s = this.strings();
    for (const action of actions) {
      switch (action.type) {
        case 'room_created': {
          const exitLabel = String(action.data.exitLabel ?? '');
          if (exitLabel) {
            this.roomEngine.send({
              type: 'emote_in_room',
              entityId: 'narrator',
              entityName: 'narrator',
              text: s.newPassage(exitLabel),
            });
          }
          break;
        }
        case 'item_changed': {
          const result = String(action.data.result ?? '');
          if (result) {
            this.roomEngine.send({
              type: 'emote_in_room',
              entityId: 'narrator',
              entityName: 'narrator',
              text: result,
            });
          }
          break;
        }
        case 'notification': {
          const message = String(action.data.message ?? '');
          const priority = String(action.data.priority ?? 'normal');
          if (message) {
            this.roomEngine.send({
              type: 'emote_in_room',
              entityId: 'narrator',
              entityName: 'narrator',
              text: s.notificationPriority(wordFor(s.priorityWords, priority), message),
            });
          }
          break;
        }
        case 'room_navigated': {
          const direction = String(action.data.direction ?? '');
          this.roomEngine.send({
            type: 'emote_in_room',
            entityId: this.profile.entityId,
            entityName: this.profile.name,
            // A direction code as a word in her language; an exit's own name as it is.
            text: s.heads(wordFor(s.directionWords, direction)),
          });
          break;
        }
        case 'hint_updated':
          // Hints come as part of normal room state — no narration needed
          break;
      }
    }
  }

  // ── Drive & Proactivity ──────────────────────────────────────────────

  /** Current drive state snapshot. */
  getDrives(): DriveState {
    return { ...this.drives };
  }

  /** Current calibration ledger. */
  getCalibrationLedger(): CalibrationLedger {
    return this.calibrationLedger;
  }

  /**
   * Apply calibration feedback from the human.
   * Delegates to CalibrationLedger for immediate adjustment.
   */
  applyCalibrationFeedback(type: string, direction: string, category: string | null, trigger: string): void {
    this.calibrationLedger.applyFeedback(type, direction, category, trigger);
  }

  /**
   * Evaluate drive proactivity — called every vitality tick when idle.
   * If a drive exceeds the tier threshold, runs ProactivityJudgment and
   * emits the resulting action into the room.
   */
  private evaluateDriveProactivity(): void {
    // Quick check: any drive above a low floor before building full context
    if (!anyAbove(this.drives, 0.2)) return;

    // Compute remaining budget
    const now = Date.now();
    const elapsed = now - this.budgetWindowStart;
    if (elapsed > 3_600_000) {
      // Reset budget window every hour
      this.budgetWindowStart = now;
      this.proactivitySpent = 0;
    }
    const remainingBudget = computeBudget(this.proactivitySpent, elapsed);

    const ctx: JudgmentContext = {
      drives: this.drives,
      vitality: this.vitality,
      remainingBudget,
      lastProactiveActionMs: this.lastProactiveActionMs,
      lastHumanSpeechMs: this.lastHumanSpeechMs,
      agentEntityId: this.profile.entityId,
      tier: 0, // Phone agents default to tier 0 (nascent)
      oraclePredictions: this.phoneOracle?.allPredictions() ?? null,
      strings: this.strings(),
    };

    const result = evaluateProactivity(ctx);
    if (result.type !== 'act') return;

    const action = result.action;
    this.proactivitySpent += action.budgetCost;
    this.lastProactiveActionMs = now;

    // Execute the action
    switch (action.tier) {
      case 'ambient':
        this.roomEngine.send({
          type: 'emote_in_room',
          entityId: this.profile.entityId,
          entityName: this.profile.name,
          text: action.emoteText,
        });
        break;
      case 'observation':
        this.speak(action.speechText);
        break;
      case 'initiative':
        this.speak(`*${action.description}*`);
        break;
    }

    // Relieve the drive that was acted upon
    switch (action.driveName) {
      case 'curiosity':   this.drives = relieveCuriosity(this.drives); break;
      case 'care':        this.drives = relieveCare(this.drives); break;
      case 'social':      this.drives = relieveSocial(this.drives); break;
      case 'achievement': this.drives = relieveAchievement(this.drives); break;
      case 'alertness':   this.drives = relieveAlertness(this.drives); break;
    }
  }

  /** Request a greeting from the companion (e.g. on first room render). */
  requestGreeting(): void {
    if (this._state === 'idle' && !this.turnParked) {
      this.greetPlayer('You');
    }
  }

  shutdown(): void {
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    if (this.vitalityInterval) clearInterval(this.vitalityInterval);
    if (this.errorRecoveryTimer) clearTimeout(this.errorRecoveryTimer);
    if (this.unsubscribeRoom) this.unsubscribeRoom();
    this.speechListeners = [];
    this.dialogueStateListeners = [];
  }
}
