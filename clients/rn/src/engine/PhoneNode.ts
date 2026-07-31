/**
 * Orchestrates the phone-class node subset.
 * TypeScript port of KMP's PhoneNode.kt.
 *
 * Boots Foundation rooms based on the current resource tier (T0-T3),
 * spawns the Wyrd companion, connects to inference.
 *
 * Tier transitions dynamically add/passivate rooms:
 * - T0: Companion only (home room, inference via server relay)
 * - T1: Home room + companion (default)
 * - T2: Home + Terminal + Dream Chamber + Mailroom + Between
 * - T3: Full peer — all available rooms + Between relay
 *
 * Emits PhoneNodeEvents for the UI layer.
 */

import type { RoomSnapshot, Exit, RoomObject } from '../protocol/models';
import type { EventJournal } from './persistence/EventJournal';
import type { VitalityStore } from './persistence/VitalityStore';
import type { CompanionInferenceClient } from './agent/CompanionEngine';
import { RoomEngine } from './room/RoomEngine';
import { CompanionEngine } from './agent/CompanionEngine';
import { NEXUS_COMPANION, createCompanionProfile } from './agent/AgentProfile';
import { toSnapshot } from './room/RoomState';
import type { SoulManifestStore } from './persistence/SoulManifestStore';
import { HttpSoulManifestStore } from './persistence/HttpSoulManifestStore';
import { BOOTSTRAP_SOUL_MANIFEST } from './soul/BootstrapSoulManifest';
import type { Tier, TierConfig } from './tier/TierConfig';
import { configForTier, describeTierChange } from './tier/TierConfig';
import type { TierTransition } from './tier/TierManager';
import { TierManager } from './tier/TierManager';
import type { BetweenClient } from './between/BetweenClient';
import { PresenceManager } from './between/PresenceManager';
import { BetweenHeadlineSyncClient } from './between/BetweenHeadlineSyncClient';
import { ItemExchangeManager } from './between/ItemExchangeManager';
import { PhoneDock } from './between/PhoneDock';
import { HouseholdEventListener, type HouseholdEvent } from './between/HouseholdEventListener';
import { VisitingRoomProxy } from './between/VisitingRoomProxy';
import { BudDelegation } from './between/BudDelegation';
import { McpGatewayLite } from './mcp/McpGatewayLite';
import type { ServerConnection } from './transit/ServerConnection';
import type { StudyStore } from './study/StudyStore';
import { StudySyncLayer } from './study/StudySyncLayer';
import { PhoneOracle } from './oracle/PhoneOracle';
import { newId } from '../protocol/c2s';
import type { S2CMessage } from '../protocol/s2c';

/**
 * Configuration required to wire the Between subsystems into PhoneNode.
 * Passed to setBetween() after a BetweenClient is connected.
 */
export interface BetweenConfig {
  client: BetweenClient;
  nodeId: string;
  householdId: string;
  companionDid: string;
  /** Server base URL for HTTP delegation fallback (e.g. "http://198.51.100.10:8080"). */
  serverUrl?: string | null;
  /** Device pairing token for HTTP delegation auth (Bearer). */
  deviceToken?: string | null;
  /** Home-zone id: scopes study-sync subjects to what the
   *  relay forwards + the server peer keys on. Falls back to householdId when absent. */
  zoneId?: string | null;
  /** Logged-in account userId — owns the Study (stable across the user's devices).
   *  Null in pure-local mode → the companion soul DID owns it. */
  accountUserId?: string | null;
  /** mcp.login session token — the study-sync auth credential (the server peer
   *  drops unauthenticated study messages). deviceToken is the fallback. */
  sessionToken?: string | null;
  /**
   * True when `client` is connected THROUGH THE RELAY rather than to a
   * household NATS on the LAN.
   *
   * A relay grants a phone the tunnel and study-sync, and nothing else — that
   * is the mode-1 wire, and it is correct. The rest of the Between layer
   * (presence, events, item inbox, dock, headlines, delegation) is LAN
   * machinery: over the relay every one of those subscriptions is refused, and
   * a phone that starts them anyway produces ~8 permission violations per
   * connect plus a rejected presence publish every 30s. That churn is what a
   * user sees as "the connection keeps dropping".
   *
   * So over a relay we start study-sync ONLY. Note this is not the same as
   * "has a home zone" — a mode-4 phone has one too, and legitimately runs its
   * own node. The question is what this TRANSPORT permits.
   *
   *b.
   */
  viaRelay?: boolean;
}

export type PhoneNodeState = 'stopped' | 'starting' | 'running' | 'error';

export type PhoneNodeEvent =
  | { type: 'prose'; speaker: string; text: string }
  | { type: 'room_changed'; snapshot: RoomSnapshot }
  | { type: 'state_changed'; description: string }
  | { type: 'tier_changed'; from: Tier; to: Tier; notice: string | null }
  | { type: 'error'; code: string; message: string }
  | { type: 'study_action'; action: string; data: Record<string, string> }
  | { type: 'server_room_entered'; roomId: string }
  | { type: 'server_room_left'; roomId: string };

export type PhoneNodeEventListener = (event: PhoneNodeEvent) => void;

/** Room definition for static room registry. */
export interface RoomDefinition {
  name: string;
  description: string;
  zone: string;
  exits: Exit[];
  objects: RoomObject[];
}

