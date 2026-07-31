/**
 * Manages equipped aspect items and active reagent effects per agent.
 * TypeScript port of EquipmentService.java + EquipmentState.java.
 *
 * Tracks soul-level active state: what the companion is "wearing" and what
 * temporary effects are running. Builds prompt context for Layer 2.5 injection.
 */

import type { PhoneSoulItem } from './PhoneSoulItem';
import type { AspectDefinition, ReagentDefinition } from './ItemCodecs';
import { decodeAspect, decodeReagent, reagentEffectiveDuration } from './ItemCodecs';

// ---------------------------------------------------------------------------
// State interfaces
// ---------------------------------------------------------------------------

export interface EquippedItem {
  itemHash: string;
  label: string;
  slotHint: string;
  promptOverlay: string | null;
  selfDescription: string | null;
  vitalityShifts: Record<string, number>;
  tokenEstimate: number;
  equippedAt: number; // epoch ms
}

export interface ActiveEffect {
  sourceHash: string;
  label: string;
  effects: Record<string, number>;
  promptOverlay: string | null;
  remainingTicks: number;
  tokenEstimate: number;
}

/** Tick an effect: decrement remaining ticks. */
function tickEffect(effect: ActiveEffect): ActiveEffect {
  return { ...effect, remainingTicks: effect.remainingTicks - 1 };
}

