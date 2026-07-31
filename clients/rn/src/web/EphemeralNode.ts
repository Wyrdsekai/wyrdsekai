/**
 * EphemeralNode — orchestrates web-based node capabilities.
 *
 * An ephemeral node is a browser tab acting as a lightweight Wyrdsekai node.
 * It combines:
 *   - WebLLM for in-browser inference (WebGPU)
 *   - IndexedDB for persistent storage (events + vitality)
 *   - Web Crypto for Ed25519 identity
 *   - NATS over WebSocket for Between participation
 *   - RoomEngine + CompanionEngine for local world simulation
 *   - ServiceWorker for offline caching
 *
 * The node is "ephemeral" because it exists only while the tab is open.
 * State is persisted to IndexedDB but compute is transient.
 */

import { WebLLMService, WEB_MODEL_CATALOG } from './WebLLMService';
import { WebInferenceRouter } from './WebInferenceRouter';
import { WebStorageService, webStorage } from './WebStorageService';
import { WebCryptoService } from './WebCryptoService';
import { IndexedDBEventJournal } from './IndexedDBEventJournal';
import { IndexedDBVitalityStore } from './IndexedDBVitalityStore';
import { NatsClient } from './NatsClient';
import { BetweenBridge } from './BetweenBridge';
import { ServiceWorkerCache, serviceWorkerCache } from './ServiceWorkerCache';
import { RoomEngine } from '../engine/room/RoomEngine';
import { CompanionEngine } from '../engine/agent/CompanionEngine';
import { NEXUS_COMPANION } from '../engine/agent/AgentProfile';
import { toSnapshot } from '../engine/room/RoomState';
import type { PhoneNodeEvent } from '../engine/PhoneNode';
import type { RoomSnapshot } from '../protocol/models';

export type EphemeralNodeState = 'stopped' | 'starting' | 'running' | 'error';

export interface EphemeralNodeCapabilities {
  webgpu: boolean;
  webCrypto: boolean;
  indexedDB: boolean;
  serviceWorker: boolean;
}

export class EphemeralNode {
  private _state: EphemeralNodeState = 'stopped';
  private _error: string | null = null;
  private _stateListeners: Array<(state: EphemeralNodeState) => void> = [];
  private eventListeners: Array<(event: PhoneNodeEvent) => void> = [];

  readonly webLLM: WebLLMService;
  readonly inferenceRouter: WebInferenceRouter;
  readonly storage: WebStorageService;
  readonly crypto: WebCryptoService;
  readonly natsClient: NatsClient;
  readonly serviceWorker: ServiceWorkerCache;

  private journal: IndexedDBEventJournal | null = null;
  private vitalityStore: IndexedDBVitalityStore | null = null;
  private betweenBridge: BetweenBridge | null = null;

  /** @deprecated Use homeRoom instead */
  get nexusRoom(): RoomEngine | null { return this.homeRoom; }
  homeRoom: RoomEngine | null = null;
  terminalRoom: RoomEngine | null = null;
  companion: CompanionEngine | null = null;
  private currentRoomId = 'home';

  constructor() {
    this.webLLM = new WebLLMService();
    this.inferenceRouter = new WebInferenceRouter(this.webLLM);
    this.storage = webStorage;
    this.crypto = new WebCryptoService();
    this.natsClient = new NatsClient();
    this.serviceWorker = serviceWorkerCache;
  }

  get state(): EphemeralNodeState {
    return this._state;
  }

  get error(): string | null {
    return this._error;
  }

  get natsConnected(): boolean {
    return this.natsClient.state === 'connected';
  }

  /** Subscribe to phone-node-style events. */
  onEvent(listener: (event: PhoneNodeEvent) => void): () => void {
    this.eventListeners.push(listener);
    return () => {
      this.eventListeners = this.eventListeners.filter(l => l !== listener);
    };
  }

  private emit(event: PhoneNodeEvent): void {
    for (const listener of this.eventListeners) {
      listener(event);
    }
  }

  static detectCapabilities(): EphemeralNodeCapabilities {
    return {
      webgpu: WebLLMService.isWebGPUSupported(),
      webCrypto: typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined',
      indexedDB: typeof indexedDB !== 'undefined',
      serviceWorker: typeof navigator !== 'undefined' && 'serviceWorker' in navigator,
    };
  }