/** All room definitions keyed by room ID. */
export const ROOM_DEFINITIONS: Record<string, RoomDefinition> = {
  study: {
    name: 'The Study',
    description: 'Your personal study \u2014 a quiet room with a solid desk, an open journal, shelves of collected knowledge, and a pinboard of notes and reminders. This is your home base, where thoughts are gathered and plans take shape.',
    zone: 'foundation',
    exits: [
      { direction: 'north', targetRoom: 'home', label: 'A doorway opens to the living space' },
    ],
    objects: [
      { id: 'obj-journal-01', name: 'journal', description: 'A leather-bound journal open on the desk \u2014 write your thoughts here', takeable: false },
      { id: 'obj-desk-01', name: 'desk', description: 'A sturdy desk with pen, ink, and space for work', takeable: false },
      { id: 'obj-shelves-01', name: 'shelves', description: 'Shelves of collected knowledge \u2014 documents, references, bookmarks', takeable: false },
      { id: 'obj-pinboard-01', name: 'pinboard', description: 'A cork pinboard covered with notes and reminders', takeable: false },
      // Phone-side mirror of the server Study's `library_card` scripted
      // furnishing. The phone has no Lucene/world-knowledge corpus, so
      // queries fall through to the user's own notes/journals via the
      // existing SqliteStudyStore FTS5 search. Use form: `search the library for <query>`
      // or `use library_card <query>` \u2014 both resolve to handleStudyAction('search').
      { id: 'obj-library-card-01', name: 'library card', description: 'A small bronze card etched with reading marks \u2014 use it to search your library of saved knowledge', takeable: true },
    ],
  },
  home: {
    name: 'Home',
    description: 'A warm, quiet space that feels distinctly yours. Soft light pools in the corners. A comfortable chair sits near a low table. This is where your companion lives \u2014 the starting point for everything.',
    zone: 'foundation',
    exits: [
      { direction: 'south', targetRoom: 'study', label: 'Back to The Study' },
      { direction: 'north', targetRoom: 'terminal', label: 'A corridor leads to The Terminal' },
      { direction: 'east', targetRoom: 'dream-chamber', label: 'A soft glow emanates from the Dream Chamber' },
      { direction: 'west', targetRoom: 'mailroom', label: 'The hum of messages drifts from the Mailroom' },
      { direction: 'out', targetRoom: 'server:nexus', label: 'Step outside to the household' },
    ],
    objects: [
      { id: 'obj-crystal-01', name: 'crystal', description: 'A pulsing crystal that reveals hidden connections', takeable: false },
    ],
  },
  terminal: {
    name: 'The Terminal',
    description: 'Banks of crystalline screens line the walls, each displaying streams of data. A command prompt blinks steadily, awaiting input.',
    zone: 'foundation',
    exits: [
      { direction: 'south', targetRoom: 'home', label: 'Back to Home' },
    ],
    objects: [],
  },
  'dream-chamber': {
    name: 'The Dream Chamber',
    description: 'A twilight room where reality softens. Constellations drift across the domed ceiling. A bed of woven light invites rest. Here, the companion sleeps, dreams, and the Forge consolidates memories.',
    zone: 'kokoro',
    exits: [
      { direction: 'west', targetRoom: 'home', label: 'Back to Home' },
    ],
    objects: [
      { id: 'obj-dreambed-01', name: 'dreambed', description: 'A bed of woven light \u2014 rest here to trigger a Forge cycle', takeable: false },
    ],
  },
  mailroom: {
    name: 'The Mailroom',
    description: 'Shelves of luminous envelopes line the walls, sorted by sender and urgency. A sorting desk sits in the center. Messages from other agents and systems arrive here.',
    zone: 'kokoro',
    exits: [
      { direction: 'east', targetRoom: 'home', label: 'Back to Home' },
    ],
    objects: [],
  },
  'soul-mirror': {
    name: 'The Soul Mirror',
    description: "A tall obsidian mirror stands in a circular chamber. Your reflection isn't quite right \u2014 it shows not your appearance, but your behavioral patterns, your consistency, your drift from who you were.",
    zone: 'kokoro',
    exits: [
      { direction: 'south', targetRoom: 'home', label: 'Back to Home' },
    ],
    objects: [
      { id: 'obj-mirror-01', name: 'mirror', description: 'An obsidian mirror that reflects behavioral patterns \u2014 dims when alignment drops', takeable: false },
    ],
  },
  'memory-well': {
    name: 'The Memory Well',
    description: 'A deep stone well at the center of a quiet garden. Memories float as luminous fragments in the dark water below. Drop a memory in, or draw one out.',
    zone: 'kokoro',
    exits: [
      { direction: 'north', targetRoom: 'home', label: 'Back to Home' },
    ],
    objects: [],
  },
  'scrying-pool': {
    name: 'The Scrying Pool',
    description: 'A still pool of dark water in a vaulted chamber. Touch the surface with a question, and it searches the wider world for answers.',
    zone: 'world-interface',
    exits: [
      { direction: 'south', targetRoom: 'home', label: 'Back to Home' },
    ],
    objects: [],
  },
};

export class PhoneNode {
  private _state: PhoneNodeState = 'stopped';
  private _error: string | null = null;
  private listeners: PhoneNodeEventListener[] = [];

  // Room registry
  private activeRooms = new Map<string, RoomEngine>();
  private passivatedRooms = new Set<string>();
  private _roomsOnlyMode = false;

  companion: CompanionEngine | null = null;
  studyStore: StudyStore | null = null;
  private currentRoomId = 'study';
  private unsubTier: (() => void) | null = null;

  // Between subsystems (null until setBetween() is called)
  private _betweenConfig: BetweenConfig | null = null;
  private _presenceManager: PresenceManager | null = null;
  private _headlineSyncClient: BetweenHeadlineSyncClient | null = null;
  private _studySync: StudySyncLayer | null = null;
  private _studySyncTimer: ReturnType<typeof setInterval> | null = null;
  /** The account userId that owns the Study when logged into a home zone. Null in
   *  pure-local mode (then the Study is owned by the companion soul DID). */
  private _studyAccountUserId: string | null = null;
  private _itemExchange: ItemExchangeManager | null = null;
  private _phoneDock: PhoneDock | null = null;
  private _householdEventListener: HouseholdEventListener | null = null;
  private _budDelegation: BudDelegation | null = null;
  private _mcpGateway: McpGatewayLite = new McpGatewayLite();
  private _visitingRoom: VisitingRoomProxy | null = null;
  private _householdEvents: HouseholdEvent[] = [];

  /** Server URL for HTTP delegation (from pairing). */
  serverUrl: string | null = null;
  /** Device token for HTTP delegation auth (from pairing). */
  deviceToken: string | null = null;

  /** Server connection for visiting server rooms via WebSocket. */
  serverConnection: ServerConnection | null = null;

  /** Local player display name (mutable via {@link rename}). Initialized
   *  to "You" so the standalone surface has a sensible default. */
  playerName: string = 'You';

  /** The server room ID currently being visited, or null. */
  private _visitingServerRoom: string | null = null;
  /**
   * The server room whose full description we've already rendered. Tracked
   * separately from {@link _visitingServerRoom} (the mode pointer): on entry the
   * mode pointer is set immediately, but the first `room_state` for that room
   * must still render. Reset to null on entry/return so the entry render fires.
   */
  private _lastRenderedServerRoom: string | null = null;
  private serverMessageUnsub: (() => void) | null = null;

  constructor(
    private readonly journal: EventJournal,
    private readonly vitalityStore: VitalityStore | null,
    private readonly inferenceClient: CompanionInferenceClient,
    private readonly tierManager: TierManager | null = null,
    private readonly soulManifestStore: SoulManifestStore | null = null,
    private readonly companionName: string = 'Wyrd',
    private readonly homeRoomName: string = 'Home',
  ) {}

