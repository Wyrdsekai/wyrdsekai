/**
 * Bridges the companion's action system with the item/equipment subsystem.
 * TypeScript port of KMP's CompanionCapabilityBridge.kt.
 *
 * Translates high-level companion actions (equip, doff, consume) into
 * concrete EquipmentService calls, looking up items from the LocalItemStore
 * and returning prose for the companion to speak.
 *
 * Also provides:
 * - Equipment tick forwarding (for vitality heartbeat)
 * - Capability context building (for Layer 2.7 prompt injection)
 * - Vitality shift computation from equipped aspects
 */

import type { EquipmentService } from '../item/EquipmentService';
import type { LocalItemStore } from '../persistence/LocalItemStore';
import type { SkillUsageTracker } from './SkillUsageTracker';
import type { VitalityState } from './VitalityState';
import { decodeAspect, decodeReagent } from '../item/ItemCodecs';
import { buildCapabilityContext, type SkillSummaryItem } from './CapabilityContextBuilder';

export class CompanionCapabilityBridge {
  constructor(
    private readonly equipmentService: EquipmentService,
    private readonly itemStore: LocalItemStore,
    private readonly usageTracker: SkillUsageTracker,
  ) {}

  /**
   * Handle an equip action. Returns prose to speak.
   */
  async handleEquip(agentId: string, itemName: string): Promise<string> {
    const items = await this.itemStore.byLabel(itemName);
    const item = items[0] ?? null;
    if (!item) return `I don't have an item called '${itemName}'.`;
    if (item.category !== 'aspect') return `'${itemName}' isn't something I can wear.`;

    const def = decodeAspect(item);
    if (!def) return `I can't figure out how to wear '${itemName}'.`;

    const result = this.equipmentService.equip(agentId, item);
    if (result) {
      const desc = def.selfDescription;
      return desc && desc.trim().length > 0 ? `*${desc}*` : `*equips ${itemName}*`;
    } else {
      if (this.equipmentService.isEquippedByLabel(agentId, itemName)) {
        return `I'm already wearing '${itemName}'.`;
      }
      return "I'm already wearing too many things. I'd need to take something off first.";
    }
  }

  /**
   * Handle a doff (unequip) action. Returns prose to speak.
   */
  handleDoff(agentId: string, itemName: string): string {
    const success = this.equipmentService.doffByLabel(agentId, itemName);
    return success ? `*removes ${itemName}*` : `I'm not wearing '${itemName}'.`;
  }

  /**
   * Handle a consume action. Returns prose to speak.
   */
  async handleConsume(agentId: string, itemName: string): Promise<string> {
    const items = await this.itemStore.byLabel(itemName);
    const item = items[0] ?? null;
    if (!item) return `I don't have '${itemName}'.`;
    if (item.category !== 'reagent') return `'${itemName}' isn't something I can use that way.`;

    const def = decodeReagent(item);
    if (!def) return `I can't figure out how to use '${itemName}'.`;

    const effect = this.equipmentService.consume(agentId, item);
    if (effect) {
      const desc = def.promptOverlay;
      return desc && desc.trim().length > 0 ? `*${desc}*` : `*uses ${itemName}*`;
    } else {
      return 'I have too many active effects right now.';
    }
  }

  /**
   * Tick equipment effects (call on each vitality heartbeat).
   * Decrements active reagent effect durations and removes expired ones.
   */
  tick(agentId: string) {
    return this.equipmentService.tick(agentId);
  }

  /**
   * Build capability context for prompt injection (Layer 2.7).
   *
   * Combines skill items from the item store, equipment prompt context,
   * and vitality state into a single context string for FullPromptAssembler.
   */
  async buildCapabilityContext(
    agentId: string,
    vitality: VitalityState,
  ): Promise<string | null> {
    const skillItems = await this.itemStore.byCategory('skill');
    const equipmentContext = this.equipmentService.buildPromptContext(agentId);

    // Convert PhoneSoulItems to SkillSummaryItems
    const skills: SkillSummaryItem[] = skillItems.map(item => ({
      name: item.label,
      description: item.text.length > 200 ? item.text.substring(0, 200) : item.text,
    }));

    const result = buildCapabilityContext(
      skills,
      equipmentContext,
      vitality,
      null, // proactivityTracker
      null, // assessmentText
    );
    return result.trim().length > 0 ? result : null;
  }

  /**
   * Get vitality shifts from equipped aspects.
   * These are additive baseline modifiers for the vitality system.
   */
  computeVitalityShifts(agentId: string): Record<string, number> {
    return this.equipmentService.computeVitalityShifts(agentId);
  }
}
