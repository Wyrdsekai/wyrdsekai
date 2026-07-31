/**
 * Builds PromptAssembler Layer 2.7: Capability Context.
 * TypeScript port of CapabilityContextBuilder.java, simplified for phone.
 *
 * Tells the companion what it can do -- skill items, equipment context,
 * vitality levels, proactivity state, and self-assessment text.
 * The companion uses this to decide whether to use an existing capability,
 * build a new one, or escalate.
 *
 * Budget: 10% of remaining tokens after Layer 2.6.
 */

import type { VitalityState } from './VitalityState';
import type { ProactivityTracker } from './ProactivityPolicy';

/** Budget cap: capability context uses at most 10% of remaining tokens. */
export const CAPABILITY_BUDGET_FRACTION = 0.10;

export interface SkillSummaryItem {
  name: string;
  description: string | null;
}

/**
 * Build the capability context string for prompt injection.
 *
 * @param skills            Available skill items (name + description)
 * @param equipmentContext  Pre-built equipment context string (from EquipmentService), or null
 * @param vitality          Current vitality state, or null
 * @param proactivityTracker Proactivity tracker, or null
 * @param assessmentText    Latest self-assessment text, or null
 * @returns Formatted context string
 */
export function buildCapabilityContext(
  skills: SkillSummaryItem[],
  equipmentContext: string | null,
  vitality: VitalityState | null,
  proactivityTracker: ProactivityTracker | null,
  assessmentText: string | null,
): string {
  const parts: string[] = [];

  // Section 0: Available tools (matches server ToolItemStarterKit — 14 tools)
  parts.push('## Available Tools');
  parts.push('Your actions are provided as tools. Use JSON action blocks to act.');
  parts.push('You can CREATE new tools using craft_from_template. Crafted items become immediately usable.');
  parts.push('Equipped: Library Card, Searching Glass, Quill, Sending Stone, Task Ledger, Channel Stone, Craft From Template');
  parts.push('Inherent: move, examine, express, remember, goal_done, pick up, introspect');
  parts.push('');

  // Section 1: Skill items (additional soul skills beyond starter kit)
  if (skills.length > 0) {
    parts.push('## Available Capabilities');
    for (const skill of skills) {
      if (skill.description) {
        parts.push(`- ${skill.name}: ${skill.description}`);
      } else {
        parts.push(`- ${skill.name}`);
      }
    }
    parts.push('');
  }

  // Section 2: Equipment context (pre-built by EquipmentService)
  if (equipmentContext) {
    parts.push(equipmentContext);
  }

  // Section 3: Vitality gate
  if (vitality) {
    parts.push(
      `Energy: ${vitality.energy.toFixed(2)} | Context Budget: ${vitality.contextBudget.toFixed(2)}`,
    );
  }

  // Section 4: Proactive skills
  if (proactivityTracker && vitality) {
    const proactiveSection = proactivityTracker.buildContextSection(
      vitality.energy,
      vitality.confidence,
    );
    if (proactiveSection) {
      parts.push('');
      parts.push(proactiveSection);
    }
  }

  // Section 5: Self-assessment
  if (assessmentText) {
    parts.push('');
    parts.push(assessmentText);
  }

  return parts.join('\n');
}
