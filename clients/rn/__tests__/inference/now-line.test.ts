/**
 * NowLine — the phone port of the server's date/time line.
 *
 * The reference outputs are the server's (core/.../inference/NowLine.java) for
 * the same instants and zones. Where this platform's Intl gives a zone no
 * English short name (Tokyo, Kolkata: "GMT+9", "GMT+5:30"), only the offset is
 * printed; those cases accept either form and pin everything else exactly.
 */

import {
  NowLine,
  askedText,
  dateText,
  dateTimeText,
  partOfDay,
  withAsked,
} from '../../src/inference/NowLine';
import type { ChatMessage } from '../../src/inference/types';

const AT = new Date('2026-09-23T14:05:00Z');

describe('NowLine.dateTimeText — server reference outputs', () => {
  it('America/New_York in summer names the zone and its offset', () => {
    expect(dateTimeText(AT, 'America/New_York'))
      .toBe('[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]');
  });

  it('America/New_York in winter follows daylight saving', () => {
    expect(dateTimeText(new Date('2026-12-23T14:05:00Z'), 'America/New_York'))
      .toBe('[Now: Wednesday 23 December 2026, 09:05 EST (UTC-5), morning]');
  });

  it('Asia/Tokyo', () => {
    expect([
      '[Now: Wednesday 23 September 2026, 23:05 JST (UTC+9), night]',
      '[Now: Wednesday 23 September 2026, 23:05 UTC+9, night]',
    ]).toContain(dateTimeText(AT, 'Asia/Tokyo'));
  });

  it('Asia/Kolkata prints the half-hour offset', () => {
    expect([
      '[Now: Wednesday 23 September 2026, 19:35 IST (UTC+5:30), evening]',
      '[Now: Wednesday 23 September 2026, 19:35 UTC+5:30, evening]',
    ]).toContain(dateTimeText(AT, 'Asia/Kolkata'));
  });

  it('UTC prints just UTC', () => {
    expect(dateTimeText(AT, 'UTC'))
      .toBe('[Now: Wednesday 23 September 2026, 14:05 UTC, afternoon]');
  });

  it('America/Sao_Paulo has no short name: offset only', () => {
    expect(dateTimeText(AT, 'America/Sao_Paulo'))
      .toBe('[Now: Wednesday 23 September 2026, 11:05 UTC-3, morning]');
  });

  it('Asia/Kathmandu prints the quarter-hour offset', () => {
    expect(dateTimeText(AT, 'Asia/Kathmandu'))
      .toBe('[Now: Wednesday 23 September 2026, 19:50 UTC+5:45, evening]');
  });

  it('pads the hour and minute but not the day', () => {
    expect(dateTimeText(new Date('2026-09-03T05:07:00Z'), 'UTC'))
      .toBe('[Now: Thursday 3 September 2026, 05:07 UTC, morning]');
  });

  it('reads the date in the zone, not in UTC', () => {
    // 15:30Z is already Thursday in Tokyo.
    expect(dateTimeText(new Date('2026-09-23T15:30:00Z'), 'Asia/Tokyo'))
      .toMatch(/^\[Now: Thursday 24 September 2026, 00:30 (JST \(UTC\+9\)|UTC\+9), night\]$/);
  });

  it("with no zone given, reads the device's own zone", () => {
    const device = Intl.DateTimeFormat().resolvedOptions().timeZone;
    expect(dateTimeText(AT)).toBe(dateTimeText(AT, device));
    expect(dateTimeText(new Date('2026-12-23T14:05:00Z')))
      .toBe(dateTimeText(new Date('2026-12-23T14:05:00Z'), device));
  });
});

describe('NowLine — the other lines', () => {
  it('partOfDay boundaries match the server', () => {
    const at = (h: number) => partOfDay(h);
    expect([0, 1].map(at)).toEqual(['night', 'night']);
    expect([2, 3, 4].map(at)).toEqual(['late night', 'late night', 'late night']);
    expect([5, 11].map(at)).toEqual(['morning', 'morning']);
    expect([12, 16].map(at)).toEqual(['afternoon', 'afternoon']);
    expect([17, 20].map(at)).toEqual(['evening', 'evening']);
    expect([21, 23].map(at)).toEqual(['night', 'night']);
  });

  it('dateText carries no zone', () => {
    expect(dateText(AT, 'America/New_York')).toBe('Today is Wednesday, 23 September 2026.');
    expect(dateText(new Date('2026-09-23T15:30:00Z'), 'Asia/Tokyo'))
      .toBe('Today is Thursday, 24 September 2026.');
  });

  it('askedText is the Now format without the part of day', () => {
    expect(askedText(new Date('2026-09-23T13:40:00Z'), 'America/New_York'))
      .toBe('[Asked: Wednesday 23 September 2026, 09:40 EDT (UTC-4)]');
  });
});

