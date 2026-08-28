/**
 * THE live-session input contract — one pure function from a typed line to the
 * C2S frame (or client-local behavior) a terminal must produce over a live zone
 * session (relay tunnel).
 *
 * This is the RN half of the EXECUTABLE parity contract in
 * `clients/parity/parity.json`; the KMP twin is
 * `clients/kmp/shared/.../engine/transit/SessionInputMapper.kt`. Both are
 * driven by the same JSON table from their test suites
 * (parity-conformance.test.ts / ParityConformanceTest.kt). Behavior changes go
 * TABLE-FIRST; a client that drifts fails its build. Born 2026-07-25 after a
 * week of the two hand-written input layers drifting apart one bug at a time.
 *
 * Scope: LIVE-SESSION ONLY. The offline path legitimately differs (it drives
 * the local PhoneNode's APIs directly). Study commands (journal/library) are
 * intercepted by the screen BEFORE this mapper when a ServerClient is present.
 */
import type { C2SMessage } from '../../protocol/c2s';
import type { Hint, RoomSnapshot } from '../../protocol/models';
import type { S2CMessage } from '../../protocol/s2c';

export type MappedInput =
  /**
   * Send `frame` over the session, after rendering `echo` as a muted system
   * line. The echo is TERMINAL-style ("> <input>"), never a speech bubble —
   * echoing commands as "You: l" made every command read as the player SAYING
   * it (operator, 2026-07-25). Part of the parity contract (parity.json
   * echoPolicy).
   */
  | { kind: 'send'; frame: C2SMessage; echo: string }
  | { kind: 'local'; speaker: string; text: string }
  | { kind: 'ignore' };

/** Client-side help — documents CLIENT input syntax, so it stays local. */
export const HELP_TEXT =
  'Commands:\n' +
  "  say <text> or '<text> or \"<text>  -- Say something\n" +
  '  emote <action> or :<action> or ;<action>  -- Perform an action\n' +
  '  tell <name> <text> or ><name> <text>  -- Send a private message\n' +
  '  whisper <name> <text>  -- Whisper to someone nearby\n' +
  '  look or l  -- Look around\n' +
  '  go <direction>  -- Move to another room\n' +
  '  take <object>  -- Pick up an object\n' +
  '  drop <object>  -- Drop an object\n' +
  '  use <object>  -- Use an object\n' +
  '  /inventory or /i  -- Check your inventory\n' +
  '  /socials  -- List social emotes\n' +
  '  /help  -- Show this help';

export const SOCIALS_TEXT =
  'Social emotes (type the word to perform):\n' +
  '  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n' +
  '  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n' +
  '  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n' +
  '  hug, thank, agree, disagree, salute, welcome';

const directionAliases: Record<string, string> = {
  n: 'north', s: 'south', e: 'east', w: 'west',
  u: 'up', d: 'down',
  ne: 'northeast', nw: 'northwest', se: 'southeast', sw: 'southwest',
  '北': 'north', '南': 'south', '東': 'east', '西': 'west',
  '上': 'up', '下': 'down',
};

const bareDirections = new Set([
  'north', 'south', 'east', 'west', 'up', 'down',
  'northeast', 'northwest', 'southeast', 'southwest',
  'out', 'back',
  ...Object.keys(directionAliases),
]);

export function resolveDirection(raw: string): string {
  const t = raw.trim();
  return directionAliases[t] ?? directionAliases[t.toLowerCase()] ?? t.toLowerCase();
}

/** The numbered actions menu (SSH parity). Exact copy is part of the contract. */
export function actionsMenu(hints: Hint[]): string {
  if (hints.length === 0) return 'Nothing to do here right now.';
  const lines = hints.map((h, i) => `  [${i + 1}] ${h.label}`).join('\n');
  return `Things to do here:\n${lines}\n(type a number to choose)`;
}

const examineRe = /^(?:examine|exam|ex|inspect|x)\s+(.+)$/i;
const lookAtRe = /^l(?:ook)?\s+at\s+(.+)$/i;
const goRe = /^(?:go|move)\s+(.+)$/i;
const takeRe = /^(?:take|get)\s+(.+)$/i;
const pickUpRe = /^pick\s+up\s+(.+)$/i;
const dropRe = /^(?:drop|put\s+down)\s+(.+)$/i;
const retireRe = /^(?:retire|destroy|discard)\s+(.+)$/i;
const useRe = /^use\s+(.+)$/i;
const sayRe = /^say\s+(.+)$/i;
const emoteRe = /^emote\s+(.+)$/i;

