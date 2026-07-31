/**
 * Codec for the skill item JSON format stored in PhoneSoulItem.text.
 * TypeScript port of SkillItemCodec.java.
 *
 * A skill PhoneSoulItem's text field contains a JSON document describing
 * a companion-created capability (code, params, tests, metadata).
 */

import type { PhoneSoulItem } from '../item/PhoneSoulItem';

export interface SkillParam {
  name: string;
  type: string;
  description: string;
  required: boolean;
}

export interface SkillTestCase {
  params: Record<string, unknown>;
  expectSuccess: boolean;
  expectContains: string | null;
}

export interface SkillDefinition {
  version: number;
  runtime: string;
  code: string;
  params: SkillParam[];
  description: string | null;
  testCases: SkillTestCase[];
  dependencies: string[];
  usageCount: number;
  lastUsed: number | null; // epoch ms (null if never used)
}

/**
 * Decode a PhoneSoulItem's text field into a SkillDefinition.
 * Returns null if the item is not a valid skill.
 */
export function decodeSkill(item: PhoneSoulItem): SkillDefinition | null {
  if (!item || !item.text || item.category !== 'skill') return null;
  return decodeSkillJson(item.text);
}

/**
 * Decode a raw JSON string into a SkillDefinition.
 */
export function decodeSkillJson(json: string): SkillDefinition | null {
  if (!json) return null;
  try {
    const obj = JSON.parse(json);
    return {
      version: obj.version ?? 1,
      runtime: obj.runtime ?? 'graaljs',
      code: obj.code ?? '',
      params: Array.isArray(obj.params)
        ? obj.params.map((p: Record<string, unknown>) => ({
            name: (p.name as string) ?? '',
            type: (p.type as string) ?? 'string',
            description: (p.description as string) ?? '',
            required: Boolean(p.required),
          }))
        : [],
      description: obj.description ?? null,
      testCases: Array.isArray(obj.testCases)
        ? obj.testCases.map((t: Record<string, unknown>) => ({
            params: (t.params as Record<string, unknown>) ?? {},
            expectSuccess: t.expectSuccess !== false,
            expectContains: (t.expectContains as string) ?? null,
          }))
        : [],
      dependencies: Array.isArray(obj.dependencies) ? obj.dependencies : [],
      usageCount: typeof obj.usageCount === 'number' ? obj.usageCount : 0,
      lastUsed: typeof obj.lastUsed === 'number' ? obj.lastUsed : null,
    };
  } catch {
    return null;
  }
}

/**
 * Encode a SkillDefinition to a JSON string (for PhoneSoulItem.text).
 */
export function encodeSkill(def: SkillDefinition): string {
  return JSON.stringify(def);
}

/**
 * Create a new SkillDefinition for initial storage.
 */
export function createSkillDefinition(
  runtime: string,
  code: string,
  params: SkillParam[],
  description: string | null,
  testCases: SkillTestCase[],
  dependencies: string[],
): SkillDefinition {
  return {
    version: 1,
    runtime,
    code,
    params: params ?? [],
    description,
    testCases: testCases ?? [],
    dependencies: dependencies ?? [],
    usageCount: 0,
    lastUsed: null,
  };
}

/**
 * Return a copy with incremented usage count and updated timestamp.
 */
export function withUsage(def: SkillDefinition): SkillDefinition {
  return {
    ...def,
    usageCount: def.usageCount + 1,
    lastUsed: Date.now(),
  };
}