describe('NowLine.stamp', () => {
  const NY = 'America/New_York';
  const LINE = '[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]';

  const conversation = (): ChatMessage[] => [
    { role: 'system', content: 'You are Wyrd.' },
    { role: 'user', content: 'Alice says: good morning' },
    { role: 'assistant', content: 'Morning, Alice.' },
    { role: 'user', content: 'Alice says: what day is it?' },
  ];

  it('DATE_TIME opens the LAST user message and nothing else', () => {
    const out = NowLine.dateTime(AT).stamp(conversation(), NY);
    expect(out).toEqual([
      { role: 'system', content: 'You are Wyrd.' },
      { role: 'user', content: 'Alice says: good morning' },
      { role: 'assistant', content: 'Morning, Alice.' },
      { role: 'user', content: `${LINE}\nAlice says: what day is it?` },
    ]);
  });

  it('DATE_TIME skips a trailing assistant turn to reach the last user message', () => {
    const msgs = conversation().slice(0, 3);
    const out = NowLine.dateTime(AT).stamp(msgs, NY);
    expect(out[1].content).toBe(`${LINE}\nAlice says: good morning`);
    expect(out[2]).toEqual(msgs[2]);
  });

  it('DATE_TIME with no user message inserts one after a leading system message', () => {
    expect(NowLine.dateTime(AT).stamp([{ role: 'system', content: 'sys' }], NY)).toEqual([
      { role: 'system', content: 'sys' },
      { role: 'user', content: LINE },
    ]);
    expect(NowLine.dateTime(AT).stamp([], NY)).toEqual([{ role: 'user', content: LINE }]);
    expect(NowLine.dateTime(AT).stamp([{ role: 'assistant', content: 'hi' }], NY)).toEqual([
      { role: 'user', content: LINE },
      { role: 'assistant', content: 'hi' },
    ]);
  });

  it('DATE_TIME is idempotent: an already-stamped list comes back as it is', () => {
    const once = NowLine.dateTime(AT).stamp(conversation(), NY);
    const later = NowLine.dateTime(new Date('2026-09-23T18:00:00Z'));
    expect(later.stamp(once, NY)).toBe(once);
  });

  it('DATE opens the leading system message', () => {
    const out = NowLine.date(AT).stamp(conversation(), NY);
    expect(out[0]).toEqual({
      role: 'system',
      content: 'Today is Wednesday, 23 September 2026.\nYou are Wyrd.',
    });
    expect(out.slice(1)).toEqual(conversation().slice(1));
  });

  it('DATE creates a system message when there is none, and fills an empty one', () => {
    expect(NowLine.date(AT).stamp([{ role: 'user', content: 'summarise this' }], NY)).toEqual([
      { role: 'system', content: 'Today is Wednesday, 23 September 2026.' },
      { role: 'user', content: 'summarise this' },
    ]);
    expect(NowLine.date(AT).stamp([{ role: 'system', content: '' }], NY)).toEqual([
      { role: 'system', content: 'Today is Wednesday, 23 September 2026.' },
    ]);
  });

  it('DATE is idempotent', () => {
    const once = NowLine.date(AT).stamp(conversation(), NY);
    expect(NowLine.date(AT).stamp(once, NY)).toBe(once);
  });

  it('NONE returns the messages untouched', () => {
    const msgs = conversation();
    const out = NowLine.NONE.stamp(msgs, NY);
    expect(out).toBe(msgs);
    expect(out).toEqual(conversation());
  });

  it('never changes the input array or its messages', () => {
    for (const line of [NowLine.dateTime(AT), NowLine.date(AT)]) {
      const msgs = conversation();
      const originals = [...msgs];
      const out = line.stamp(msgs, NY);
      expect(out).not.toBe(msgs);
      expect(msgs).toEqual(conversation());
      msgs.forEach((m, i) => expect(m).toBe(originals[i]));
    }
  });

  it('with no asOf, states the moment it is sent', () => {
    jest.useFakeTimers().setSystemTime(AT);
    try {
      const out = NowLine.dateTime().stamp([{ role: 'user', content: 'hi' }], NY);
      expect(out[0].content).toBe(`${LINE}\nhi`);
    } finally {
      jest.useRealTimers();
    }
  });
});

describe('withAsked — the offline replay line', () => {
  const NY = 'America/New_York';
  const asked = new Date('2026-09-23T13:40:00Z');
  const answered = AT; // 25 minutes later
  const msgs = (): ChatMessage[] => [
    { role: 'system', content: 'You are Wyrd.' },
    { role: 'user', content: 'Bob says: remind me what we planned' },
  ];

  it('opens the last user message, and the Now line lands above it', () => {
    const out = NowLine.dateTime(answered).stamp(withAsked(msgs(), asked, answered, NY), NY);
    expect(out[1].content).toBe(
      '[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]\n'
      + '[Asked: Wednesday 23 September 2026, 09:40 EDT (UTC-4)]\n'
      + 'Bob says: remind me what we planned',
    );
    expect(out[0]).toEqual(msgs()[0]);
  });

  it('adds nothing when it was asked under a minute ago', () => {
    const input = msgs();
    expect(withAsked(input, new Date(AT.getTime() - 59_000), AT, NY)).toBe(input);
  });

  it('never changes its input', () => {
    const input = msgs();
    withAsked(input, asked, answered, NY);
    expect(input).toEqual(msgs());
  });
});