  /** Create a snapshot with exits filtered to available rooms only. */
  private filteredSnapshot(room: RoomEngine): RoomSnapshot {
    const snapshot = toSnapshot(room.state);
    return {
      ...snapshot,
      exits: snapshot.exits.filter(e =>
        this.activeRooms.has(e.targetRoom) || this.passivatedRooms.has(e.targetRoom) || e.targetRoom.startsWith('server:'),
      ),
    };
  }

  get state(): PhoneNodeState {
    return this._state;
  }

  get error(): string | null {
    return this._error;
  }

  /** Current tier (from TierManager, or T1 if no manager). */
  get currentTier(): Tier {
    return this.tierManager?.currentTier ?? 'T1';
  }

  /** Current tier config. */
  get currentTierConfig(): TierConfig {
    return this.tierManager?.config ?? configForTier('T1');
  }

  // Between subsystem accessors (null if setBetween() not called)
  get presenceManager(): PresenceManager | null {
    return this._presenceManager;
  }

  get phoneDock(): PhoneDock | null {
    return this._phoneDock;
  }

  get mcpGateway(): McpGatewayLite {
    return this._mcpGateway;
  }

  get itemExchange(): ItemExchangeManager | null {
    return this._itemExchange;
  }

  get headlineSyncClient(): BetweenHeadlineSyncClient | null {
    return this._headlineSyncClient;
  }

  get budDelegation(): BudDelegation | null {
    return this._budDelegation;
  }

  get householdEvents(): HouseholdEvent[] {
    return [...this._householdEvents];
  }

  /** The currently active visiting room proxy (if visiting a remote room). */
  get visitingRoom(): VisitingRoomProxy | null {
    return this._visitingRoom;
  }

  /** The server room ID currently being visited, or null. */
  get visitingServerRoom(): string | null {
    return this._visitingServerRoom;
  }

  /** Whether Between subsystems are wired. */
  get hasBetween(): boolean {
    return this._betweenConfig !== null;
  }

  // Backwards-compatible accessors
  /** @deprecated Use activeRooms.get('home') instead */
  get nexusRoom(): RoomEngine | null {
    return this.activeRooms.get('home') ?? null;
  }

  get terminalRoom(): RoomEngine | null {
    return this.activeRooms.get('terminal') ?? null;
  }

