/**
 * Node stand-ins for the app's two iOS native modules, for the live rehearsal
 * test (rehearsal-live.test.ts). They do in node what the iOS code does on the
 * phone, so the app's own JS (RelaySocket, HouseholdTrust, NatsServerClient,
 * NativeNatsClient, WebSocketServerConnection …) runs unchanged against a real
 * relay and home:
 *
 *   HouseholdTrust  (WyrdHouseholdTrust.mm + WyrdTrustStore.mm): a pin store
 *     keyed exactly like WyrdTrustStore (WyrdNormalizePinKey), CA pins and leaf
 *     pins, and fetchServerCertificates.
 *   WyrdRelaySocket (WyrdRelaySocket.mm): one websocket per socket id, frames
 *     carried to JS as base64 events, binary `send`, text `sendText`.
 *
 * The TLS decision mirrors WyrdTrustStore +evaluateServerTrust for the
 * connection's host:port: CA pins first (node: `ca` = the pinned CA only,
 * chain and host name verified — the iOS anchor-only evaluation), then leaf
 * pins (the served leaf's SHA-256 must be pinned), else the system's default
 * trust. Node needs the CA certificate itself, not only its fingerprint, so the
 * harness hands in PEMs; one is used only when its SHA-256 equals the pin the
 * app set from the invite. Difference from iOS: node does not require the
 * pinned CA to be in the served chain.
 */
/* eslint-disable @typescript-eslint/no-var-requires */
import { createHash, X509Certificate } from 'crypto';
import * as tls from 'tls';
import * as https from 'https';
import { isIP } from 'net';

type Listener = (ev: Record<string, unknown>) => void;

export interface Frame {
  socket: string;
  url: string;
  dir: 'out' | 'in';
  text: string;
}

function derOfPem(pem: string): Buffer {
  return Buffer.from(pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, ''), 'base64');
}

function colonHex(buf: Buffer): string {
  return (buf.toString('hex').toUpperCase().match(/.{2}/g) ?? []).join(':');
}

/** WyrdNormalizePinKey. */
export function normalizeKey(key: string): string {
  let k = key.toLowerCase();
  if (k.startsWith('[')) {
    const close = k.indexOf(']');
    if (close > 0) k = k.slice(1, close) + k.slice(close + 1);
  }
  return k;
}

/** WyrdPinKey for a URL the native side is asked to connect to. */
export function keyForUrl(url: string): string {
  const u = new URL(url.replace(/^wss:/, 'https:').replace(/^ws:/, 'http:'));
  const host = u.hostname.replace(/^\[|\]$/g, '').toLowerCase();
  const port = u.port ? Number(u.port) : u.protocol === 'https:' ? 443 : 80;
  return `${host}:${port}`;
}

