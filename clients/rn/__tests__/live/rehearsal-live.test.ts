/**
 * LIVE — the app's own connection code against a running rehearsal relay and
 * home ( W2/W3). Skipped unless
 *   RN_REHEARSAL_ENV=<env.json of the rehearsal>
 * and, optionally, RN_REHEARSAL_INVITE=<file holding a fresh wyrdphone:// url>
 * (else env.json's phone_invite.raw). Run with --forceExit (keep-alive sockets linger).
 *
 * Real modules throughout: phoneInvite, addInviteToBank/zoneBankStore,
 * HouseholdTrust (pins per host:port), openZone → zoneConnect → NatsServerClient
 * (sealed requests over nats.ws), RelaySocket, RelayTunnelServerConnection
 * (sealed tunnel), PairingClient + appModeStore.setPairingCredentials, homeBus,
 * NatsBetweenAdapter/NativeNatsClient + PhoneNode.setBetween(busUser) +
 * StudySyncLayer, PhoneNode's server-room visit → WebSocketServerConnection.
 * Only the two iOS native modules are node stand-ins (nodeNativeShims.ts), and
 * secure storage is a map (MMKV does not run in node). Nothing secret is printed.
 */
import * as fs from 'fs';
import * as path from 'path';

const ENV_PATH = process.env.RN_REHEARSAL_ENV;
const env: any = ENV_PATH ? JSON.parse(fs.readFileSync(ENV_PATH, 'utf8')) : null;
const caPems = env ? [env.home.home_ca_pem_path, env.relay.ca_pem_path].map((p: string) => fs.readFileSync(p, 'utf8')) : [];
// eslint-disable-next-line @typescript-eslint/no-var-requires
const mockShims = require('./nodeNativeShims').createShims(caPems);

const mockMem = new Map<string, string>();
jest.mock('../../src/state/secureStorage', () => ({
  secureStorage: {
    async getItem(k: string) { return mockMem.has(k) ? mockMem.get(k)! : null; },
    async setItem(k: string, v: string) { mockMem.set(k, String(v)); },
    async removeItem(k: string) { mockMem.delete(k); },
    async keys() { return [...mockMem.keys()]; },
  },
}));
jest.mock('react-native', () => {
  const actual = jest.requireActual('../../__mocks__/react-native');
  actual.Platform.OS = 'ios';
  actual.NativeModules.HouseholdTrust = mockShims.trust;
  actual.NativeModules.WyrdRelaySocket = mockShims.socket;
  actual.NativeEventEmitter = mockShims.Emitter;
  return actual;
});

import { parsePhoneInvite, type PhoneInvite } from '../../src/network/phoneInvite';
import { addInviteToBank } from '../../src/server/addInviteToBank';
import { useZoneBankStore } from '../../src/state/zoneBankStore';
import { restoreNativePins, pinHomeCa } from '../../src/server/HouseholdTrust';
import { openZone } from '../../src/server/openZone';
import { NatsServerClient } from '../../src/server/NatsServerClient';
import { RelayTunnelServerConnection } from '../../src/engine/transit/RelayTunnelServerConnection';
import { requestPairing, verifyCode, type PairingCredentials } from '../../src/network/PairingClient';
import { useAppModeStore } from '../../src/state/appModeStore';
import { HOME_BUS_URL_KEY, HOME_NATS_PASS_KEY, HOME_NATS_USER_KEY, resolveHomeBus } from '../../src/server/homeBus';
import { NatsBetweenAdapter } from '../../src/engine/between/NatsBetweenAdapter';
import { PhoneNode, type PhoneNodeEvent } from '../../src/engine/PhoneNode';
import { InMemoryEventJournal } from '../../src/engine/persistence/InMemoryEventJournal';
import { InMemoryVitalityStore } from '../../src/engine/persistence/InMemoryVitalityStore';
import { AsyncStorageStudyStore } from '../../src/engine/study/AsyncStorageStudyStore';
import type { S2CMessage } from '../../src/protocol/s2c';
import { createMockAsyncStorage } from '../helpers/mockAsyncStorage';

const live = env ? describe : describe.skip;
jest.setTimeout(120_000);

const lines: string[] = [];
const say = (s: string) => { lines.push(s); };

