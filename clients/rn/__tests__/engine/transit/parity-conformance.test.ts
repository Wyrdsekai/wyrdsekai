/**
 * EXECUTABLE client-parity contract (clients/parity/parity.json).
 *
 * Drives mapSessionInput + renderSessionS2C straight from the shared table —
 * the SAME file KMP's ParityConformanceTest.kt consumes. When the two clients
 * drift on the live-session interaction layer, one of these suites fails
 * instead of operator finding it on a phone (2026-07-25).
 */
import * as fs from 'fs';
import * as path from 'path';
import {
  mapSessionInput,
  renderSessionS2C,
} from '../../../src/engine/transit/sessionInputMapper';
import { parseS2CMessage } from '../../../src/protocol/s2c';
import type { Hint } from '../../../src/protocol/models';

const tablePath = path.resolve(__dirname, '../../../../parity/parity.json');
const table = JSON.parse(fs.readFileSync(tablePath, 'utf8'));

const hintsFixture: Hint[] = table.hintsFixture;

/** Strip client-filled/empty fields so semantic frames compare cleanly. */
function normalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalize);
  if (value !== null && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (k === 'id' || k === 'roomId' || k === 'seq') continue;
      if (v === null || v === undefined) continue;
      if (Array.isArray(v) && v.length === 0) continue;
      if (v !== null && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0) continue;
      out[k] = normalize(v);
    }
    return out;
  }
  return value;
}

describe('client parity conformance (RN half)', () => {
  it('table sanity', () => {
    expect(table.version).toBe(1);
    expect(table.input.length).toBeGreaterThanOrEqual(40);
    expect(table.s2cRender.length).toBeGreaterThanOrEqual(6);
  });

  describe('input → C2S mapping', () => {
    for (const c of table.input) {
      it(c.name, () => {
        const hints = c.hints === 'none' ? [] : hintsFixture;
        const mapped = mapSessionInput(c.input, hints, () => 'test-id');

        if (c.expect.frame) {
          expect(mapped.kind).toBe('send');
          if (mapped.kind !== 'send') return;
          expect(normalize(JSON.parse(JSON.stringify(mapped.frame)))).toEqual(
            normalize(c.expect.frame),
          );
          // echoPolicy: every send echoes "> <trimmed input>".
          expect(mapped.echo).toBe(`> ${c.input.trim()}`);
        } else if (c.expect.local) {
          if (c.expect.local.kind === 'ignore') {
            expect(mapped.kind).toBe('ignore');
          } else {
            expect(mapped.kind).toBe('local');
            if (mapped.kind !== 'local') return;
            expect(mapped.speaker).toBe(c.expect.local.speaker);
            if (c.expect.local.text != null) {
              expect(mapped.text).toBe(c.expect.local.text);
            }
            if (c.expect.local.textStartsWith != null) {
              expect(mapped.text.startsWith(c.expect.local.textStartsWith)).toBe(true);
            }
          }
        } else {
          throw new Error(`${c.name}: case has neither frame nor local expectation`);
        }
      });
    }
  });

  describe('S2C → render rules', () => {
    for (const c of table.s2cRender) {
      it(c.name, () => {
        // Decode through the client's OWN wire decoder — also proves the
        // decoder itself accepts the canonical frame shapes.
        const msg = parseS2CMessage(JSON.stringify(c.frame));
        expect(msg).not.toBeNull();
        const render = renderSessionS2C(msg!);

        expect(render.prose).toEqual(c.expect.prose);
        expect(render.room != null).toBe(c.expect.roomUpdate);
      });
    }
  });
});
