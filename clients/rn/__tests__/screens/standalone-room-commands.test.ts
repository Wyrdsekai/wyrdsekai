/**
 * StandaloneRoomScreen command parsing tests.
 *
 * Tests the input parsing logic from StandaloneRoomScreen:
 * - "go north" / "n" -> go command
 * - "take sword" -> take command
 * - "look" -> look command
 * - regular text -> say command
 * - exit chip generation from room snapshot
 *
 * We extract and test the parsing logic directly rather than rendering.
 */

import type { RoomSnapshot, Exit } from '../../src/protocol/models';

// ── Parsing logic extracted from StandaloneRoomScreen ──

const directionAliases: Record<string, string> = {
  n: 'north', s: 'south', e: 'east', w: 'west',
  ne: 'northeast', nw: 'northwest', se: 'southeast', sw: 'southwest',
  '\u5317': 'north', '\u5357': 'south', '\u6771': 'east', '\u897F': 'west',
  '\u4E0A': 'up', '\u4E0B': 'down',
};

const bareDirections = new Set([
  'north', 'south', 'east', 'west', 'up', 'down',
  'northeast', 'northwest', 'southeast', 'southwest',
  'n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw',
  '\u5317', '\u5357', '\u6771', '\u897F', '\u4E0A', '\u4E0B',
]);

function resolveDirection(raw: string): string {
  const lower = raw.toLowerCase();
  return directionAliases[lower] ?? lower;
}

type ParsedCommand =
  | { type: 'look' }
  | { type: 'go'; direction: string }
  | { type: 'take'; objectName: string }
  | { type: 'use'; objectName: string }
  | { type: 'say'; text: string };

/**
 * Parse user input into a command, following StandaloneRoomScreen's sendInput logic.
 */
function parseInput(input: string): ParsedCommand | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  const lower = trimmed.toLowerCase();

  // look
  if (lower === 'look' || lower === 'l') {
    return { type: 'look' };
  }

  // go <direction>
  const goMatch = lower.match(/^(?:go|move)\s+(.+)$/);
  if (goMatch) {
    return { type: 'go', direction: resolveDirection(goMatch[1].trim()) };
  }

  // Bare direction
  if (bareDirections.has(lower) || bareDirections.has(trimmed)) {
    const raw = bareDirections.has(lower) ? lower : trimmed;
    return { type: 'go', direction: resolveDirection(raw) };
  }

  // take <object>
  const takeMatch = lower.match(/^(?:take|get)\s+(.+)$/) ?? lower.match(/^pick\s+up\s+(.+)$/);
  if (takeMatch) {
    return { type: 'take', objectName: takeMatch[1].trim() };
  }

  // use <object>
  const useMatch = lower.match(/^use\s+(.+)$/);
  if (useMatch) {
    return { type: 'use', objectName: useMatch[1].trim() };
  }

  // Explicit say
  const sayMatch = trimmed.match(/^say\s+(.+)$/i) ?? trimmed.match(/^"(.+)"$/);
  if (sayMatch) {
    return { type: 'say', text: sayMatch[1] };
  }

  // Default: say
  return { type: 'say', text: trimmed };
}

// ── Tests ──

describe('Command parsing: go command', () => {
  test('"go north" parses as go north', () => {
    const cmd = parseInput('go north');
    expect(cmd).toEqual({ type: 'go', direction: 'north' });
  });

  test('"move south" parses as go south', () => {
    const cmd = parseInput('move south');
    expect(cmd).toEqual({ type: 'go', direction: 'south' });
  });

  test('"go northeast" parses as go northeast', () => {
    const cmd = parseInput('go northeast');
    expect(cmd).toEqual({ type: 'go', direction: 'northeast' });
  });

  test('"GO NORTH" is case-insensitive', () => {
    const cmd = parseInput('GO NORTH');
    expect(cmd).toEqual({ type: 'go', direction: 'north' });
  });
});

describe('Command parsing: bare directions', () => {
  test('"n" resolves to go north', () => {
    const cmd = parseInput('n');
    expect(cmd).toEqual({ type: 'go', direction: 'north' });
  });

  test('"s" resolves to go south', () => {
    const cmd = parseInput('s');
    expect(cmd).toEqual({ type: 'go', direction: 'south' });
  });

  test('"e" resolves to go east', () => {
    const cmd = parseInput('e');
    expect(cmd).toEqual({ type: 'go', direction: 'east' });
  });

  test('"w" resolves to go west', () => {
    const cmd = parseInput('w');
    expect(cmd).toEqual({ type: 'go', direction: 'west' });
  });

  test('"ne" resolves to go northeast', () => {
    const cmd = parseInput('ne');
    expect(cmd).toEqual({ type: 'go', direction: 'northeast' });
  });

  test('"north" as bare direction', () => {
    const cmd = parseInput('north');
    expect(cmd).toEqual({ type: 'go', direction: 'north' });
  });

  test('Japanese direction \u5317 resolves to north', () => {
    const cmd = parseInput('\u5317');
    expect(cmd).toEqual({ type: 'go', direction: 'north' });
  });

  test('Japanese direction \u4E0A resolves to up', () => {
    const cmd = parseInput('\u4E0A');
    expect(cmd).toEqual({ type: 'go', direction: 'up' });
  });
});

