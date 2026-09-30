import { extractProse, parseActions, stripNowLineEcho } from '../../src/engine/agent/ActionParser';
import { askedText, dateTimeText } from '../../src/inference/NowLine';

describe('ActionParser', () => {
  it('returns prose with no actions for plain text', () => {
    const result = parseActions('Hello, welcome to The Nexus!');
    expect(result.prose).toBe('Hello, welcome to The Nexus!');
    expect(result.actions).toHaveLength(0);
  });

  it('parses create_room action', () => {
    const text = `Welcome! Let me create a room for you.
\`\`\`json
{"action": "create_room", "name": "Gallery", "description": "A bright gallery.", "exits": [{"direction": "south", "target": "nexus", "label": "Back"}]}
\`\`\``;
    const result = parseActions(text);
    expect(result.prose).toContain('Welcome!');
    expect(result.actions).toHaveLength(1);
    expect(result.actions[0].type).toBe('create_room');
    if (result.actions[0].type === 'create_room') {
      expect(result.actions[0].name).toBe('Gallery');
      expect(result.actions[0].exits).toHaveLength(1);
      expect(result.actions[0].exits[0].direction).toBe('south');
    }
  });

  it('parses suggest_hints action', () => {
    const text = `Here are some options:
\`\`\`json
{"action": "suggest_hints", "hints": [
  {"label": "Photos", "intent": "photo", "action": "say"},
  {"label": "Organize", "intent": "org", "action": "say"}
]}
\`\`\``;
    const result = parseActions(text);
    expect(result.actions).toHaveLength(1);
    expect(result.actions[0].type).toBe('suggest_hints');
    if (result.actions[0].type === 'suggest_hints') {
      expect(result.actions[0].hints).toHaveLength(2);
      expect(result.actions[0].hints[0].label).toBe('Photos');
    }
  });

  it('parses multiple actions', () => {
    const text = `Creating your room!
\`\`\`json
{"action": "create_room", "name": "Gallery", "description": "Art room.", "exits": []}
\`\`\`
What would you like to do next?
\`\`\`json
{"action": "suggest_hints", "hints": [{"label": "Add photos", "intent": "photos", "action": "say"}]}
\`\`\``;
    const result = parseActions(text);
    expect(result.actions).toHaveLength(2);
    expect(result.actions[0].type).toBe('create_room');
    expect(result.actions[1].type).toBe('suggest_hints');
  });

  it('ignores malformed JSON blocks', () => {
    const text = `Hello!
\`\`\`json
{this is not valid json}
\`\`\``;
    const result = parseActions(text);
    expect(result.actions).toHaveLength(0);
    expect(result.prose).toContain('Hello!');
  });

  it('ignores JSON without action field', () => {
    const text = `Check this out:
\`\`\`json
{"name": "test", "value": 42}
\`\`\``;
    const result = parseActions(text);
    expect(result.actions).toHaveLength(0);
  });

  it('skips suggest_hints with empty hints array', () => {
    const text = `Options:
\`\`\`json
{"action": "suggest_hints", "hints": []}
\`\`\``;
    const result = parseActions(text);
    expect(result.actions).toHaveLength(0);
  });

  it('ignores unknown action types', () => {
    const text = `Action:
\`\`\`json
{"action": "delete_world"}
\`\`\``;
    const result = parseActions(text);
    expect(result.actions).toHaveLength(0);
  });
});

/**
 * The date line the phone stamps on her request (`[Now: …]`, a replay's
 * `[Asked: …]`), repeated at the start of a line of her reply, is not her
 * words. The server strips it (core ActionParser.NOW_LINE_ECHO); the phone
 * had no strip and said it in the room.
 */
describe('ActionParser — a repeated date line', () => {
  const now = dateTimeText(new Date('2026-09-23T14:05:00Z'), 'America/New_York');
  const asked = askedText(new Date('2026-09-23T13:40:00Z'), 'America/New_York');

  it('the Now line at the head of her reply is stripped', () => {
    expect(now).toBe('[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning]');
    expect(stripNowLineEcho(`${now}\nMorning. The tea is on.`)).toBe('Morning. The tea is on.');
  });

  it('with the closing bracket dropped, the way small models copy it', () => {
    expect(stripNowLineEcho('[Now: Wednesday 23 September 2026, 10:05 EDT (UTC-4), morning\nMorning.'))
      .toBe('Morning.');
  });

  it("a replay's Asked line, above or below the Now line, and a line in the middle", () => {
    expect(stripNowLineEcho(`${asked}\nYes.`)).toBe('Yes.');
    expect(stripNowLineEcho(`${now}\n${asked}\nWe planned tomatoes.`)).toBe('We planned tomatoes.');
    expect(stripNowLineEcho(`Morning.\n  ${now}  \nThe tea is on.`)).toBe('Morning.\nThe tea is on.');
    expect(stripNowLineEcho(`Morning.\n${now}`)).toBe('Morning.');
  });

  it('her own words are not a date line', () => {
    expect(stripNowLineEcho('Now: the kettle, then the letters.')).toBe('Now: the kettle, then the letters.');
    expect(stripNowLineEcho('I wrote [Now: soon] on the list.')).toBe('I wrote [Now: soon] on the list.');
    // At the end of a line it is still hers: the date line starts its line.
    expect(stripNowLineEcho('The note on the door says [Now: open]')).toBe('The note on the door says [Now: open]');
    expect(stripNowLineEcho('Morning.\nThe sign says [Now: open]\nCome in.'))
      .toBe('Morning.\nThe sign says [Now: open]\nCome in.');
    expect(stripNowLineEcho('')).toBe('');
  });

  it('only the line: nothing is left to say', () => {
    expect(stripNowLineEcho(now)).toBe('');
  });

  it('her prose from parseActions and extractProse never carries it, and her action still parses', () => {
    const reply = `${now}\nLet me note that.\n\`\`\`json\n{"action": "make_commitment", "description": "water the tomatoes"}\n\`\`\``;
    const result = parseActions(reply);
    expect(result.prose).toBe('Let me note that.');
    expect(result.actions.map((a) => a.type)).toEqual(['make_commitment']);
    expect(extractProse(reply)).toBe('Let me note that.');
    expect(parseActions(`${now}\nMorning.`).prose).toBe('Morning.');
    expect(extractProse(`${now}\nMorning.`)).toBe('Morning.');
  });

  it('on a line with un-fenced action JSON: once the JSON is gone the line is only the date line', () => {
    // The server and KMP strip the JSON first. RN stripped the date line first,
    // did not match it with the JSON still after it, and said it in the room.
    const json = '{"action":"emote","text":"waves"}';
    const head = `${now} ${json}\nHello.`;
    const tail = `Hello.\n${now} ${json}`;
    expect(parseActions(head).prose).toBe('Hello.');
    expect(parseActions(tail).prose).toBe('Hello.');
    expect(extractProse(head)).toBe('Hello.');
    expect(extractProse(tail)).toBe('Hello.');
    // Before a fenced block: extractProse's other branch.
    expect(extractProse(`${tail}\n\`\`\`json\n{"action": "make_commitment", "description": "x"}\n\`\`\``)).toBe('Hello.');
  });
});
