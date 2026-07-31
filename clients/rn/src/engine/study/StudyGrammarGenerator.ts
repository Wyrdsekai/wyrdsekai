/**
 * Generates GBNF grammars for the Study room on phone.
 * TypeScript port of KMP's StudyGrammarGenerator.kt.
 *
 * Constrains 0.6B model output to valid Study actions.
 * Speech is always free. Actions constrained to room state.
 */

import type { Exit } from '../../protocol/models';

function escapeGbnf(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

/**
 * Generate a GBNF grammar for the Study room.
 * @param exits Available exits from the current room
 * @returns GBNF grammar string
 */
export function generateStudyGrammar(exits: Exit[] = []): string {
  const rules: string[] = [];
  const alts: string[] = [];

  // Free speech — always available, unconstrained
  rules.push('speech ::= "say:" freetext');
  rules.push('freetext ::= [^\\n]+');
  alts.push('speech');

  // Emote
  rules.push('emote ::= "emote:" freetext');
  alts.push('emote');

  // Navigation
  if (exits.length > 0) {
    const dirs = exits.map(e => `"${escapeGbnf(e.direction)}"`).join(' | ');
    rules.push('navigate ::= "go:" direction');
    rules.push(`direction ::= ${dirs}`);
    alts.push('navigate');
  }

  // Study objects (fixed set)
  rules.push('use_object ::= "use:" object_name');
  rules.push('object_name ::= "journal" | "desk" | "shelves" | "pinboard"');
  alts.push('use_object');

  // Look
  rules.push('look ::= "look" | "look:" freetext');
  alts.push('look');

  // Study-specific verbs
  const studyVerbs: [string, boolean][] = [
    ['journal_write', true],
    ['journal_search', true],
    ['journal_private', true],
    ['note_add', true],
    ['note_search', true],
    ['pin', true],
    ['remind', true],
    ['summarize', false],
    ['digest', false],
  ];

  for (const [name, hasArg] of studyVerbs) {
    if (hasArg) {
      rules.push(`${name} ::= "${name}:" freetext`);
    } else {
      rules.push(`${name} ::= "${name}"`);
    }
    alts.push(name);
  }

  const root = `root ::= ${alts.join(' | ')}`;
  return root + '\n\n' + rules.join('\n') + '\n';
}