export function mapSessionInput(raw: string, hints: Hint[], nextId: () => string): MappedInput {
  const trimmed = raw.trim();
  if (!trimmed) return { kind: 'ignore' };
  const lower = trimmed.toLowerCase();
  // Terminal-style echo for everything that goes over the wire.
  const send = (frame: C2SMessage): MappedInput => ({ kind: 'send', frame, echo: `> ${trimmed}` });

  // Slash commands. /help + /socials document CLIENT syntax → local text;
  // /actions mirrors the bare word; /inventory normalizes; everything else
  // strips the slash and forwards — the zone re-parses through the SAME
  // CommandParser SSH uses (never wrap in Say: the zone does NOT re-parse say
  // text as commands, it just speaks it).
  if (trimmed.startsWith('/')) {
    const parts = trimmed.substring(1).split(/\s+/).filter(Boolean);
    if (parts.length === 0) return { kind: 'ignore' };
    switch (parts[0].toLowerCase()) {
      case 'help':
        return { kind: 'local', speaker: 'system', text: HELP_TEXT };
      case 'socials':
        return { kind: 'local', speaker: 'system', text: SOCIALS_TEXT };
      case 'actions':
        return { kind: 'local', speaker: 'system', text: actionsMenu(hints) };
      case 'inventory':
      case 'i':
        return send({ type: 'command', id: nextId(), command: 'inventory', args: parts.slice(1), payload: {} });
      default:
        return send({ type: 'command', id: nextId(), command: parts[0], args: parts.slice(1), payload: {} });
    }
  }

  // Numbered actions menu + selection (SSH parity).
  if (lower === 'actions') {
    return { kind: 'local', speaker: 'system', text: actionsMenu(hints) };
  }
  if (/^\d+$/.test(trimmed)) {
    const n = parseInt(trimmed, 10);
    if (n >= 1 && n <= hints.length) {
      // Canonical index-based select — the zone dispatches the hint's OWN
      // intent. Never round-trip through a guessed verb or Say.
      return send({ type: 'hint_select', id: nextId(), roomId: '', index: n - 1 });
    }
    // Out-of-range → forward like any unknown word (server decides).
  }

  // examine family BEFORE bare look, so "look at X" isn't a room render.
  let g = trimmed.match(examineRe) ?? trimmed.match(lookAtRe);
  if (g) return send({ type: 'examine', id: nextId(), roomId: '', target: g[1].trim() });
  if (lower === 'look' || lower === 'l') {
    return send({ type: 'look', id: nextId(), roomId: '' });
  }

  g = trimmed.match(goRe);
  if (g) return send({ type: 'go', id: nextId(), roomId: '', direction: resolveDirection(g[1]) });
  if (bareDirections.has(lower) || bareDirections.has(trimmed)) {
    return send({ type: 'go', id: nextId(), roomId: '', direction: resolveDirection(trimmed) });
  }

  g = trimmed.match(takeRe) ?? trimmed.match(pickUpRe);
  if (g) return send({ type: 'take', id: nextId(), roomId: '', objectName: g[1].trim() });
  g = trimmed.match(dropRe);
  if (g) return send({ type: 'drop', id: nextId(), roomId: '', objectName: g[1].trim() });
  g = trimmed.match(retireRe);
  if (g) return send({ type: 'retire', id: nextId(), roomId: '', objectName: g[1].trim() });
  g = trimmed.match(useRe);
  if (g) return send({ type: 'use', id: nextId(), roomId: '', objectName: g[1].trim(), target: null });

  // Say shorthands: ' or " (leading only — the tail is kept verbatim).
  if (trimmed.startsWith("'") || trimmed.startsWith('"')) {
    return send({ type: 'say', id: nextId(), roomId: '', text: trimmed.substring(1) });
  }

  // Emote prefixes.
  if (trimmed.startsWith(':') || trimmed.startsWith(';')) {
    const text = trimmed.substring(1).trim();
    if (!text) return { kind: 'ignore' };
    return send({ type: 'emote', id: nextId(), roomId: '', text });
  }

  // tell/whisper/> ride as Say(raw): the zone's session parser turns
  // "tell <name> <msg>" into a directed tell (handleWebSocketTell) and echoes
  // ui.tell_sent back.
  if (trimmed.startsWith('>') || lower.startsWith('tell ') || lower.startsWith('whisper ')) {
    return send({ type: 'say', id: nextId(), roomId: '', text: trimmed });
  }

  g = trimmed.match(emoteRe);
  if (g) return send({ type: 'emote', id: nextId(), roomId: '', text: g[1].trim() });
  g = trimmed.match(sayRe);
  if (g) return send({ type: 'say', id: nextId(), roomId: '', text: g[1] });

  // Default: forward as a generic Command — the zone re-parses it through the
  // SAME CommandParser SSH/CLI uses (map/where/nearby/rooms/path/exits/score/…
  // behave exactly like ssh; truly unknown verbs fall to room speech THERE,
  // by the zone's rules, not the client's guess).
  const parts = trimmed.split(/\s+/);
  return send({ type: 'command', id: nextId(), command: parts[0], args: parts.slice(1), payload: {} });
}

/**
 * The live-session S2C render contract — what a frame must paint into the
 * prose stream. The other half of `clients/parity/parity.json`.
 */
export interface SessionRender {
  prose: Array<{ speaker: string; text: string }>;
  /** Non-null → the screen must adopt this room (header/exits/entities/hints). */
  room: RoomSnapshot | null;
}

export function renderSessionS2C(msg: S2CMessage): SessionRender {
  switch (msg.type) {
    case 'room_state':
      // PRINT the room, not just the header — a silent room_state made a
      // tunneled `look` produce nothing visible (2026-07-25).
      return {
        prose: [{ speaker: 'narrator', text: `${msg.room.name}\n${msg.room.description}` }],
        room: msg.room,
      };
    case 'prose':
      return { prose: [{ speaker: msg.speaker, text: msg.text }], room: null };
    case 'state_change':
      return { prose: [{ speaker: 'narrator', text: msg.description }], room: null };
    case 'error':
      return { prose: [{ speaker: 'system', text: `Error: ${msg.message}` }], room: null };
    case 'map_data':
      return msg.textMap && msg.textMap.trim().length > 0
        ? { prose: [{ speaker: 'system', text: msg.textMap }], room: null }
        : { prose: [], room: null };
    default:
      return { prose: [], room: null };
  }
}
