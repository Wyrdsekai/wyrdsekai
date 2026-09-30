/**
 * What a model request knows about today: every request declares one.
 *
 * A companion always knows the date and the time (decided 2026-03-28). With no
 * date the model believes it is 2024: on 2026-09-22 a companion searched her
 * own subject for "2024 2025". The phone's prompts carried no date at all.
 *
 * Port of the server's core/.../inference/NowLine.java — the same modes and the
 * same protocol line on every surface. Every request says which of three it is,
 * and the send point stamps the outgoing COPY of the messages; the stored
 * history never holds the line:
 *   - DATE_TIME — she speaks, thinks or acts:
 *     `[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]` opens the
 *     LAST user message. That is close to generation and after everything the
 *     model server can reuse from its prompt cache; a value that changes every
 *     minute never sits in the system prompt.
 *   - DATE — a single-shot request made for her:
 *     `Today is Wednesday, 23 September 2026.` opens the leading system message.
 *   - NONE — a classifier, an extractor, identity authoring, a smoke test:
 *     nothing is added, because the time would leak into what it writes.
 *
 * A request that declares nothing gets DATE_TIME as of when it is sent — a
 * forgotten path still knows the day. `now-line-declared.test.ts` makes every
 * request in the tree declare one anyway.
 */

import type { ChatMessage } from './types';

export type NowMode = 'DATE_TIME' | 'DATE' | 'NONE';

const OPEN = '[Now: ';
const TODAY = 'Today is ';

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/** A request answered this much later than it was asked says when it was asked. */
const ASKED_MIN_WAIT_MS = 60_000;

export class NowLine {
  static readonly NONE = new NowLine('NONE', null);

  /**
   * @param asOf the moment the line states; a loop fixes it when the loop opens
   *             so every iteration sends the same bytes. null = when sent.
   */
  private constructor(readonly mode: NowMode, readonly asOf: Date | null) {}

  /** She speaks, thinks or acts: date and time, as of `asOf` or when sent. */
  static dateTime(asOf?: Date): NowLine {
    return new NowLine('DATE_TIME', asOf ?? null);
  }

  /** A single-shot request made for her: the date, as of `asOf` or when sent. */
  static date(asOf?: Date): NowLine {
    return new NowLine('DATE', asOf ?? null);
  }

  /**
   * The outgoing copy of `messages` with this line in place. The input array
   * and its messages are never changed. A list that already carries the line
   * (a retry, a fallthrough to the next backend) is returned as it is.
   *
   * @param zone IANA zone the line is read in; absent = the device's own.
   */
  stamp(messages: ChatMessage[], zone?: string): ChatMessage[] {
    if (this.mode === 'NONE') return messages;
    const at = this.asOf ?? new Date();
    const out = [...messages];
    if (this.mode === 'DATE') {
      const line = dateText(at, zone);
      const sys = out[0];
      if (sys?.role === 'system') {
        const body = sys.content ?? '';
        if (body.startsWith(TODAY)) return messages;
        out[0] = { ...sys, content: body ? `${line}\n${body}` : line };
      } else {
        out.unshift({ role: 'system', content: line });
      }
      return out;
    }
    const line = dateTimeText(at, zone);
    for (let i = out.length - 1; i >= 0; i--) {
      const m = out[i];
      if (m.role !== 'user') continue;
      const body = m.content ?? '';
      if (body.startsWith(OPEN)) return messages;
      out[i] = { ...m, content: `${line}\n${body}` };
      return out;
    }
    // No user turn at all: the line is one, after a leading system message.
    out.splice(out[0]?.role === 'system' ? 1 : 0, 0, { role: 'user', content: line });
    return out;
  }
}

/**
 * `[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]`. The zone is
 * part of the time: people reach her from other zones, and the offset moves
 * with daylight saving.
 */
export function dateTimeText(at: Date, zone?: string): string {
  const t = wallClock(at, zone);
  return `${OPEN}${dayMonthYear(t)}, ${hourMinute(t)} ${zoneText(at, zone, t.offsetMinutes)}, ${partOfDay(t.hour)}]`;
}

/** `[Asked: Wednesday 23 September 2026, 09:40 EDT (UTC-4)]` — when a replayed request was asked. */
export function askedText(at: Date, zone?: string): string {
  const t = wallClock(at, zone);
  return `[Asked: ${dayMonthYear(t)}, ${hourMinute(t)} ${zoneText(at, zone, t.offsetMinutes)}]`;
}