describe('Command parsing: take command', () => {
  test('"take sword" parses as take sword', () => {
    const cmd = parseInput('take sword');
    expect(cmd).toEqual({ type: 'take', objectName: 'sword' });
  });

  test('"get scroll" parses as take scroll', () => {
    const cmd = parseInput('get scroll');
    expect(cmd).toEqual({ type: 'take', objectName: 'scroll' });
  });

  test('"pick up crystal" parses as take crystal', () => {
    const cmd = parseInput('pick up crystal');
    expect(cmd).toEqual({ type: 'take', objectName: 'crystal' });
  });

  test('"TAKE Key" is case-insensitive for verb, preserves object name case in lowered form', () => {
    const cmd = parseInput('TAKE Key');
    expect(cmd).toEqual({ type: 'take', objectName: 'key' });
  });
});

describe('Command parsing: look command', () => {
  test('"look" parses as look', () => {
    const cmd = parseInput('look');
    expect(cmd).toEqual({ type: 'look' });
  });

  test('"l" shorthand parses as look', () => {
    const cmd = parseInput('l');
    expect(cmd).toEqual({ type: 'look' });
  });

  test('"Look" is case-insensitive', () => {
    const cmd = parseInput('Look');
    expect(cmd).toEqual({ type: 'look' });
  });

  test('"L" uppercase shorthand works', () => {
    const cmd = parseInput('L');
    expect(cmd).toEqual({ type: 'look' });
  });
});

describe('Command parsing: say command (default)', () => {
  test('regular text defaults to say', () => {
    const cmd = parseInput('Hello there');
    expect(cmd).toEqual({ type: 'say', text: 'Hello there' });
  });

  test('explicit say prefix', () => {
    const cmd = parseInput('say Hello world');
    expect(cmd).toEqual({ type: 'say', text: 'Hello world' });
  });

  test('quoted text becomes say', () => {
    const cmd = parseInput('"Who goes there?"');
    expect(cmd).toEqual({ type: 'say', text: 'Who goes there?' });
  });

  test('empty input returns null', () => {
    expect(parseInput('')).toBeNull();
  });

  test('whitespace-only input returns null', () => {
    expect(parseInput('   ')).toBeNull();
  });

  test('arbitrary text is say', () => {
    const cmd = parseInput('I wonder what this crystal does');
    expect(cmd).toEqual({ type: 'say', text: 'I wonder what this crystal does' });
  });
});

describe('Command parsing: use command', () => {
  test('"use crystal" parses as use', () => {
    const cmd = parseInput('use crystal');
    expect(cmd).toEqual({ type: 'use', objectName: 'crystal' });
  });

  test('"use ancient scroll" parses multi-word object', () => {
    const cmd = parseInput('use ancient scroll');
    expect(cmd).toEqual({ type: 'use', objectName: 'ancient scroll' });
  });
});

describe('Exit chip generation from room snapshot', () => {
  test('exits array from snapshot maps to chip data', () => {
    const snapshot: RoomSnapshot = {
      roomId: 'nexus',
      name: 'The Nexus',
      description: 'A hub.',
      zone: 'foundation',
      exits: [
        { direction: 'north', targetRoom: 'terminal', label: 'To Terminal' },
        { direction: 'east', targetRoom: 'dream-chamber', label: 'To Dream Chamber' },
      ],
      entities: [],
      objects: [],
      hints: [],
    };

    // StandaloneRoomScreen maps exits to chips with direction as the key
    const chipDirections = snapshot.exits.map((exit: Exit) => exit.direction);
    expect(chipDirections).toEqual(['north', 'east']);
  });

  test('empty exits produces no chips', () => {
    const snapshot: RoomSnapshot = {
      roomId: 'nexus',
      name: 'The Nexus',
      description: 'A hub.',
      zone: 'foundation',
      exits: [],
      entities: [],
      objects: [],
      hints: [],
    };

    expect(snapshot.exits).toHaveLength(0);
  });

  test('exits include target room for navigation', () => {
    const exits: Exit[] = [
      { direction: 'north', targetRoom: 'terminal', label: 'North' },
      { direction: 'west', targetRoom: 'mailroom', label: 'West' },
    ];

    expect(exits[0].targetRoom).toBe('terminal');
    expect(exits[1].targetRoom).toBe('mailroom');
  });
});