  /**
   * Initialize the ephemeral node — sets up IndexedDB storage, rooms,
   * and transitions to 'running' state.
   */
  async initialize(): Promise<void> {
    this.setState('starting');
    this._error = null;

    try {
      // Initialize storage
      await this.storage.init();

      // Initialize event journal + vitality store
      const journal = new IndexedDBEventJournal();
      await journal.init();
      this.journal = journal;
      this.vitalityStore = new IndexedDBVitalityStore(this.storage);

      // Boot rooms
      const home = new RoomEngine('home', journal);
      this.homeRoom = home;

      await delay(50);

      if (home.state.name === '') {
        await home.send({
          type: 'create_room',
          name: 'Home',
          description: 'A warm, quiet space that feels distinctly yours. Soft light pools in the corners. A comfortable chair sits near a low table. This is where your companion lives \u2014 the starting point for everything.',
          zone: 'foundation',
          exits: [{ direction: 'north', targetRoom: 'terminal', label: 'A corridor leads to The Terminal' }],
          objects: [{ id: 'obj-crystal-01', name: 'crystal', description: 'A pulsing crystal that reveals hidden connections', takeable: false }],
        });
      }

      const terminal = new RoomEngine('terminal', journal);
      this.terminalRoom = terminal;

      await delay(50);

      if (terminal.state.name === '') {
        await terminal.send({
          type: 'create_room',
          name: 'The Terminal',
          description: 'Banks of crystalline screens line the walls, each displaying streams of data. A command prompt blinks steadily, awaiting input.',
          zone: 'foundation',
          exits: [{ direction: 'south', targetRoom: 'home', label: 'Back to Home' }],
        });
      }

      // Wire room notifications
      this.wireRoomNotifications(home, 'home');
      this.wireRoomNotifications(terminal, 'terminal');

      // Register service worker for offline caching
      if (ServiceWorkerCache.isSupported()) {
        this.serviceWorker.register().catch(() => {});
      }

      this.setState('running');
    } catch (e: unknown) {
      this._error = e instanceof Error ? e.message : 'Failed to initialize';
      this.setState('error');
    }
  }

  /** Start the companion engine (requires a loaded WebLLM model). */
  async startCompanion(): Promise<void> {
    if (!this.homeRoom || !this.inferenceRouter.canInferLocally()) return;

    const comp = new CompanionEngine(
      NEXUS_COMPANION,
      this.homeRoom,
      this.inferenceRouter,
      this.vitalityStore,
    );
    this.companion = comp;
    await comp.start();
  }

  /** Connect to household NATS for Between participation. */
  async connectNats(url: string): Promise<void> {
    await this.natsClient.connect(url);

    // Bridge rooms to NATS
    this.betweenBridge = new BetweenBridge(this.natsClient);
    if (this.homeRoom) this.betweenBridge.bridgeRoom(this.homeRoom);
    if (this.terminalRoom) this.betweenBridge.bridgeRoom(this.terminalRoom);
  }

  async loadModel(
    modelId: string,
    onProgress?: (progress: { text: string; progress: number }) => void,
  ): Promise<void> {
    await this.webLLM.loadModel(modelId, onProgress);
  }

  async unloadModel(): Promise<void> {
    await this.webLLM.unloadModel();
  }

  getAvailableModels() {
    return WEB_MODEL_CATALOG;
  }

  currentRoom(): RoomEngine | null {
    switch (this.currentRoomId) {
      case 'home': return this.homeRoom;
      case 'terminal': return this.terminalRoom;
      default: return this.homeRoom;
    }
  }

  async say(entityId: string, entityName: string, text: string): Promise<void> {
    await this.currentRoom()?.send({ type: 'say_in_room', entityId, entityName, text });
  }

  async go(entityId: string, entityName: string, direction: string): Promise<void> {
    const room = this.currentRoom();
    if (!room) return;

    const exit = room.state.exits[direction];
    if (!exit) {
      this.emit({ type: 'error', code: 'no_exit', message: 'There is no exit in that direction.' });
      return;
    }

    await room.send({ type: 'leave_room', entityId, entityName, direction });
    this.currentRoomId = exit.targetRoom;
    const targetRoom = this.currentRoom();
    if (targetRoom) {
      await targetRoom.send({ type: 'enter_room', entityId, entityName, entityType: 'player', fromDirection: direction });
      this.emit({ type: 'room_changed', snapshot: toSnapshot(targetRoom.state) });
    }
  }

  look(): RoomSnapshot | null {
    const room = this.currentRoom();
    return room ? toSnapshot(room.state) : null;
  }

  async shutdown(): Promise<void> {
    this.companion?.shutdown();
    this.homeRoom?.shutdown();
    this.terminalRoom?.shutdown();
    this.betweenBridge?.shutdown();
    await this.natsClient.disconnect();
    await this.webLLM.unloadModel();

    this.companion = null;
    this.homeRoom = null;
    this.terminalRoom = null;
    this.betweenBridge = null;

    this.setState('stopped');
    this._error = null;
  }

  onStateChange(listener: (state: EphemeralNodeState) => void): () => void {
    this._stateListeners.push(listener);
    return () => {
      this._stateListeners = this._stateListeners.filter(l => l !== listener);
    };
  }

  private setState(state: EphemeralNodeState): void {
    this._state = state;
    this._stateListeners.forEach(l => l(state));
  }

  private wireRoomNotifications(room: RoomEngine, roomId: string): void {
    room.onEvent(event => {
      if (roomId !== this.currentRoomId) return;

      switch (event.type) {
        case 'said':
          this.emit({ type: 'prose', speaker: event.entityName, text: event.text });
          break;
        case 'entity_entered':
          this.emit({ type: 'prose', speaker: 'narrator', text: `${event.entityName} enters from the ${event.fromDirection}.` });
          break;
        case 'entity_left':
          this.emit({ type: 'prose', speaker: 'narrator', text: `${event.entityName} leaves to the ${event.direction}.` });
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