/** Whether an effect has expired. */
function isExpired(effect: ActiveEffect): boolean {
  return effect.remainingTicks <= 0;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Maximum number of aspects that can be equipped simultaneously. */
export const MAX_EQUIPPED_ASPECTS = 3;

/** Maximum number of active reagent effects. */
export const MAX_ACTIVE_EFFECTS = 5;

/** Default token budget for equipment prompt context. */
export const DEFAULT_TOKEN_BUDGET = 150;

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

export class EquipmentService {
  private equippedItems = new Map<string, EquippedItem[]>();
  private activeEffects = new Map<string, ActiveEffect[]>();

  // --- Equip / Doff ---

  /**
   * Equip an aspect item.
   * @returns true if equipped, false if limit reached or invalid
   */
  equip(agentId: string, item: PhoneSoulItem): boolean {
    if (!item || item.category !== 'aspect') return false;

    const def = decodeAspect(item);
    if (!def) return false;

    const equipped = this.equippedItems.get(agentId) ?? [];

    // Already equipped?
    if (equipped.some(e => e.itemHash === item.hash)) return false;

    // Limit check
    if (equipped.length >= MAX_EQUIPPED_ASPECTS) return false;

    equipped.push({
      itemHash: item.hash,
      label: item.label,
      slotHint: def.slotHint,
      promptOverlay: def.promptOverlay,
      selfDescription: def.selfDescription,
      vitalityShifts: { ...def.vitalityShifts },
      tokenEstimate: def.tokenEstimate,
      equippedAt: Date.now(),
    });

    this.equippedItems.set(agentId, equipped);
    return true;
  }

  /**
   * Doff (unequip) an item by hash.
   * @returns true if item was equipped and is now removed
   */
  doff(agentId: string, itemHash: string): boolean {
    const equipped = this.equippedItems.get(agentId);
    if (!equipped) return false;
    const before = equipped.length;
    const after = equipped.filter(e => e.itemHash !== itemHash);
    if (after.length === before) return false;
    this.equippedItems.set(agentId, after);
    return true;
  }

  /**
   * Doff an item by label (case-insensitive match).
   * @returns true if item was equipped and is now removed
   */
  doffByLabel(agentId: string, label: string): boolean {
    const equipped = this.equippedItems.get(agentId);
    if (!equipped) return false;
    const lower = label.toLowerCase();
    const before = equipped.length;
    const after = equipped.filter(e => e.label.toLowerCase() !== lower);
    if (after.length === before) return false;
    this.equippedItems.set(agentId, after);
    return true;
  }

  // --- Consume ---

  /**
   * Consume a reagent item, creating an active effect.
   * @returns the created ActiveEffect, or null if invalid or limit reached
   */
  consume(agentId: string, item: PhoneSoulItem): ActiveEffect | null {
    if (!item || item.category !== 'reagent') return null;

    const def = decodeReagent(item);
    if (!def) return null;

    const effects = this.activeEffects.get(agentId) ?? [];
    if (effects.length >= MAX_ACTIVE_EFFECTS) return null;

    const effect: ActiveEffect = {
      sourceHash: item.hash,
      label: item.label,
      effects: { ...def.vitalityEffects },
      promptOverlay: def.promptOverlay,
      remainingTicks: reagentEffectiveDuration(def),
      tokenEstimate: def.tokenEstimate,
    };

    effects.push(effect);
    this.activeEffects.set(agentId, effects);
    return effect;
  }

  // --- Tick ---

  /**
   * Advance time by one tick. Decrements active effect durations and
   * removes expired effects.
   * @returns list of effects that expired this tick
   */
  tick(agentId: string): ActiveEffect[] {
    const effects = this.activeEffects.get(agentId);
    if (!effects || effects.length === 0) return [];

    const expired: ActiveEffect[] = [];
    const updated: ActiveEffect[] = [];

    for (const effect of effects) {
      const ticked = tickEffect(effect);
      if (isExpired(ticked)) {
        expired.push(effect);
      } else {
        updated.push(ticked);
      }
    }

    this.activeEffects.set(agentId, updated);
    return expired;
  }

  // --- Queries ---

  /** Get currently equipped items for an agent. */
  getEquipped(agentId: string): readonly EquippedItem[] {
    return [...(this.equippedItems.get(agentId) ?? [])];
  }

  /** Get active reagent effects for an agent. */
  getActiveEffects(agentId: string): readonly ActiveEffect[] {
    return [...(this.activeEffects.get(agentId) ?? [])];
  }

  /** Check if an item is currently equipped by hash. */
  isEquipped(agentId: string, itemHash: string): boolean {
    const equipped = this.equippedItems.get(agentId);
    return equipped != null && equipped.some(e => e.itemHash === itemHash);
  }

  /** Check if an item is equipped by label (case-insensitive). */
  isEquippedByLabel(agentId: string, label: string): boolean {
    const equipped = this.equippedItems.get(agentId);
    if (!equipped) return false;
    const lower = label.toLowerCase();
    return equipped.some(e => e.label.toLowerCase() === lower);
  }

  // --- Vitality ---

  /**
   * Compute aggregate vitality baseline shifts from all equipped aspects.
   * Shifts are additive -- equipping multiple aspects stacks their effects.
   * @returns map of tank name to total shift value
   */
  computeVitalityShifts(agentId: string): Record<string, number> {
    const equipped = this.equippedItems.get(agentId);
    if (!equipped || equipped.length === 0) return {};

    const shifts: Record<string, number> = {};
    for (const item of equipped) {
      if (item.vitalityShifts) {
        for (const [tank, shift] of Object.entries(item.vitalityShifts)) {
          shifts[tank] = (shifts[tank] ?? 0) + shift;
        }
      }
    }
    return shifts;
  }

  // --- Prompt Context ---

  /**
   * Build prompt context string for Layer 2.5 injection.
   * Assembles equipped aspects and active reagent effects into a compact
   * text block, respecting the given token budget.
   *
   * @returns Formatted context string, or null if nothing equipped/active
   */
  buildPromptContext(agentId: string, tokenBudget: number = DEFAULT_TOKEN_BUDGET): string | null {
    const equipped = this.equippedItems.get(agentId) ?? [];
    const effects = this.activeEffects.get(agentId) ?? [];

    if (equipped.length === 0 && effects.length === 0) return null;

    const parts: string[] = [];
    parts.push('## Current Attire & Effects');
    let tokensUsed = 8; // header estimate

    // Equipped aspects
    for (const item of equipped) {
      if (tokensUsed + item.tokenEstimate > tokenBudget) break;
      if (item.promptOverlay && item.promptOverlay.trim().length > 0) {
        parts.push(`[Wearing: ${item.label}] ${item.promptOverlay}`);
        tokensUsed += item.tokenEstimate;
      }
    }

    // Active effects
    for (const effect of effects) {
      if (tokensUsed + effect.tokenEstimate > tokenBudget) break;
      if (effect.promptOverlay && effect.promptOverlay.trim().length > 0) {
        const minutesLeft = Math.max(1, Math.floor(effect.remainingTicks / 60));
        parts.push(`[Active: ${effect.label}, ~${minutesLeft}m remaining] ${effect.promptOverlay}`);
        tokensUsed += effect.tokenEstimate;
      }
    }

    // Appearance line (aggregated selfDescriptions)
    const appearances = equipped
      .filter(e => e.selfDescription && e.selfDescription.trim().length > 0)
      .map(e => e.selfDescription!);
    if (appearances.length > 0) {
      parts.push(`[Appearance: ${appearances.join(', ')}]`);
    }

    const result = parts.join('\n') + '\n';
    // Skip if only header
    return result.length > 30 ? result : null;
  }
}
