/**
 * RelaySocket — a thin WebSocket-shaped wrapper that NativeNatsClient drives.
 *
 * On iOS, the global `WebSocket` (RN's SocketRocket-backed RCTWebSocketModule)
 * CANNOT pin a self-signed household relay leaf: under RN 0.83 New Architecture
 * `RCTSetCustomSRWebSocketProvider` is not honoured, so our custom
 * `SRSecurityPolicy` never runs and the relay handshake dies with
 * `Trust evaluate failure: [root AnchorTrusted]`. So on iOS we drive the native
 * `WyrdRelaySocket` module (NSURLSessionWebSocketTask + serverTrust pinning
 * against the household-CA allowlist the HouseholdTrust module populates).
 *
 * On Android the OkHttp-backed JS WebSocket already pins via the
 * HouseholdTrustManager, so we fall through to the global `WebSocket` there
 * (and anywhere the native module is absent — e.g. web/tests).
 *
 * This wrapper exposes ONLY the surface NativeNatsClient relies on:
 *   - `binaryType` (settable; ignored by the native path, honoured by the JS
 *     fallback)
 *   - `onopen` / `onmessage` / `onerror` / `onclose` handler properties
 *   - `send(text)` / `close()`
 * The NATS protocol logic in NativeNatsClient is untouched.
 */
import { NativeEventEmitter, NativeModules, Platform } from 'react-native';

/** The minimal MessageEvent shape consumers read (`event.data`). The native
 *  path always delivers an ArrayBuffer (raw frame bytes), like a real
 *  binaryType='arraybuffer' WebSocket — nats.ws requires ArrayBuffers and
 *  NativeNatsClient handles them too. */
interface RelayMessageEvent {
  data: string | ArrayBuffer;
}

/** Anything a WebSocket-style `send()` accepts that we can serialise to bytes. */
type SendData = string | ArrayBuffer | ArrayBufferView;

/** Native module surface (legacy bridge: NativeModules.WyrdRelaySocket). The
 *  native `send` takes BASE64 of the raw bytes to transmit (RelaySocket encodes
 *  whatever the caller passed); the native side decodes + sends a binary frame. */
interface NativeRelaySocketModule {
  connect(socketId: string, url: string): void;
  send(socketId: string, base64: string): void;
  close(socketId: string): void;
}

/** Uint8Array -> base64. Uses the global btoa (present in RN/Hermes). */
function bytesToBase64(u8: Uint8Array): string {
  let bin = '';
  const chunk = 0x8000;
  for (let i = 0; i < u8.length; i += chunk) {
    bin += String.fromCharCode.apply(
      null,
      u8.subarray(i, i + chunk) as unknown as number[],
    );
  }
  // eslint-disable-next-line no-undef
  return btoa(bin);
}

/** base64 -> Uint8Array. Uses the global atob (present in RN/Hermes). */
function base64ToBytes(b64: string): Uint8Array {
  // eslint-disable-next-line no-undef
  const bin = atob(b64);
  const u8 = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
  return u8;
}

/** Coerce a WebSocket-style send() argument to the raw bytes to transmit. */
function sendDataToBytes(data: SendData): Uint8Array {
  if (typeof data === 'string') return new TextEncoder().encode(data);
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  // ArrayBufferView (Uint8Array, DataView, …) — copy the exact byte window.
  return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
}

/** The single event payload the native module emits (see WyrdRelaySocket.mm). */
interface NativeRelaySocketEvent {
  id: string;
  type: 'open' | 'message' | 'closing' | 'closed' | 'error';
  data?: string;
  message?: string;
  code?: number;
  reason?: string;
}

const nativeRelaySocket: NativeRelaySocketModule | null =
  Platform.OS === 'ios' && NativeModules.WyrdRelaySocket
    ? (NativeModules.WyrdRelaySocket as NativeRelaySocketModule)
    : null;

/** Whether the native pinned relay socket is available (iOS + module linked). */
export function nativeRelaySocketAvailable(): boolean {
  return nativeRelaySocket != null;
}

let nextSocketId = 1;