/** `Today is Wednesday, 23 September 2026.` */
export function dateText(at: Date, zone?: string): string {
  const t = wallClock(at, zone);
  return `${TODAY}${t.weekday}, ${t.day} ${t.month} ${t.year}.`;
}

export function partOfDay(hour: number): string {
  if (hour >= 5 && hour < 12) return 'morning';
  if (hour >= 12 && hour < 17) return 'afternoon';
  if (hour >= 17 && hour < 21) return 'evening';
  if (hour >= 21 || hour < 2) return 'night';
  return 'late night';
}

/**
 * The outgoing copy for a request answered later than it was asked (the offline
 * queue): `[Asked: …]` opens the last user message, so once the send point puts
 * `[Now: …]` above it she reads when it was asked and that it waited. Under a
 * minute apart nothing is added. The input is never changed.
 */
export function withAsked(
  messages: ChatMessage[],
  askedAt: Date,
  answeredAt: Date,
  zone?: string,
): ChatMessage[] {
  if (answeredAt.getTime() - askedAt.getTime() < ASKED_MIN_WAIT_MS) return messages;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role !== 'user') continue;
    const out = [...messages];
    out[i] = { ...messages[i], content: `${askedText(askedAt, zone)}\n${messages[i].content ?? ''}` };
    return out;
  }
  return messages;
}

interface WallClock {
  weekday: string;
  day: number;
  month: string;
  year: number;
  hour: number;
  minute: number;
  offsetMinutes: number;
}

/** The wall clock at `at` in `zone`; absent = the device's own zone. */
function wallClock(at: Date, zone?: string): WallClock {
  const offsetMinutes = zone ? zoneOffsetMinutes(at, zone) : -at.getTimezoneOffset();
  const t = new Date(at.getTime() + offsetMinutes * 60_000);
  return {
    weekday: WEEKDAYS[t.getUTCDay()],
    day: t.getUTCDate(),
    month: MONTHS[t.getUTCMonth()],
    year: t.getUTCFullYear(),
    hour: t.getUTCHours(),
    minute: t.getUTCMinutes(),
    offsetMinutes,
  };
}

function dayMonthYear(t: WallClock): string {
  return `${t.weekday} ${t.day} ${t.month} ${t.year}`;
}

function hourMinute(t: WallClock): string {
  return `${String(t.hour).padStart(2, '0')}:${String(t.minute).padStart(2, '0')}`;
}

/** Offset of a named zone at `at`, from its wall clock there. */
function zoneOffsetMinutes(at: Date, zone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric', month: 'numeric', day: 'numeric',
    hour: 'numeric', minute: 'numeric', second: 'numeric',
  }).formatToParts(at);
  const n = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const wall = Date.UTC(n('year'), n('month') - 1, n('day'), n('hour'), n('minute'), n('second'));
  return Math.round((wall - Math.floor(at.getTime() / 1000) * 1000) / 60_000);
}

/**
 * `EDT (UTC-4)`; `UTC+5:30` where the platform gives the zone no short name of
 * its own (Intl answers "GMT+5:30" for Kolkata). The offset is the load-bearing
 * part and is always there.
 */
function zoneText(at: Date, zone: string | undefined, offsetMinutes: number): string {
  const abs = Math.abs(offsetMinutes);
  const hours = Math.floor(abs / 60);
  const minutes = abs % 60;
  const offset = offsetMinutes === 0
    ? 'UTC'
    : `UTC${offsetMinutes < 0 ? '-' : '+'}${hours}${minutes === 0 ? '' : `:${String(minutes).padStart(2, '0')}`}`;
  const name = shortZoneName(at, zone);
  return name ? `${name} (${offset})` : offset;
}

/** The platform's English short name for the zone ("EDT"), or null where it has none. */
function shortZoneName(at: Date, zone: string | undefined): string | null {
  try {
    const name = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'short' })
      .formatToParts(at)
      .find((p) => p.type === 'timeZoneName')?.value ?? '';
    return /^[A-Za-z]+$/.test(name) && !name.startsWith('GMT') && !name.startsWith('UTC') ? name : null;
  } catch {
    // An engine without Intl time-zone names still prints the offset.
    return null;
  }
}
