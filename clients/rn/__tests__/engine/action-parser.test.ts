import { parseActions } from '../../src/engine/agent/ActionParser';

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
