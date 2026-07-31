/**
 * WebSocket client with exponential backoff reconnection and seq-based replay.
 */

import { C2SMessage, serializeC2S, newId } from '../protocol/c2s';
import { S2CMessage, parseS2CMessage } from '../protocol/s2c';

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';
export type MessageHandler = (msg: S2CMessage) => void;
export type StateHandler = (state: ConnectionState) => void;

export class WyrdWebSocket {
  private ws: WebSocket | null = null;
  private serverUrl = '';
  private token: string | null = null;
  private locale = 'en';
  private currentRoomId: string | null = null;
  private lastSeenSeq = 0;
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private shouldReconnect = false;

  private messageHandlers: MessageHandler[] = [];
  private stateHandlers: StateHandler[] = [];
  private _state: ConnectionState = 'disconnected';

  get state(): ConnectionState {
    return this._state;
  }

  onMessage(handler: MessageHandler): () => void {
    this.messageHandlers.push(handler);
    return () => {
      this.messageHandlers = this.messageHandlers.filter(h => h !== handler);
    };
  }

  onStateChange(handler: StateHandler): () => void {
    this.stateHandlers.push(handler);
    return () => {
      this.stateHandlers = this.stateHandlers.filter(h => h !== handler);
    };
  }

  connect(serverUrl: string, token: string | null, locale: string = 'en'): void {
    this.serverUrl = serverUrl;
    this.token = token;
    this.locale = locale;
    this.lastSeenSeq = 0;
    this.shouldReconnect = true;
    this.attempt = 0;
    this.doConnect(false);
  }

  /** Update locale for reconnect URLs. */
  setLocale(locale: string): void {
    this.locale = locale;
  }

  /** Track current room for reconnect URL. */
  setCurrentRoomId(roomId: string): void {
    this.currentRoomId = roomId;
  }

  disconnect(): void {
    this.shouldReconnect = false;
    this.stopPing();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close(1000, 'Client disconnect');
    this.ws = null;
    this.setState('disconnected');
  }

  send(msg: C2SMessage): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(serializeC2S(msg));
    }
  }

  private doConnect(isReconnect: boolean): void {
    this.setState(isReconnect ? 'reconnecting' : 'connecting');

    let raw = this.serverUrl.trim().replace(/\/$/, '');
    // Auto-prepend scheme if missing
    if (!/^(https?|wss?):\/\//i.test(raw)) {
      raw = 'http://' + raw;
    }
    const base = raw
      .replace('http://', 'ws://')
      .replace('https://', 'wss://');

    let url = `${base}/ws`;
    const params: string[] = [];
    if (this.token) params.push(`token=${this.token}`);
    if (this.locale !== 'en') params.push(`locale=${this.locale}`);
    if (this.currentRoomId) params.push(`room=${this.currentRoomId}`);
    if (params.length > 0) url += `?${params.join('&')}`;

    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      this.setState('connected');
      this.attempt = 0;
      this.startPing();

      // Request replay if reconnecting
      if (isReconnect && this.lastSeenSeq > 0) {
        this.send({
          type: 'reconnect',
          id: newId(),
          roomId: '',
          lastSeenSeq: this.lastSeenSeq,
        });
      }
    };

    ws.onmessage = (event) => {
      const msg = parseS2CMessage(event.data as string);
      if (msg) {
        this.lastSeenSeq = msg.seq;
        for (const handler of this.messageHandlers) {
          handler(msg);
        }
      }
    };

    ws.onclose = () => {
      this.ws = null;
      this.stopPing();
      if (this.shouldReconnect) {
        this.scheduleReconnect();
      } else {
        this.setState('disconnected');
      }
    };

    ws.onerror = () => {
      // onclose will fire after onerror
    };
  }

  private scheduleReconnect(): void {
    this.setState('reconnecting');
    const maxBackoff = 30000;
    const delay = Math.min(1000 * Math.pow(2, Math.min(this.attempt, 5)), maxBackoff);
    const jitter = Math.random() * delay / 4;
    this.attempt++;

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.doConnect(true);
    }, delay + jitter);
  }

  private startPing(): void {
    this.stopPing();
    this.pingTimer = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        // Send a ping frame — RN WebSocket supports this via empty string
        // The server will respond with pong, keeping the connection alive
        try {
          this.ws.send(JSON.stringify({ type: 'ping', id: newId(), roomId: '' }));
        } catch { /* ignore */ }
      }
    }, 30_000);
  }

  private stopPing(): void {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  private setState(state: ConnectionState): void {
    this._state = state;
    for (const handler of this.stateHandlers) {
      handler(state);
    }
  }
}