export function createShims(caPems: string[]) {
  const pemByFp = new Map<string, string>();
  for (const pem of caPems) pemByFp.set(createHash('sha256').update(derOfPem(pem)).digest('hex').toUpperCase(), pem);

  const leafPins = new Map<string, Set<string>>();
  const caPins = new Map<string, Set<string>>();
  const add = (m: Map<string, Set<string>>, k: string, v: string) => {
    const key = normalizeKey(k);
    if (!m.has(key)) m.set(key, new Set());
    m.get(key)!.add(v);
  };

  const trust = {
    async addTrustedCert(key: string, pem: string): Promise<boolean> {
      add(leafPins, key, colonHex(createHash('sha256').update(derOfPem(pem)).digest()));
      return true;
    },
    async pinFingerprint(key: string, fp: string): Promise<boolean> {
      add(leafPins, key, fp.toUpperCase());
      return true;
    },
    async pinCaFingerprint(key: string, fp: string): Promise<boolean> {
      const hex = fp.replace(/[:\s]/g, '').toUpperCase();
      if (!key || hex.length !== 64) throw new Error('host and a SHA-256 fingerprint are required');
      add(caPins, key, hex);
      return true;
    },
    async removeTrustedCert(key: string): Promise<boolean> {
      leafPins.delete(normalizeKey(key));
      caPins.delete(normalizeKey(key));
      return true;
    },
    async listTrustedHosts() {
      return [...leafPins.keys()].map((host) => ({ host, subject: host, validUntil: 0 }));
    },
    /** Read the served chain without trusting it (the iOS probe cancels after the handshake). */
    fetchServerCertificates(host: string, port: number): Promise<Array<{ pem: string; fingerprint: string }>> {
      return new Promise((resolve, reject) => {
        const s = tls.connect({ host, port, rejectUnauthorized: false, servername: isIP(host) ? undefined : host }, () => {
          const out: Array<{ pem: string; fingerprint: string }> = [];
          let c = s.getPeerCertificate(true) as tls.DetailedPeerCertificate | undefined;
          const seen = new Set<string>();
          while (c && c.raw && !seen.has(c.fingerprint256)) {
            seen.add(c.fingerprint256);
            const x = new X509Certificate(c.raw);
            out.push({ pem: x.toString(), fingerprint: c.fingerprint256 });
            c = c.issuerCertificate;
          }
          s.end();
          resolve(out);
        });
        s.on('error', reject);
        s.setTimeout(8000, () => { s.destroy(); reject(new Error('probe timed out')); });
      });
    },
    // Inspection for the test.
    _leafPins: leafPins,
    _caPins: caPins,
  };

  /** What WyrdTrustStore would decide for this endpoint, as node TLS options. */
  function tlsFor(key: string): { opts: Record<string, unknown>; leaf?: Set<string>; why: string } {
    const cas = caPins.get(normalizeKey(key));
    if (cas && cas.size > 0) {
      const pems = [...cas].map((fp) => pemByFp.get(fp)).filter((p): p is string => !!p);
      if (pems.length === 0) return { opts: { ca: [], rejectUnauthorized: true }, why: 'CA pin without its certificate' };
      return { opts: { ca: pems, rejectUnauthorized: true }, why: 'CA pin' };
    }
    const leaf = leafPins.get(normalizeKey(key));
    if (leaf && leaf.size > 0) return { opts: { rejectUnauthorized: false }, leaf, why: 'leaf pin' };
    return { opts: { rejectUnauthorized: true }, why: 'no pin (system trust)' };
  }

  const listeners = new Set<Listener>();
  const emit = (ev: Record<string, unknown>) => { for (const l of [...listeners]) l(ev); };
  const sockets = new Map<string, { ws: any; url: string }>();
  const frames: Frame[] = [];
  const decisions: Array<{ url: string; key: string; why: string }> = [];

  const socket = {
    connect(id: string, url: string): void {
      const WS = require('ws');
      const key = keyForUrl(url);
      const t = tlsFor(key);
      decisions.push({ url: url.replace(/([?&](token|device_token)=)[^&]+/g, '$1<redacted>'), key, why: t.why });
      const host = new URL(url.replace(/^wss:/, 'https:')).hostname;
      const ws = new WS(url, { ...t.opts, servername: isIP(host) ? undefined : host, handshakeTimeout: 10000 });
      sockets.set(id, { ws, url });
      ws.on('upgrade', (res: { socket: tls.TLSSocket }) => {
        if (!t.leaf) return;
        const fp = res.socket.getPeerCertificate().fingerprint256?.toUpperCase();
        if (!fp || !t.leaf.has(fp)) {
          emit({ id, type: 'error', message: `pin mismatch for ${key}: served ${fp}` });
          ws.terminate();
        }
      });
      ws.on('open', () => emit({ id, type: 'open' }));
      ws.on('message', (data: Buffer | string) => {
        const buf = typeof data === 'string' ? Buffer.from(data, 'utf8') : Buffer.from(data);
        frames.push({ socket: id, url, dir: 'in', text: buf.toString('utf8') });
        emit({ id, type: 'message', data: buf.toString('base64') });
      });
      ws.on('error', (e: Error) => emit({ id, type: 'error', message: e.message }));
      ws.on('close', (code: number, reason: string) => {
        emit({ id, type: 'closing', code, reason: String(reason ?? '') });
        emit({ id, type: 'closed' });
        sockets.delete(id);
      });
    },
    send(id: string, base64: string): void {
      const s = sockets.get(id);
      if (!s) return;
      const buf = Buffer.from(base64, 'base64');
      frames.push({ socket: id, url: s.url, dir: 'out', text: buf.toString('utf8') });
      s.ws.send(buf, { binary: true });
    },
    sendText(id: string, text: string): void {
      const s = sockets.get(id);
      if (!s) return;
      frames.push({ socket: id, url: s.url, dir: 'out', text });
      s.ws.send(text);
    },
    close(id: string): void {
      sockets.get(id)?.ws.close();
    },
  };

  /** NativeEventEmitter as RN gives it: listeners for the module's single event. */
  class Emitter {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    constructor(_mod?: unknown) {}
    addListener(_event: string, l: Listener) {
      listeners.add(l);
      return { remove: () => { listeners.delete(l); } };
    }
    removeAllListeners() { /* per-socket listeners remove themselves */ }
  }

  /**
   * fetch() as the app gets it on iOS: RN's HTTP handler with the app's TLS
   * challenge hook (WyrdFetchPinning), i.e. the same pin decision per host:port.
   */
  async function pinnedFetch(url: string, init?: { method?: string; headers?: Record<string, string>; body?: string }) {
    const u = new URL(url);
    const t = u.protocol === 'https:' ? tlsFor(keyForUrl(url)) : { opts: {}, why: 'plain' as const };
    if (u.protocol === 'https:') decisions.push({ url: `${u.origin}${u.pathname}`, key: keyForUrl(url), why: t.why });
    return new Promise<{ ok: boolean; status: number; json: () => Promise<any>; text: () => Promise<string> }>((resolve, reject) => {
      const req = https.request({
        method: init?.method ?? 'GET', host: u.hostname, port: u.port || 443, path: `${u.pathname}${u.search}`,
        headers: init?.headers, servername: isIP(u.hostname) ? undefined : u.hostname, ...t.opts,
      }, (res) => {
        if ('leaf' in t && t.leaf) {
          const fp = (res.socket as tls.TLSSocket).getPeerCertificate().fingerprint256?.toUpperCase();
          if (!fp || !t.leaf.has(fp)) { res.destroy(); reject(new Error('pin mismatch')); return; }
        }
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (c) => { body += c; });
        res.on('end', () => resolve({
          ok: (res.statusCode ?? 0) >= 200 && (res.statusCode ?? 0) < 300,
          status: res.statusCode ?? 0,
          json: async () => JSON.parse(body),
          text: async () => body,
        }));
      });
      req.on('error', reject);
      req.setTimeout(10000, () => req.destroy(new Error('timed out')));
      if (init?.body) req.write(init.body);
      req.end();
    });
  }

  /** Cut the network under every open socket to `url`'s host:port (as a lost Wi-Fi would). */
  function dropConnections(url: string): number {
    const key = keyForUrl(url);
    let n = 0;
    for (const s of sockets.values()) {
      if (keyForUrl(s.url) === key) { s.ws.terminate(); n++; }
    }
    return n;
  }

  return { trust, socket, Emitter, pinnedFetch, frames, decisions, dropConnections, openSockets: () => sockets.size };
}