function waitFor<T>(what: string, fn: () => T | null | undefined | false, ms = 15_000): Promise<T> {
  const t0 = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      let v: T | null | undefined | false;
      try { v = fn(); } catch { v = null; }
      if (v) { resolve(v as T); return; }
      if (Date.now() - t0 > ms) { reject(new Error(`timed out waiting for ${what}`)); return; }
      setTimeout(tick, 100);
    };
    tick();
  });
}

const mockInference = { async complete() { return { content: 'ok', promptTokens: 1, completionTokens: 1 }; } };

// eslint-disable-next-line @typescript-eslint/no-var-requires
const { keyForUrl } = require('./nodeNativeShims');
const sameConn = (a: string, b: string) => keyForUrl(a) === keyForUrl(b);

/** Frames on one connection (nats.ws hands the socket "wss://host:port/"). */
function framesOf(url: string, dir: 'in' | 'out', from = 0): Array<{ text: string }> {
  return mockShims.frames.slice(from).filter((f: any) => f.dir === dir && sameConn(f.url, url));
}

/** NATS protocol ops on one connection: [{op, subject, payload?}] (PUB/SUB sent, MSG received). */
function sentOps(url: string, dir: 'in' | 'out' = 'out', from = 0): Array<{ op: string; subject: string; payload?: string }> {
  const text = framesOf(url, dir, from).map((f) => f.text).join('');
  const ops: Array<{ op: string; subject: string; payload?: string }> = [];
  const re = /(?:^|\r\n)(SUB|PUB|HPUB|MSG) (\S+)[^\r]*(?=\r\n)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    const op = { op: m[1], subject: m[2] } as { op: string; subject: string; payload?: string };
    if (m[1] === 'PUB' || m[1] === 'MSG') {
      const start = re.lastIndex + 2;
      const end = text.indexOf('\r\n', start);
      op.payload = text.slice(start, end < 0 ? undefined : end);
    }
    ops.push(op);
  }
  return ops;
}

/** Server error lines (-ERR …) received on one connection. */
function serverErrors(url: string): string[] {
  return framesOf(url, 'in').flatMap((f) => f.text.split('\r\n')).filter((l) => l.startsWith('-ERR'));
}