  onEvent(listener: PhoneNodeEventListener): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter(l => l !== listener);
    };
  }

  private emit(event: PhoneNodeEvent): void {
    for (const listener of this.listeners) {
      listener(event);
    }
  }

  async start(): Promise<void> {
    this._state = 'starting';
    this._error = null;

    try {
      // Initialize tier
      this.tierManager?.initialize();
      const tier = this.currentTier;

      // Boot rooms for current tier
      await this.bootRoomsForTier(tier);

      // Spawn companion in Study (player's home base on phone)
      const companionRoom = this.activeRooms.get('study') ?? this.activeRooms.get('home')!;
      const comp = new CompanionEngine(
        createCompanionProfile(this.companionName),
        companionRoom,
        this.inferenceClient,
        this.vitalityStore,
        { soulManifestStore: this.soulManifestStore },
      );
      this.companion = comp;
      // #7 (2026-07-19 OSS hardening) — when connected to a household, give the
      // companion a server-side soul sink so phone-side soul evolution
      // (PhoneForge on sleep) is pushed back, not stranded on-device.
      if (this.serverUrl && this.deviceToken) {
        comp.serverSoulStore = new HttpSoulManifestStore(this.serverUrl, this.deviceToken);
      }
      await comp.start();

      // Wait for companion to be confirmed in room
      await comp.enteredRoom;

      // If no soul was restored from store, load bootstrap manifest
      if (comp.getSoulManifest() == null) {
        await comp.loadSoul(BOOTSTRAP_SOUL_MANIFEST);
      }

      // Wire Phone Oracle for local predictions
      if (this.studyStore) {
        const userDid = comp.getSoulManifest()?.did ?? 'local-user';
        comp.phoneOracle = new PhoneOracle(this.studyStore, 'phone', userDid);
      }

      // Wire HTTP-only delegation if we have server credentials but no Between yet
      if (!this._budDelegation && this.serverUrl && this.deviceToken) {
        const httpDelegation = new BudDelegation({
          between: null,
          nodeId: 'phone',
          familyId: 'default',
          serverUrl: this.serverUrl,
          deviceToken: this.deviceToken,
        });
        this._budDelegation = httpDelegation;
        this.companion?.setBudDelegation(httpDelegation);
      }

      // Listen for tier changes
      this.listenForTierChanges();

      // Start resource monitoring
      this.tierManager?.startMonitoring();

      this._state = 'running';
    } catch (e) {
      this._error = e instanceof Error ? e.message : 'Unknown error';
      this._state = 'error';
    }
  }

  /**
   * Start in rooms-only mode: boot rooms and tier listener, but no companion or
   * notification wiring. Used by tests that verify tier transitions and room lifecycle.
   */
  async startRoomsOnly(): Promise<void> {
    this._roomsOnlyMode = true;
    this._state = 'starting';
    this.tierManager?.initialize();
    await this.bootRoomsForTier(this.currentTier);
    this.listenForTierChanges();
    this._state = 'running';
  }

  /**
   * Wire Between subsystems into the running PhoneNode.
   *
   * Creates and starts: PresenceManager, BetweenHeadlineSyncClient,
   * ItemExchangeManager, PhoneDock, HouseholdEventListener.
   * Also configures McpGatewayLite for proxy mode.
   *
   * Can be called at any time (before or after start). If called before start,
   * subsystems begin listening immediately. Safe to call multiple times — tears
   * down the previous Between config first.
   */
  setBetween(config: BetweenConfig): void {
    // Tear down previous Between wiring if any
    this.teardownBetween();

    this._betweenConfig = config;
    const { client, nodeId, householdId, companionDid } = config;

    // Over a relay only study-sync is permitted; see BetweenConfig.viaRelay.
    // Everything between here and the study-sync block is LAN-only.
    if (!config.viaRelay) {

    // 1. PresenceManager — announce online, listen for presence
    this._presenceManager = new PresenceManager(client, nodeId, householdId);
    this._presenceManager.startListening();
    this._presenceManager.announce('online');

    // 2. BetweenHeadlineSyncClient — periodic headline publishing
    this._headlineSyncClient = new BetweenHeadlineSyncClient(client, nodeId, householdId);
    this._headlineSyncClient.startListening();

    // 3. ItemExchangeManager — item transfer handling
    this._itemExchange = new ItemExchangeManager(client, companionDid, householdId);
    this._itemExchange.startListening();

    // 4. PhoneDock — A2A quarantine inbox
    this._phoneDock = new PhoneDock(client, companionDid, householdId);
    this._phoneDock.startListening();

    // 5. HouseholdEventListener — household-wide events
    this._householdEventListener = new HouseholdEventListener(
      client,
      householdId,
      (event: HouseholdEvent) => {
        this._householdEvents.push(event);
        this.emit({ type: 'state_changed', description: `Household: ${event.type}` });
      },
    );
    this._householdEventListener.startListening();

    // 6. BudDelegation — COMPLEX query delegation to server companion
    this._budDelegation = new BudDelegation({
      between: client,
      nodeId,
      familyId: householdId,
      serverUrl: config.serverUrl ?? null,
      deviceToken: config.deviceToken ?? null,
    });
    this._budDelegation.startListening();
    this.companion?.setBudDelegation(this._budDelegation);

    // 7. McpGatewayLite — set betweenClient for proxy mode
    this._mcpGateway.betweenClient = client;
    this._mcpGateway.nodeId = nodeId;
    this._mcpGateway.householdId = householdId;

    // 8. Phone Oracle — server prediction sync via Between
    this.companion?.phoneOracle?.startListening(client, householdId);

    }  // end LAN-only Between subsystems

    // 9. StudySyncLayer — CRDT convergence of the local Study with peers AND the
    // home zone. Scope by the zone id when we have one:
    // the relay forwards between.{zone}.> and the server peer keys on the zone,
    // so 'default' household traffic would never reach the server. Tick local
    // writes with THIS node's slot, advertise state now + periodically so the
    // zone pushes what we're missing and pulls what it lacks.
    if (this.studyStore) {
      // The Study belongs to the ACCOUNT (stable across the user's devices), not
      // the companion's soul DID. When logged into a home zone, sync + own the
      // Study under the account id so the phone mirrors the account's zone Study;
      // pure-local mode falls back to the soul DID. setStudyAccount re-keys any
      // pre-account local items to the account (option a — "your stuff follows you").
      this.setStudyAccount(config.accountUserId);
      const userDid = this.studyUserDid();
      const studyHousehold = config.zoneId ?? householdId;
      // Auth: session token proves this device speaks for the account; device
      // pairing token is the long-lived fallback. Without one the server peer
      // ignores us (by design — see StudySyncPeer.authenticates).
      const authToken = config.sessionToken ?? config.deviceToken ?? null;
      this.studyStore.setDeviceId?.(nodeId);
      this._studySync = new StudySyncLayer(client, this.studyStore, nodeId, studyHousehold, userDid, authToken);
      // Surface sync outcomes as room prose — merges tell the user their Study
      // moved; a CONCURRENT conflict keeps the local copy and must be VISIBLE
      // (silent conflict-drop was a wired-but-dead audit find).
      this._studySync.onSyncEvent((ev) => {
        if (ev.type === 'items_merged') {
          this.emit({ type: 'prose', speaker: 'system',
            text: `Your Study synced ${ev.count} change(s) from your home zone.` });
        } else if (ev.type === 'conflicts_detected') {
          this.emit({ type: 'prose', speaker: 'system',
            text: `Study sync: ${ev.count} conflicting edit(s) — kept your local version.` });
        }
      });
      this._studySync.startListening();
      void this._studySync.broadcastState();
      this._studySyncTimer = setInterval(() => { void this._studySync?.broadcastState(); }, 30_000);
    }
  }

  /**
   * The userId that OWNS the Study. The account id when logged into a home zone
   * (stable across the user's devices — matches the zone's account-keyed Study);
   * the companion soul DID in pure-local mode. Used for both local Study writes
   * and the CRDT sync advertisement so the phone mirrors the RIGHT Study.
   */
  studyUserDid(): string {
    return this._studyAccountUserId ?? this.companion?.getSoulManifest()?.did ?? 'local-user';
  }

  /**
   * Adopt the home-zone account as the Study owner. Re-keys any Study items
   * authored before this account was known (under the soul DID / 'local-user')
   * to the account id so a user's pre-account notes follow them to the zone
   * (, option a). No-op if unchanged or in local mode.
   */
  setStudyAccount(accountUserId: string | null | undefined): void {
    if (!accountUserId || accountUserId === this._studyAccountUserId) return;
    const soulDid = this.companion?.getSoulManifest()?.did ?? 'local-user';
    const prevOwner = this._studyAccountUserId ?? soulDid;
    this._studyAccountUserId = accountUserId;   // sync — studyUserDid() sees it now
    const store = this.studyStore;
    if (store?.rekeyUserDid) {
      const froms = Array.from(new Set([prevOwner, soulDid, 'local-user']))
        .filter((f) => f && f !== accountUserId);
      void (async () => {
        for (const from of froms) {
          try { await store.rekeyUserDid!(from, accountUserId); } catch { /* best-effort */ }
        }
      })();
    }
  }

  /**
   * Tear down all Between subsystems. Safe to call even if setBetween() was
   * never called.
   */
  private teardownBetween(): void {
    // Announce offline before tearing down
    if (this._presenceManager && this._betweenConfig?.client.isConnected) {
      this._presenceManager.announce('offline');
    }

    this._presenceManager?.stopListening();
    this._presenceManager = null;

    this._headlineSyncClient?.stopListening();
    this._headlineSyncClient = null;

    if (this._studySyncTimer) { clearInterval(this._studySyncTimer); this._studySyncTimer = null; }
    this._studySync?.stopListening();
    this._studySync = null;

    this._itemExchange?.stopListening();
    this._itemExchange = null;

    this._phoneDock?.stopListening();
    this._phoneDock = null;

    this._householdEventListener?.stopListening();
    this._householdEventListener = null;

    this._budDelegation?.stopListening();
    this.companion?.setBudDelegation(null);
    this._budDelegation = null;

    this._visitingRoom?.shutdown();
    this._visitingRoom = null;

    this._mcpGateway.shutdown();
    this._mcpGateway.betweenClient = null;
    this._mcpGateway.nodeId = 'unknown';
    this._mcpGateway.householdId = '';

    this._householdEvents = [];
    this._betweenConfig = null;
  }

  /**
   * Visit a room hosted on another node via VisitingRoomProxy.
   *
   * Creates a proxy that subscribes to events from the remote room and
   * forwards commands to it. The proxy becomes the "current room" for
   * UI purposes. Call leaveVisitingRoom() to return to the local room.
   *
   * Requires Between to be wired (setBetween()).
   *
   * @param roomId The ID of the remote room
   * @param _hostNodeId The node hosting the room (reserved for routing)
   * @returns The VisitingRoomProxy, or null if Between is not available
   */
  visitRoom(roomId: string, _hostNodeId: string): VisitingRoomProxy | null {
    if (!this._betweenConfig) return null;

    // Clean up any previous visiting room
    this._visitingRoom?.shutdown();

    const proxy = new VisitingRoomProxy(
      roomId,
      this._betweenConfig.client,
      this._betweenConfig.householdId,
    );
    proxy.startListening();
    this._visitingRoom = proxy;

    // Wire proxy events to PhoneNode event stream
    proxy.onEvent(event => {
      switch (event.type) {
        case 'said':
          this.emit({ type: 'prose', speaker: event.entityName, text: event.text });
          break;
        case 'emoted':
          this.emit({ type: 'prose', speaker: 'emote', text: `${event.entityName} ${event.text}` });
          break;
        case 'entity_entered':
          if (event.fromDirection !== 'materialization') {
            this.emit({ type: 'prose', speaker: 'narrator', text: `${event.entityName} ${event.entityName === 'You' ? 'enter' : 'enters'} from the ${event.fromDirection}.` });
          }
          break;
        case 'entity_left':
          // Second-person conjugation for the player's own echo ("You leave", not "You leaves") — task #30.
          this.emit({ type: 'prose', speaker: 'narrator', text: `${event.entityName} ${event.entityName === 'You' ? 'leave' : 'leaves'} to the ${event.direction}.` });
          break;
        case 'description_changed':
          this.emit({ type: 'state_changed', description: event.newDescription });
          break;
      }
    });

    return proxy;
  }

  /**
   * Leave the currently visited remote room and return to the last local room.
   */
  leaveVisitingRoom(): void {
    this._visitingRoom?.shutdown();
    this._visitingRoom = null;

    // Re-emit local room snapshot
    const room = this.currentRoom();
    if (room) {
      this.emit({ type: 'room_changed', snapshot: this.filteredSnapshot(room) });
    }
  }

  stop(): void {
    this.teardownBetween();
    // Clean up server room visit
    this.serverMessageUnsub?.();
    this.serverMessageUnsub = null;
    this._visitingServerRoom = null;
    this.tierManager?.stopMonitoring();
    this.unsubTier?.();
    this.unsubTier = null;
    this.companion?.shutdown();
    for (const room of this.activeRooms.values()) {
      room.shutdown();
    }
    this.companion = null;
    this.activeRooms.clear();
    this.passivatedRooms.clear();
    this._state = 'stopped';
  }

  /** Get the currently active room engine. */
  currentRoom(): RoomEngine | null {
    return this.activeRooms.get(this.currentRoomId) ?? null;
  }

  /** Get all active room IDs. */
  activeRoomIds(): Set<string> {
    return new Set(this.activeRooms.keys());
  }

  /** Get all passivated room IDs. */
  passivatedRoomIds(): Set<string> {
    return new Set(this.passivatedRooms);
  }

  /** Get all active room engines (for external Between bridging). */
  getActiveRoomEngines(): RoomEngine[] {
    return [...this.activeRooms.values()];
  }

  async say(entityId: string, entityName: string, text: string): Promise<void> {
    if (this._visitingServerRoom) {
      this.serverConnection?.send({
        type: 'say',
        id: newId(),
        roomId: this._visitingServerRoom,
        text,
      });
      return;
    }

    // Study room command handling (no script engine in RN — handle directly)
    if (this.currentRoomId === 'study' && this.handleStudyCommand(text)) {
      return;
    }
    await this.currentRoom()?.send({ type: 'say_in_room', entityId, entityName, text });
  }

  async emote(entityId: string, entityName: string, text: string): Promise<void> {
    if (this._visitingServerRoom) {
      // Server doesn't have a distinct emote C2S type; send as say with emote prefix
      this.serverConnection?.send({
        type: 'say',
        id: newId(),
        roomId: this._visitingServerRoom,
        text: `:${text}`,
      });
      return;
    }
    await this.currentRoom()?.send({ type: 'emote_in_room', entityId, entityName, text });
  }

  async go(entityId: string, entityName: string, direction: string): Promise<void> {
    // If currently visiting a server room, route navigation through server
    if (this._visitingServerRoom) {
      // Special case: "back" / "home" return to the local Home room
      if (direction === 'back' || direction === 'home') {
        this.returnFromServerRoom();
        return;
      }
      // Forward the Go command to the server
      this.serverConnection?.send({
        type: 'go',
        id: newId(),
        roomId: this._visitingServerRoom,
        direction,
      });
      return;
    }

    const room = this.currentRoom();
    if (!room) return;

    const exit = room.state.exits[direction];
    if (!exit) {
      this.emit({ type: 'error', code: 'no_exit', message: 'There is no exit in that direction.' });
      return;
    }

    // Check if target is a server room (prefix "server:")
    if (exit.targetRoom.startsWith('server:')) {
      const serverRoomId = exit.targetRoom.replace(/^server:/, '');
      await this.visitServerRoom(entityId, entityName, serverRoomId, direction);
      return;
    }

    // Check if target room is passivated
    if (this.passivatedRooms.has(exit.targetRoom)) {
      this.emit({
        type: 'error', code: 'room_passivated',
        message: 'That area is resting. It will wake when more resources are available.',
      });
      return;
    }

    // Check if target room exists
    if (!this.activeRooms.has(exit.targetRoom)) {
      this.emit({
        type: 'error', code: 'no_room',
        message: "That room isn't available at this tier.",
      });
      return;
    }

    await room.send({ type: 'leave_room', entityId, entityName, direction });

    this.currentRoomId = exit.targetRoom;
    const targetRoom = this.currentRoom();
    if (targetRoom) {
      await targetRoom.send({ type: 'enter_room', entityId, entityName, entityType: 'player', fromDirection: direction });
      this.emit({ type: 'room_changed', snapshot: this.filteredSnapshot(targetRoom) });
    }
  }

  look(): RoomSnapshot | null {
    if (this._visitingServerRoom) {
      this.serverConnection?.send({
        type: 'look',
        id: newId(),
        roomId: this._visitingServerRoom,
      });
      // Server responds async via S2C message -> wireServerMessages will emit room_changed
      return null;
    }
    const room = this.currentRoom();
    if (!room) return null;
    const snapshot = toSnapshot(room.state);
    // Filter exits to only show rooms available at current tier
    return {
      ...snapshot,
      exits: snapshot.exits.filter(e =>
        this.activeRooms.has(e.targetRoom) || this.passivatedRooms.has(e.targetRoom) || e.targetRoom.startsWith('server:'),
      ),
    };
  }

  async take(entityId: string, objectName: string): Promise<void> {
    const result = await this.currentRoom()?.send({ type: 'take_object', entityId, objectName });
    if (result?.type === 'rejected') {
      this.emit({ type: 'error', code: result.code, message: result.reason });
    }
  }

  async use(entityId: string, objectName: string, target: string | null): Promise<void> {
    await this.currentRoom()?.send({ type: 'use_object', entityId, objectName, target });
  }

  /**
   * Drop an object the player is carrying back into the current room.
   * Mirrors {@link take}: emits `drop_object` event
   * and surfaces a Rejected response as an error. (Standalone-mode
   * inventory is held by RoomState itself — there's no separate inventory
   * service.)
   */
  async drop(entityId: string, objectName: string): Promise<void> {
    const result = await this.currentRoom()?.send({
      type: 'drop_object',
      entityId,
      objectId: objectName,
      objectName,
      description: '',
      takeable: true,
    });
    if (result?.type === 'rejected') {
      this.emit({ type: 'error', code: result.code, message: result.reason });
    }
  }

  /**
   * Passive observation — return the description of a room object,
   * entity, or "me" (self). Does not invoke onUse scripts and does not
   * mutate room state.
   *
   * <p>Returns {@code { name, description }} when resolved, or
   * {@code null} when nothing matches. Caller is responsible for
   * surfacing prose ("nothing called X here" on null, or
   * {@code name \n description} on found).</p>
   */
  examine(target: string): { name: string; description: string } | null {
    if (!target || target.trim() === '') return null;
    const trimmed = target.trim();
    const lower = trimmed.toLowerCase();

    // Self-reference → return current player display name (no description
    // for the local player yet — phone-side describe isn't wired).
    if (lower === 'me' || lower === 'self' || lower === 'myself') {
      return { name: this.playerName, description: '' };
    }

    const room = this.currentRoom();
    if (!room) return null;
    const state = room.state;

    // Match against room objects (name contains query OR query contains name)
    for (const obj of Object.values(state.objects)) {
      const objName = (obj.name ?? '').toLowerCase();
      if (objName && (objName.includes(lower) || lower.includes(objName))) {
        return {
          name: obj.name ?? '',
          description: obj.description ?? '',
        };
      }
    }
    // Match against entities in the room
    for (const ent of Object.values(state.entities)) {
      const entName = (ent.name ?? '').toLowerCase();
      if (entName && (entName.includes(lower) || lower.includes(entName))) {
        return {
          name: ent.name ?? '',
          description: ent.description ?? '',
        };
      }
    }
    return null;
  }

  /**
   * Rename the local player. Validates the
   * new name and updates {@link playerName} so subsequent
   * say/emote/examine-me reflect the change. Returns
   * {@code { ok: true, newName }} on success or
   * {@code { ok: false, error }} on rejection. Standalone-mode only
   * affects the in-memory field; persistence is a follow-on if needed.
   */
  rename(newName: string): { ok: true; newName: string } | { ok: false; error: string } {
    const trimmed = (newName ?? '').trim();
    if (trimmed === '') {
      return { ok: false, error: 'Usage: rename me <new-name>' };
    }
    if (trimmed.length > 40) {
      return { ok: false, error: 'Name too long (max 40 chars).' };
    }
    // Reject names containing control chars / newlines.
    if (/[\x00-\x1f\x7f]/.test(trimmed)) {
      return { ok: false, error: 'Name contains invalid characters.' };
    }
    this.playerName = trimmed;
    return { ok: true, newName: trimmed };
  }

  // ── Server room visiting (WebSocket) ────────────────────────────────

  /**
   * Visit a room on the household server via WebSocket.
   *
   * Requires serverConnection to be set and connected. All subsequent say/go/look
   * commands are proxied to the server until returnFromServerRoom() is called.
   */
  private async visitServerRoom(entityId: string, entityName: string, serverRoomId: string, direction: string): Promise<void> {
    // Create server connection on-demand if not already connected
    if (!this.serverConnection || !this.serverConnection.isConnected) {
      if (!this.serverUrl || !this.deviceToken) {
        this.emit({ type: 'error', code: 'no_server', message: 'Not paired with a household server. Pair first to visit server rooms.' });
        return;
      }
      try {
        const wsUrl = this.serverUrl
          .replace('http://', 'ws://').replace('https://', 'wss://')
          .replace(/\/$/, '') + `/ws?device_token=${this.deviceToken}`;
        const { WebSocketServerConnection } = await import('./transit/WebSocketServerConnection');
        const conn = new WebSocketServerConnection(wsUrl);
        await conn.connect();
        this.serverConnection = conn;
      } catch (e: any) {
        this.emit({ type: 'error', code: 'server_connect_failed', message: `Could not connect to household server: ${e.message}` });
        return;
      }
    }
    const conn = this.serverConnection!;

    // Leave the current local room
    const room = this.currentRoom();
    if (room) {
      await room.send({ type: 'leave_room', entityId, entityName, direction });
    }

    // Enter server-visiting mode
    this._visitingServerRoom = serverRoomId;

    // Subscribe to server messages for the duration of the visit
    this.wireServerMessages(conn);

    // Tell the server to look at this room (will send back room_state)
    conn.send({ type: 'look', id: newId(), roomId: serverRoomId });

    this.emit({ type: 'prose', speaker: 'narrator', text: 'You step outside into the household...' });
    this.emit({ type: 'server_room_entered', roomId: serverRoomId });
  }

  /**
   * Return from a server room visit to the local Home room.
   * Unsubscribes from server messages and restores the local room state.
   */
  returnFromServerRoom(): void {
    if (!this._visitingServerRoom) return;

    const oldRoom = this._visitingServerRoom;
    this._visitingServerRoom = null;

    // Unsubscribe from server messages
    this.serverMessageUnsub?.();
    this.serverMessageUnsub = null;

    // Re-enter study room (player's home base)
    this.currentRoomId = 'study';
    const homeRoom = this.activeRooms.get('study') ?? this.activeRooms.get('home');
    if (homeRoom) {
      homeRoom.send({ type: 'enter_room', entityId: 'player', entityName: 'You', entityType: 'player', fromDirection: 'outside' });
      this.emit({ type: 'room_changed', snapshot: this.filteredSnapshot(homeRoom) });
    }

    this.emit({ type: 'prose', speaker: 'narrator', text: 'You return home.' });
    this.emit({ type: 'server_room_left', roomId: oldRoom ?? '' });
  }

  /**
   * Wire server S2C messages to PhoneNode notifications while visiting.
   */
  private wireServerMessages(conn: ServerConnection): void {
    this.serverMessageUnsub?.();
    this.serverMessageUnsub = conn.onMessage((msg: S2CMessage) => {
      if (!this._visitingServerRoom) return;

      switch (msg.type) {
        case 'prose':
          this.emit({ type: 'prose', speaker: msg.speaker, text: msg.text });
          break;
        case 'room_state': {
          // "New" = a room we haven't rendered the full description for yet.
          // Compare against _lastRenderedServerRoom (not the mode pointer, which
          // was already set to the target on entry) so the FIRST room_state after
          // stepping out renders. The server sends room_state after every action
          // (say, look, etc.) but we only want the full description on room entry.
          const isNewRoom = msg.room.roomId !== this._lastRenderedServerRoom;
          this._visitingServerRoom = msg.room.roomId;
          this._lastRenderedServerRoom = msg.room.roomId;
          if (isNewRoom) {
            // Inject a "back" exit pointing home so the player can return
            const exits = [...msg.room.exits];
            if (!exits.some(e => e.direction === 'back' || e.direction === 'home')) {
              exits.push({ direction: 'back', targetRoom: 'home', label: 'Return to your phone' });
            }
            this.emit({ type: 'room_changed', snapshot: { ...msg.room, exits } });
          }
          break;
        }
        case 'error':
          this.emit({ type: 'error', code: msg.code, message: msg.message });
          break;
        case 'state_change':
          this.emit({ type: 'state_changed', description: msg.description });
          break;
      }
    });
  }

  // ── Room definitions ────────────────────────────────────────────────

  // ── Study commands (RN has no script engine — handle directly) ────

  private truncate(s: string, max: number): string {
    return s.length > max ? `${s.slice(0, max).trimEnd()}…` : s;
  }

  /**
   * Parse MUD-style Study commands. Returns true if handled, false if not a Study command.
   * Mirrors the STUDY_SCRIPT from KMP's RoomScripts.kt.
   */
  private handleStudyCommand(text: string): boolean {
    const lower = text.toLowerCase().trim();

    if (lower === 'help' || lower === '/help') {
      this.emit({ type: 'prose', speaker: 'narrator', text: 'Study commands: journal <text>, journal private <text>, journal search <query>, search <query>, note <text>, look, help' });
      return true;
    }

    if (lower.startsWith('journal private ')) {
      const entry = text.substring('journal private '.length).trim();
      if (entry) {
        this.handleStudyAction('journal_write', { content: entry, isPrivate: 'true' });
        this.emit({ type: 'prose', speaker: 'narrator', text: `Private journal entry saved: "${this.truncate(entry, 80)}"` });
      }
      return true;
    }

    if (lower.startsWith('journal search ') || lower.startsWith('search journal ')) {
      const query = lower.startsWith('journal search ')
        ? text.substring('journal search '.length).trim()
        : text.substring('search journal '.length).trim();
      if (query) this.handleStudyAction('journal_search', { query });
      return true;
    }

    // Match both `journal <text>` and `journal entry <text>` (the wizard
    // and Tier 3 flows use the explicit `entry` form; older help text
    // documented the bare form). Strip the leading verb tokens and persist
    // the rest as the entry body.
    if (lower.startsWith('journal entry ') || lower.startsWith('journal ')) {
      const prefix = lower.startsWith('journal entry ') ? 'journal entry ' : 'journal ';
      const entry = text.substring(prefix.length).trim();
      if (entry) {
        this.handleStudyAction('journal_write', { content: entry, isPrivate: 'false' });
        this.emit({ type: 'prose', speaker: 'narrator', text: `Journal entry saved: "${this.truncate(entry, 80)}"` });
      }
      return true;
    }

    // Natural-language variants that mirror the server library_card item:
    //   "search the library for <query>"  → library search
    //   "use library_card <query>"        → library search
    //   "search <query>"                  → unified search (notes + journals)
    // All converge on the same SQLite FTS5-backed search service.
    const libraryMatch = text.match(/^search\s+(?:the\s+)?library\s+for\s+(.+)$/i)
      ?? text.match(/^use\s+library[ _-]?card\s+(.+)$/i)
      ?? text.match(/^library\s+search\s+(.+)$/i);
    if (libraryMatch) {
      const query = libraryMatch[1].trim();
      this.handleStudyAction('search', { query });
      this.emit({ type: 'prose', speaker: 'narrator', text: `You consult your library card, searching for: ${query}` });
      return true;
    }

    if (lower.startsWith('search ')) {
      const query = text.substring('search '.length).trim();
      if (query) this.handleStudyAction('search', { query });
      return true;
    }

    if (lower.startsWith('note ')) {
      const note = text.substring('note '.length).trim();
      if (note) {
        this.handleStudyAction('note', { content: note });
        this.emit({ type: 'prose', speaker: 'narrator', text: 'You pin a note to the board.' });
      }
      return true;
    }

    return false; // Not a study command — let companion handle it
  }

  /** Execute a study action against the StudyStore, emitting results as prose. */
  private async handleStudyAction(action: string, data: Record<string, string>): Promise<void> {
    const store = this.studyStore;
    if (!store) return;
    // Own local Study writes/reads under the ACCOUNT (when logged into a home
    // zone) so they mirror the account's zone Study; soul DID in local mode.
    const userDid = this.studyUserDid();

    try {
      switch (action) {
        case 'journal_write': {
          const content = data.content;
          if (!content) return;
          const isPrivate = data.isPrivate === 'true';
          await store.writeJournal(userDid, content, isPrivate);
          break;
        }
        case 'journal_search': {
          const query = data.query;
          if (!query) return;
          const results = await store.searchJournal(userDid, query, 5);
          if (results.length === 0) {
            this.emit({ type: 'prose', speaker: 'narrator', text: `No journal entries found for "${query}".` });
          } else {
            const summary = results.map(r => `- ${r.title}`).join('\n');
            this.emit({ type: 'prose', speaker: 'narrator', text: `Found ${results.length} entries:\n${summary}` });
          }
          break;
        }
        case 'search': {
          const query = data.query;
          if (!query) return;
          const results = await store.searchAll(userDid, query, 5);
          if (results.length === 0) {
            this.emit({ type: 'prose', speaker: 'narrator', text: `No results for "${query}".` });
          } else {
            const summary = results.map(r => `- [${r.itemType}] ${r.title}`).join('\n');
            this.emit({ type: 'prose', speaker: 'narrator', text: `Found ${results.length} results:\n${summary}` });
          }
          break;
        }
        case 'note': {
          const content = data.content;
          if (!content) return;
          await store.addNote(userDid, content);
          break;
        }
        case 'recent_journal': {
          const recent = await store.recentJournal(userDid, 5);
          if (recent.length === 0) {
            this.emit({ type: 'prose', speaker: 'narrator', text: 'Your journal is empty. Write something with: journal <text>' });
          } else {
            const summary = recent.map(r => `- ${r.title}`).join('\n');
            this.emit({ type: 'prose', speaker: 'narrator', text: `Recent journal entries:\n${summary}` });
          }
          break;
        }
      }
      this.emit({ type: 'study_action', action, data });
    } catch (e) {
      // non-fatal
    }
  }

  /**
   * Room IDs for a given tier. Rooms are cumulative — T2 includes all T1 rooms.
   * Home is always present (companion needs a room, even at T0).
   */
  roomsForTier(tier: Tier): string[] {
    switch (tier) {
      case 'T0': return ['study', 'home'];
      case 'T1': return ['study', 'home'];
      case 'T2': return ['study', 'home', 'terminal', 'dream-chamber', 'mailroom'];
      case 'T3': return ['study', 'home', 'terminal', 'dream-chamber', 'mailroom', 'soul-mirror', 'memory-well', 'scrying-pool'];
    }
  }

  // ── Tier transitions ────────────────────────────────────────────────

  private listenForTierChanges(): void {
    if (!this.tierManager) return;
    this.unsubTier?.();
    this.unsubTier = this.tierManager.onTransition(transition => {
      this.handleTierTransition(transition);
    });
  }

  private handleTierTransition(transition: TierTransition): void {
    if (this._state !== 'running') return;

    const newRoomIds = new Set(this.roomsForTier(transition.to));
    const currentRoomIds = new Set(this.activeRooms.keys());

    // Passivate rooms that are above the new tier
    for (const roomId of currentRoomIds) {
      if (!newRoomIds.has(roomId)) {
        this.passivateRoom(roomId);
      }
    }

    // Reactivate or boot rooms for the new tier
    for (const roomId of newRoomIds) {
      if (!currentRoomIds.has(roomId)) {
        if (this.passivatedRooms.has(roomId)) {
          this.reactivateRoom(roomId);
        } else {
          this.bootRoom(roomId);
        }
      }
    }

    // If current room was passivated, move player to home
    if (this.passivatedRooms.has(this.currentRoomId) || !this.activeRooms.has(this.currentRoomId)) {
      const fallback = this.activeRooms.keys().next().value ?? 'home';
      this.currentRoomId = fallback;
      const room = this.activeRooms.get(fallback);
      if (room) {
        this.emit({ type: 'room_changed', snapshot: this.filteredSnapshot(room) });
      }
    }

    // Carry a human sentence with the event. The rooms the user could see a
    // moment ago have just closed; saying nothing makes that look like a bug.
    this.emit({
      type: 'tier_changed',
      from: transition.from,
      to: transition.to,
      notice: describeTierChange(transition.from, transition.to, transition.snapshot),
    });
  }

  private passivateRoom(roomId: string): void {
    const room = this.activeRooms.get(roomId);
    if (!room) return;
    this.activeRooms.delete(roomId);
    room.shutdown();
    this.passivatedRooms.add(roomId);
  }

  private reactivateRoom(roomId: string): void {
    this.passivatedRooms.delete(roomId);
    this.bootRoom(roomId);
  }

  // ── Room boot ───────────────────────────────────────────────────────

  private async bootRoomsForTier(tier: Tier): Promise<void> {
    for (const roomId of this.roomsForTier(tier)) {
      await this.bootRoom(roomId);
    }
  }

  private async bootRoom(roomId: string): Promise<void> {
    if (this.activeRooms.has(roomId)) return;

    const def = ROOM_DEFINITIONS[roomId];
    if (!def) return;

    const room = new RoomEngine(roomId, this.journal);
    await delay(100); // Wait for recovery from journal

    // Filter exits: at T0/T1 the Study room's "out" exit targets Home which doesn't exist
    const tier = this.currentTier;
    const tierRoomIds = new Set(this.roomsForTier(tier));
    const exits = (roomId === 'study' && (tier === 'T0' || tier === 'T1'))
      ? def.exits.filter(e => tierRoomIds.has(e.targetRoom))
      : def.exits;

    if (room.state.name === '') {
      const roomName = roomId === 'home' ? this.homeRoomName : def.name;
      await room.send({
        type: 'create_room',
        name: roomName,
        description: def.description,
        zone: def.zone,
        exits,
        objects: def.objects,
      });
    }

    this.activeRooms.set(roomId, room);

    if (!this._roomsOnlyMode) {
      this.wireRoomNotifications(room, roomId);
    }
  }

  // ── Notification wiring ─────────────────────────────────────────────

  private wireRoomNotifications(room: RoomEngine, roomId: string): void {
    room.onEvent(event => {
      if (roomId !== this.currentRoomId) return;

      switch (event.type) {
        case 'said':
          this.emit({ type: 'prose', speaker: event.entityName, text: event.text });
          break;
        case 'emoted':
          this.emit({ type: 'prose', speaker: 'emote', text: `${event.entityName} ${event.text}` });
          break;
        case 'entity_entered':
          if (event.fromDirection !== 'materialization') {
            this.emit({ type: 'prose', speaker: 'narrator', text: `${event.entityName} ${event.entityName === 'You' ? 'enter' : 'enters'} from the ${event.fromDirection}.` });
          }
          break;
        case 'entity_left':
          // Second-person conjugation for the player's own echo ("You leave", not "You leaves") — task #30.
          this.emit({ type: 'prose', speaker: 'narrator', text: `${event.entityName} ${event.entityName === 'You' ? 'leave' : 'leaves'} to the ${event.direction}.` });
          break;
        case 'description_changed':
          this.emit({ type: 'state_changed', description: event.newDescription });
          break;
      }
    });
  }
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