/**
 * Native-backed RelaySocket. Each instance owns a unique socketId so multiple
 * concurrent relay sockets don't cross. It subscribes to the module's single
 * event and filters by socketId, mapping native lifecycle phases onto the
 * WebSocket-style handler properties.
 */
class NativeRelaySocket {
  // WebSocket-compat fields NativeNatsClient touches.
  binaryType: 'arraybuffer' | 'blob' = 'arraybuffer';
  onopen: ((ev?: unknown) => void) | null = null;
  onmessage: ((ev: RelayMessageEvent) => void) | null = null;
  onerror: ((ev?: unknown) => void) | null = null;
  onclose: ((ev?: unknown) => void) | null = null;

  private readonly socketId: string;
  private readonly module: NativeRelaySocketModule;
  private readonly emitter: NativeEventEmitter;
  private subscription: { remove: () => void } | null = null;
  private closed = false;

  constructor(url: string, module: NativeRelaySocketModule) {
    this.socketId = `relay-${nextSocketId++}`;
    this.module = module;
    // The native module IS a non-null argument, so this NativeEventEmitter
    // construction is safe on iOS (the RNFS invariant-violation footgun is
    // about passing an absent module).
    this.emitter = new NativeEventEmitter(NativeModules.WyrdRelaySocket);
    this.subscription = this.emitter.addListener(
      'wyrd_relay_socket_event',
      (ev: NativeRelaySocketEvent) => this.handleEvent(ev),
    );
    // Connect after listeners are wired so we never miss the open/message burst.
    this.module.connect(this.socketId, url);
  }

  private handleEvent(ev: NativeRelaySocketEvent): void {
    if (ev.id !== this.socketId) return; // Not our socket.
    switch (ev.type) {
      case 'open':
        this.onopen?.();
        break;
      case 'message':
        // The native side hands us BASE64 of the raw frame bytes. Decode to an
        // ArrayBuffer and deliver it exactly as a binaryType='arraybuffer'
        // WebSocket would — nats.ws does `new Uint8Array(event.data)` and
        // NativeNatsClient handles ArrayBuffer too.
        if (typeof ev.data === 'string') {
          const bytes = base64ToBytes(ev.data);
          this.onmessage?.({ data: bytes.buffer as ArrayBuffer });
        }
        break;
      case 'error':
        this.onerror?.({ message: ev.message });
        break;
      case 'closing':
        // No discrete CLOSING callback on the JS WebSocket surface
        // NativeNatsClient uses; the terminal 'closed' drives onclose.
        break;
      case 'closed':
        this.cleanupListener();
        this.onclose?.({ code: ev.code, reason: ev.reason });
        break;
    }
  }

  private cleanupListener(): void {
    if (this.subscription) {
      this.subscription.remove();
      this.subscription = null;
    }
  }

  send(data: SendData): void {
    if (this.closed) return;
    this.module.send(this.socketId, bytesToBase64(sendDataToBytes(data)));
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.module.close(this.socketId);
    // The native 'closed' event fires onclose + cleans the listener; if the
    // module never reports back (shouldn't happen), drop the listener anyway.
    this.cleanupListener();
  }
}

/**
 * The surface NativeNatsClient consumes. Identical to the subset of the DOM
 * WebSocket it uses, so it can hold either a NativeRelaySocket or a real
 * WebSocket.
 */
export interface RelaySocketLike {
  binaryType: 'arraybuffer' | 'blob';
  onopen: ((ev?: unknown) => void) | null;
  onmessage: ((ev: RelayMessageEvent) => void) | null;
  onerror: ((ev?: unknown) => void) | null;
  onclose: ((ev?: unknown) => void) | null;
  send(text: string): void;
  close(): void;
}

/**
 * Construct a relay socket. Uses the native pinned socket on iOS when the
 * module is linked; otherwise the global WebSocket (Android OkHttp-pinned, web,
 * tests). Returns a RelaySocketLike either way.
 */
export function createRelaySocket(url: string): RelaySocketLike {
  if (nativeRelaySocket) {
    return new NativeRelaySocket(url, nativeRelaySocket) as unknown as RelaySocketLike;
  }
  // eslint-disable-next-line no-undef
  const ws = new WebSocket(url);
  return ws as unknown as RelaySocketLike;
}