live('RN app against the live rehearsal (relay + home)', () => {
  const inviteUrl: string = (process.env.RN_REHEARSAL_INVITE
    ? fs.readFileSync(process.env.RN_REHEARSAL_INVITE, 'utf8')
    : env?.phone_invite?.raw ?? '').trim();
  const ada = env?.people?.steward ?? {};
  let invite: PhoneInvite;
  let zone: string;
  let relayClient: NatsServerClient;
  let pairing: PairingCredentials;
  const secrets = (): string[] => [
    ada.password, invite?.relays?.[0]?.natsPassword, pairing?.token, pairing?.nats_pass,
    relayClient?.getToken?.() ?? undefined, mockMem.get(HOME_NATS_PASS_KEY),
  ].filter((s): s is string => !!s);
  const clean = (s: string) => secrets().reduce((acc, x) => acc.split(x).join('<redacted>'), s);

  beforeAll(async () => {
    (globalThis as { fetch: unknown }).fetch = mockShims.pinnedFetch;
    await useZoneBankStore.getState().loadFromStorage();
    await restoreNativePins();
  });

  afterAll(() => {
    // eslint-disable-next-line no-console
    console.log(['', '── live rehearsal evidence ──', ...lines.map(clean),
      '── TLS decisions (pin key → decision) ──',
      ...[...new Map(mockShims.decisions.map((d: any) => [`${d.key} ${d.why}`, `${d.key.padEnd(22)} ${d.why.padEnd(24)} ${d.url}`])).values()] as string[],
    ].join('\n'));
  });

  it('1. parses a real invite (relay, zk, home_ca_fp, lan_https, home_bus)', () => {
    invite = parsePhoneInvite(inviteUrl);
    zone = invite.zoneId!;
    expect(zone).toBe(env.home.zone_id);
    expect(invite.relays[0].wsUrl).toBe(env.relay.phone_ws_url);
    expect(invite.zk).toBe(env.home.zk);
    expect(invite.homeCaFp).toBe(env.home.home_ca_fp.toLowerCase());
    expect(invite.lanHttps).toBe(env.home.lan_https);
    expect(invite.homeBus).toBe(env.home.home_bus_wss_url);
    say(`invite: zone=${zone} relay=${invite.relays[0].wsUrl} lan_https=${invite.lanHttps} home_bus=${invite.homeBus} zk=${invite.zk!.slice(0, 8)}…`);
  });

  it('2. banks it and pins relay and home apart on the same machine (host:port keys)', async () => {
    const added = addInviteToBank(inviteUrl, { username: ada.username });
    expect(added?.zoneId).toBe(zone);
    await restoreNativePins();
    const relayKey = new URL(invite.relays[0].wsUrl.replace('wss:', 'https:')).host;
    const lanKey = new URL(invite.lanHttps!).host;
    const busKey = new URL(invite.homeBus!.replace('wss:', 'https:')).host;
    expect(new Set([relayKey, lanKey, busKey]).size).toBe(3);
    expect(relayKey.split(':')[0]).toBe(lanKey.split(':')[0]); // one machine
    expect([...mockShims.trust._leafPins.keys()]).toEqual([relayKey]);
    expect([...mockShims.trust._caPins.keys()].sort()).toEqual([busKey, lanKey].sort());
    say(`pins: leaf ${relayKey}; CA ${[...mockShims.trust._caPins.keys()].join(', ')} (same host, separate keys)`);
  });

  it('3. logs in through the relay with a sealed mcp.login (openZone → NatsServerClient)', async () => {
    const res = await openZone(zone, { password: ada.password, requestTimeoutMs: 10_000 });
    if (!res.ok) throw new Error(`openZone failed: ${clean(JSON.stringify(res))}`);
    relayClient = res.client;
    expect(res.relayUrl).toBe(invite.relays[0].wsUrl);
    expect(relayClient.getToken()).toBeTruthy();
    expect(mockMem.get('@wyrd_relay_url')).toBe(invite.relays[0].wsUrl);
    const out = framesOf(invite.relays[0].wsUrl, 'out');
    const login = sentOps(invite.relays[0].wsUrl).find((o) => o.op === 'PUB' && o.subject === `wyrd.zone.${zone}.mcp.login`);
    expect(login).toBeDefined();
    const body = login!.payload!;
    expect(Object.keys(JSON.parse(body)).sort()).toEqual(['c', 'e', 'n', 'v']);
    for (const f of out) expect(f.text.includes(ada.password)).toBe(false);
    say(`relay login: ok as ${ada.username}, token received; mcp.login on the wire = {${Object.keys(JSON.parse(body)).join(',')}} (v=${JSON.parse(body).v}); password in no frame`);
    // pair.device over the relay (sealed) gives this phone its home-bus login and address.
    await waitFor('home-bus credentials from pair.device', () => mockMem.get(HOME_NATS_USER_KEY));
    expect(mockMem.get(HOME_BUS_URL_KEY)).toBe(invite.homeBus);
    say(`pair.device via relay: bus user ${mockMem.get(HOME_NATS_USER_KEY)!.slice(0, 14)}…, natsUrl=${mockMem.get(HOME_BUS_URL_KEY)}`);
  });

  it('4. opens the sealed tunnel, receives room_state, sends look and gets room_state', async () => {
    const between = relayClient.asBetweenClient()!;
    const conn = new RelayTunnelServerConnection(between, zone, relayClient.getToken(), invite.zk!);
    const got: S2CMessage[] = [];
    conn.onMessage((m) => got.push(m));
    conn.open();
    const first = await waitFor('room_state over the tunnel', () => got.find((m) => m.type === 'room_state'));
    expect(conn.isSealed).toBe(true);
    const n = got.length;
    conn.send({ type: 'look', id: 'rn-live-look-1', roomId: '' } as never);
    const after = await waitFor('room_state after look', () => got.slice(n).find((m) => m.type === 'room_state'));
    const errors = got.filter((m) => m.type === 'error');
    expect(errors).toEqual([]);
    const tunnelOut = sentOps(invite.relays[0].wsUrl).filter((o) => o.subject.startsWith(`wyrd.tunnel.${zone}.`));
    expect(tunnelOut.some((o) => o.subject.endsWith('.up'))).toBe(true);
    for (const f of mockShims.frames.filter((x: any) => x.dir === 'out')) expect(f.text.includes(relayClient.getToken()!)).toBe(false);
    const roomName = (m: any) => m.room?.name ?? m.room?.roomId;
    say(`tunnel: sealed=${conn.isSealed}; ${got.length} frame(s): ${got.map((m) => m.type).join(', ')}; room "${roomName(first)}" then after look "${roomName(after)}"; token in no tunnel frame`);
    conn.close();
  });

  it('5. a knock on another zone: sealed to that zone; an (older) relay\'s refusal comes back plainly, at once', async () => {
    const t0 = Date.now();
    // Any published zone key will do: the knock is sealed to it (here the rehearsal's, as a stand-in).
    const r = await relayClient.requestAccess('otherzone', ada.username, undefined, undefined, invite.zk);
    const ms = Date.now() - t0;
    const knock = sentOps(invite.relays[0].wsUrl).find((o) => o.op === 'PUB' && o.subject === 'wyrd.zone.otherzone.directory.knock');
    expect(knock).toBeDefined();
    expect(JSON.parse(knock!.payload!).v).toBe(2); // sealed: no name in the clear
    expect(knock!.payload!.includes(ada.username)).toBe(false);
    const errs = serverErrors(invite.relays[0].wsUrl).filter((e) => e.includes('otherzone'));
    if (errs.length > 0) {
      // A relay that carries only this phone's own zone.
      expect(r.ok).toBe(false);
      expect(r.error).toMatch(/does not carry knocks to other zones/);
      expect(ms).toBeLessThan(5_000);
      say(`knock otherzone: relay refused (${errs[0].replace(/"/g, "'")}) → ${ms} ms, plain: "${r.error}"`);
    } else {
      // A 0.5.0 relay carries it; no zone "otherzone" exists, so nobody answers.
      expect(r.ok).toBe(false);
      expect(r.error).toMatch(/did not answer/);
      say(`knock otherzone: the relay carried the sealed knock; no such zone answered → ${ms} ms, plain: "${r.error}"`);
    }
    // Any other subject the relay will not carry is answered plainly too, not after a timeout.
    const relay = invite.relays[0];
    const other = new NatsServerClient({ relayUrl: relay.wsUrl, zoneId: 'otherzone', user: relay.natsUser, password: relay.natsPassword, zk: invite.zk, requestTimeoutMs: 10_000 });
    await other.connect();
    const t1 = Date.now();
    const reply = await (other as any).request('wyrd.zone.otherzone.auth.status', {});
    const ms2 = Date.now() - t1;
    expect(reply.ok).toBe(false);
    expect(String(reply.error)).toMatch(/relay would not carry this request/);
    expect(ms2).toBeLessThan(5_000);
    say(`relay refusal of wyrd.zone.otherzone.auth.status: ${ms2} ms → "${reply.error}"`);
    await other.disconnect();
  });

  it('6. LAN pairing: a real pairing reply keeps the invite relay and gives the home bus', async () => {
    const challenge = await requestPairing(invite.lanHttps!, 'rn-live', 'phone');
    expect(challenge?.challengeId).toBeTruthy();
    // The steward, on their own device, reads the code.
    const login = await mockShims.pinnedFetch(`${invite.lanHttps}/api/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: ada.username, password: ada.password }),
    });
    const stewardToken = (await login.json()).token;
    const code = await (await mockShims.pinnedFetch(`${invite.lanHttps}/api/pair/code`, {
      headers: { Authorization: `Bearer ${stewardToken}` },
    })).json();
    const reply = await verifyCode(invite.lanHttps!, challenge!.challengeId, code.code);
    expect(reply).not.toBeNull();
    pairing = reply!;
    expect(pairing.natsUrl).toBe(invite.homeBus);
    expect(pairing.relayUrl ?? null).toBeNull();
    expect(pairing.nats_user).toMatch(/^phone-/);
    say(`pair/verify reply: natsUrl=${pairing.natsUrl} relayUrl=${pairing.relayUrl ?? 'none'} serverUrl=${pairing.serverUrl} nats_user=${pairing.nats_user!.slice(0, 14)}…`);

    await useAppModeStore.getState().setPairingCredentials(pairing);
    expect(mockMem.get('@wyrd_relay_url')).toBe(invite.relays[0].wsUrl);
    expect(mockMem.get(HOME_BUS_URL_KEY)).toBe(invite.homeBus);
    expect(mockMem.get(HOME_NATS_USER_KEY)).toBe(pairing.nats_user);
    expect(mockMem.get(HOME_NATS_PASS_KEY)).toBe(pairing.nats_pass);
    say(`after setPairingCredentials: @wyrd_relay_url=${mockMem.get('@wyrd_relay_url')} (the invite's), home bus=${mockMem.get(HOME_BUS_URL_KEY)}, bus login = the reply's`);
  });

  it('7. the home bus: pinned to home_ca_fp, own login, sealed mcp.login', async () => {
    const bus = await resolveHomeBus(zone);
    expect(bus?.url).toBe(invite.homeBus);
    expect(bus?.user).toBe(pairing.nats_user);
    await pinHomeCa(bus!.url, bus!.homeCaFp);
    const c = new NatsServerClient({ relayUrl: bus!.url, zoneId: zone, user: bus!.user, password: bus!.pass, zk: invite.zk, requestTimeoutMs: 10_000 });
    const auth = await c.login(ada.username, ada.password);
    expect(auth.token).toBeTruthy();
    const out = framesOf(bus!.url, 'out');
    const pub = sentOps(bus!.url).find((o) => o.op === 'PUB' && o.subject === `wyrd.zone.${zone}.mcp.login`);
    expect(JSON.parse(pub!.payload!).v).toBe(2);
    expect(out.some((f: any) => f.text.includes(`_INBOX.${bus!.user}.`))).toBe(true);
    for (const f of out) expect(f.text.includes(ada.password)).toBe(false);
    say(`home bus ${bus!.url}: connected with its own login, sealed mcp.login ok (userId ${auth.userId}), replies on _INBOX.<login>`);
    await c.disconnect();
  });

  it('8. the app\'s home-bus Between leg: Study sync under the login name, both ways', async () => {
    const bus = (await resolveHomeBus(zone))!;
    const adaId = (await relayClient.login(ada.username, ada.password)).userId;
    // A page written on the home through the sealed study.journal request (over the relay)…
    const homeNote = `rn-live from the home ${new Date().toISOString()}`;
    const written = await relayClient.writeJournal('', homeNote);
    expect(written.ok).toBe(true);
    const mark = mockShims.frames.length; // this step's frames only
    const client = new NatsBetweenAdapter();
    await client.connect(bus.url, { user: bus.user, pass: bus.pass });
    const node = new PhoneNode(new InMemoryEventJournal(), new InMemoryVitalityStore(), mockInference as never);
    try {
      node.studyStore = new AsyncStorageStudyStore(createMockAsyncStorage());
      await node.start();
      // A note on the phone the home has not seen: the home should ask for it.
      const note = `rn-live ${new Date().toISOString()}`;
      await node.studyStore.putItem({
        id: `si-rn-live-${Date.now()}`, userDid: adaId, itemType: 'journal', title: 'rn-live', content: note,
        collection: '', timestamp: Date.now(), version: 1, vectorClock: { [bus.user]: 1 }, lastModifiedBy: bus.user,
      });
      node.setBetween({
        client, nodeId: 'rn-live', householdId: 'default', companionDid: 'did:wyrd:companion:rn-live', zoneId: zone,
        accountUserId: adaId, sessionToken: relayClient.getToken(), viaRelay: false, busUser: bus.user,
      });
      expect(node.presenceManager).toBeNull();
      const hideU = (x: string) => x.split(bus.user).join('<login>');
      const asked = await waitFor('the home\'s directed study.sync to this phone', () =>
        sentOps(bus.url, 'in').find((o) => o.op === 'MSG' && new RegExp(`^between\\.${zone}\\.[^.]+\\.${bus.user}\\.study\\.sync$`).test(o.subject)), 20_000)
        .catch((e) => {
          say(`DEBUG bus out: ${hideU(sentOps(bus.url).map((o) => `${o.op} ${o.subject}`).join(' ; '))}`);
          say(`DEBUG bus in: ${hideU(sentOps(bus.url, 'in').map((o) => `${o.op} ${o.subject}`).join(' ; '))}`);
          say(`DEBUG bus -ERR: ${hideU(serverErrors(bus.url).join(' | ') || 'none')}`);
          if (process.env.RN_LIVE_DUMP) {
            const dump = mockShims.frames.filter((f: any) => sameConn(f.url, bus.url))
              .map((f: any) => `${f.socket} ${f.dir} ${JSON.stringify(clean(hideU(f.text)).slice(0, 600))}`).join('\n');
            fs.writeFileSync(process.env.RN_LIVE_DUMP, dump, { mode: 0o600 });
          }
          throw e;
        });
      const request = JSON.parse(asked.payload!);
      const answered = await waitFor('the phone\'s delta to the home', () =>
        sentOps(bus.url).find((o) => o.op === 'PUB' && o.subject.startsWith(`between.${zone}.${bus.user}.`) && o.subject.endsWith('.study.sync')));
      const delta = JSON.parse(answered.payload!);
      expect(delta.type).toBe('study_delta');
      expect(delta.items.map((i: { content: string }) => i.content)).toContain(note);
      // …comes back to the phone over Study sync on the home bus.
      let back: { content: string } | undefined;
      await waitFor('the home\'s journal page in the phone\'s Study', () => {
        void node.studyStore!.recentJournal(adaId, 200).then((items) => { back = items.find((i) => i.content === homeNote); });
        return back ?? null;
      }, 20_000);
      say(`home → phone: a page written through sealed study.journal ("${homeNote.slice(0, 22)}…") reached the phone's Study over the home bus`);
      const subjects = [...new Set(sentOps(bus.url, 'out', mark).map((o) => `${o.op} ${o.subject}`))];
      for (const sub of subjects) {
        expect(sub).toMatch(new RegExp(`^(SUB between\\.${zone}\\.\\*\\.${bus.user}|PUB between\\.${zone}\\.${bus.user}\\.[^.]+)\\.study\\.(state|sync)$`));
      }
      const hide = (x: string) => x.split(bus.user).join('<login>');
      say(`home-bus Study sync: home sent ${request.type} on ${hide(asked.subject)}; phone answered ${delta.type} (${delta.items.length} item) on ${hide(answered.subject)}`);
      say(`home-bus subjects the phone used: ${hide(subjects.join(' ; '))}`);
    } finally {
      node.stop();
      await client.disconnect();
    }
  });

  it('9. visiting a room on the home over wss://<lan>/ws: as the signed-in person, through the pinned native socket', async () => {
    const visit = async (session: string | null) => {
      const node = new PhoneNode(new InMemoryEventJournal(), new InMemoryVitalityStore(), mockInference as never);
      await node.start();
      await node.go('player', 'You', 'north'); // Study → Home, where "out" leads to the household
      node.serverUrl = pairing.serverUrl;
      node.deviceToken = pairing.token;
      node.sessionToken = session;
      const events: PhoneNodeEvent[] = [];
      node.onEvent((e) => events.push(e));
      const mark = mockShims.frames.length;
      await node.go('player', 'You', 'out');
      const err = events.find((e) => e.type === 'error');
      if (err) throw new Error(`visit failed: ${clean(JSON.stringify(err))}`);
      await waitFor('server_room_entered', () => events.find((e) => e.type === 'server_room_entered'));
      const room: any = await waitFor('the home\'s room_state', () => events.find((e) => e.type === 'room_changed'));
      const frames = mockShims.frames.slice(mark).filter((f: any) => f.dir === 'in' && f.url.includes('/ws?'));
      const decision = mockShims.decisions.filter((d: any) => d.url.includes('/ws?')).pop();
      (node.serverConnection as { disconnect?: () => void } | null)?.disconnect?.();
      node.stop();
      return { room: room.snapshot, frames: frames.map((f: any) => f.text), decision, url: decision?.url as string };
    };

    // Signed in (the sealed relay login's session): ada arrives as herself, in the Nexus.
    const asAda = await visit(relayClient.getToken());
    expect(asAda.decision?.why).toBe('CA pin');
    expect(asAda.decision?.key).toBe(new URL(invite.lanHttps!).host);
    expect(asAda.url).toMatch(/\/ws\?token=<redacted>&room=nexus$/);
    const adaSeen = asAda.frames.some((t: string) => t.includes('Ada (rehearsal steward)'));
    expect(asAda.room.roomId).toBe('nexus');
    expect(adaSeen).toBe(true);
    say(`server-room visit signed in: ${asAda.url} via native text socket (${asAda.decision.why} at ${asAda.decision.key}); room "${asAda.room.name}" (${asAda.room.roomId}); the home's frames name "Ada (rehearsal steward)": ${adaSeen}`);

    // No one signed in: the device token, as before.
    const asDevice = await visit(null);
    expect(asDevice.url).toMatch(/\/ws\?device_token=<redacted>$/);
    say(`server-room visit, no one signed in: ${asDevice.url}; room "${asDevice.room.name}" (${asDevice.room.roomId})`);

    // An expired session: the home refuses it before any frame; the phone uses the device token.
    const expired = await visit('0000-not-a-session');
    expect(expired.url).toMatch(/\/ws\?device_token=<redacted>$/);
    say(`server-room visit with an expired session: refused, then ${expired.url}; room "${expired.room.name}"`);
  });

  it('10. NativeNatsClient on the real home bus: refused login, permissions violations, a drop', async () => {
    const bus = (await resolveHomeBus(zone))!;
    // A wrong password: connect() fails with the server's refusal, and no retry.
    const bad = new NatsBetweenAdapter();
    const t0 = Date.now();
    const badErr = await bad.connect(bus.url, { user: bus.user, pass: 'wrong-password' }).catch((e: Error) => e);
    expect(badErr).toBeInstanceOf(Error);
    expect(String((badErr as Error).message)).toMatch(/NATS refused the connection: Authorization Violation/);
    expect(bad.loginRefused).toBe(true);
    expect(bad.isConnected).toBe(false);
    say(`bus login with a wrong password: ${Date.now() - t0} ms → "${(badErr as Error).message}"`);

    // The right login: connected only after the server's PONG.
    const good = new NatsBetweenAdapter();
    await good.connect(bus.url, { user: bus.user, pass: bus.pass });
    expect(good.isConnected).toBe(true);
    const errs: Array<{ kind: string; subject?: string; message: string }> = [];
    good.onServerError((e) => errs.push(e));
    const refusedSub = await new Promise<{ subject: string; message: string }>((resolve) => {
      good.subscribe(`between.${zone}.*.*.study.state`, () => {}, resolve);
    });
    expect(refusedSub.message).toMatch(/Permissions Violation for Subscription/);
    good.publish(`between.${zone}.presence.rn-live`, new TextEncoder().encode('{}'));
    const refusedPub = await waitFor('the refused publish', () => errs.find((e) => e.kind === 'publish'));
    expect(refusedPub.subject).toBe(`between.${zone}.presence.rn-live`);
    expect(good.isConnected).toBe(true);
    const hide = (x: string) => x.split(bus.user).join('<login>');
    say(`bus subscription refused: ${hide(refusedSub.message)} → that subscription failed, connection stays up`);
    say(`bus publish refused: ${hide(refusedPub.message)} → reported with its subject`);

    // A drop (the network under the socket is cut): isConnected goes false, then back after the reconnect.
    expect(mockShims.dropConnections(bus.url)).toBeGreaterThan(0);
    await waitFor('isConnected false after the drop', () => !good.isConnected, 2_000);
    const tDrop = Date.now();
    await waitFor('isConnected true after the reconnect', () => good.isConnected, 10_000);
    say(`bus drop: isConnected went false at once, true again after ${Date.now() - tDrop} ms (reconnect with backoff)`);
    await good.disconnect();
  });

  afterAll(async () => {
    await relayClient?.disconnect().catch(() => {});
  });
});

// Keep the file meaningful when skipped.
if (!env) {
  it('live rehearsal test is skipped (set RN_REHEARSAL_ENV to run it)', () => {
    expect(path.basename(__filename)).toBe('rehearsal-live.test.ts');
  });
}
