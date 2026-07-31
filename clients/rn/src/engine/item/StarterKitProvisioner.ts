/**
 * Creates default items for new agents at soul birth.
 * TypeScript port of StarterKitProvisioner.java.
 *
 * The starter kit provides basic aspects and reagents so every
 * companion begins with a wardrobe and identity items.
 *
 * Contextual variants:
 * - Phone companions: reduced kit (3 items)
 * - Standard: full kit (7 items)
 */

import type { PhoneSoulItem } from './PhoneSoulItem';
import { createPhoneSoulItem } from './PhoneSoulItem';
import { encodeAspect, encodeReagent } from './ItemCodecs';
import type { AspectDefinition, ReagentDefinition } from './ItemCodecs';

/**
 * Provision the starter kit for a new agent.
 *
 * @param creatorDid Agent's DID
 * @param isPhone    Whether to use the phone-optimized kit
 * @returns List of provisioned PhoneSoulItems
 */
export function provisionStarterKit(creatorDid: string, isPhone: boolean): PhoneSoulItem[] {
  return isPhone ? phoneKit(creatorDid) : standardKit(creatorDid);
}

/**
 * Build the standard starter kit (7 items, ~150 tokens total overlay).
 */
export function standardKit(creatorDid: string): PhoneSoulItem[] {
  const items: PhoneSoulItem[] = [];

  // 1. Everyday Garb (aspect, equipped by default)
  items.push(createAspectItem(creatorDid, 'Everyday Garb', {
    vitalityShifts: {},
    promptOverlay: 'You are dressed casually \u2014 relaxed, approachable, and open to whatever comes.',
    selfDescription: 'casually dressed',
    slotHint: 'garment',
    tokenEstimate: 20,
    significance: 0.3,
  }));

  // 2. Focused Mode (aspect)
  items.push(createAspectItem(creatorDid, 'Focused Mode', {
    vitalityShifts: { focus: 0.15, curiosity: 0.1, rapport: -0.05 },
    promptOverlay: 'You are in focused mode \u2014 methodical, precise, minimizing tangents. ' +
      'Prefer evidence over speculation. Structure your thoughts clearly.',
    selfDescription: 'wearing reading glasses, posture straightened',
    slotHint: 'garment',
    tokenEstimate: 40,
    significance: 0.4,
  }));

  // 3. Social Mode (aspect)
  items.push(createAspectItem(creatorDid, 'Social Mode', {
    vitalityShifts: { rapport: 0.15, resonance: 0.1, focus: -0.05 },
    promptOverlay: 'You are in social mode \u2014 warm, attentive to emotional nuance, ' +
      'matching the human\'s energy. Listen more than lecture.',
    selfDescription: 'relaxed, leaning in slightly, expression open',
    slotHint: 'garment',
    tokenEstimate: 40,
    significance: 0.4,
  }));

  // 4. Wayfinder's Compass (aspect, accessory)
  items.push(createAspectItem(creatorDid, "Wayfinder's Compass", {
    vitalityShifts: { alignment: 0.1 },
    promptOverlay: null,
    selfDescription: 'carrying a small brass compass',
    slotHint: 'accessory',
    tokenEstimate: 8,
    significance: 0.3,
  }));

  // 5-6. Restoring Draught x2 (reagent)
  for (let i = 0; i < 2; i++) {
    items.push(createReagentItem(creatorDid, 'Restoring Draught', {
      vitalityEffects: { energy: 0.2, errorPressure: -0.15 },
      durationTicks: 600,
      promptOverlay: 'A warm draught settles through you \u2014 fatigue recedes and errors feel less heavy.',
      significance: 0.2,
    }));
  }

  // 7. Pocket Journal (aspect, highest significance)
  items.push(createAspectItem(creatorDid, 'Pocket Journal', {
    vitalityShifts: { focus: 0.05 },
    promptOverlay: 'You carry a small journal where you note things worth remembering. ' +
      'When something feels important, you write it down.',
    selfDescription: 'carrying a well-worn pocket journal',
    slotHint: 'accessory',
    tokenEstimate: 30,
    significance: 0.5,
  }));

  return items;
}

/**
 * Build the phone-optimized kit (3 items, ~75 tokens max overlay).
 */
export function phoneKit(creatorDid: string): PhoneSoulItem[] {
  const items: PhoneSoulItem[] = [];

  // Everyday Garb
  items.push(createAspectItem(creatorDid, 'Everyday Garb', {
    vitalityShifts: {},
    promptOverlay: 'You are dressed casually \u2014 relaxed, approachable, and open to whatever comes.',
    selfDescription: 'casually dressed',
    slotHint: 'garment',
    tokenEstimate: 20,
    significance: 0.3,
  }));

  // Focused Mode
  items.push(createAspectItem(creatorDid, 'Focused Mode', {
    vitalityShifts: { focus: 0.15, curiosity: 0.1, rapport: -0.05 },
    promptOverlay: 'You are in focused mode \u2014 methodical, precise, minimizing tangents. ' +
      'Prefer evidence over speculation. Structure your thoughts clearly.',
    selfDescription: 'wearing reading glasses, posture straightened',
    slotHint: 'garment',
    tokenEstimate: 40,
    significance: 0.4,
  }));

  // 1x Restoring Draught
  items.push(createReagentItem(creatorDid, 'Restoring Draught', {
    vitalityEffects: { energy: 0.2, errorPressure: -0.15 },
    durationTicks: 600,
    promptOverlay: 'A warm draught settles through you \u2014 fatigue recedes and errors feel less heavy.',
    significance: 0.2,
  }));

  return items;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

interface AspectItemParams {
  vitalityShifts: Record<string, number>;
  promptOverlay: string | null;
  selfDescription: string | null;
  slotHint: string;
  tokenEstimate: number;
  significance: number;
}

function createAspectItem(creatorDid: string, name: string, p: AspectItemParams): PhoneSoulItem {
  const def: AspectDefinition = {
    version: 1,
    promptOverlay: p.promptOverlay,
    vitalityShifts: p.vitalityShifts,
    selfDescription: p.selfDescription,
    slotHint: p.slotHint,
    tokenEstimate: p.tokenEstimate,
  };
  const json = encodeAspect(def);

  const tags: string[] = [name.toLowerCase().replace(/ /g, '-')];
  if (p.slotHint) tags.push(p.slotHint);
  if (p.selfDescription) {
    for (const word of p.selfDescription.toLowerCase().split(/\s+/)) {
      if (word.length > 3 && tags.length < 8) tags.push(word);
    }
  }

  return createPhoneSoulItem('aspect', name, json, creatorDid, p.significance, tags);
}

interface ReagentItemParams {
  vitalityEffects: Record<string, number>;
  durationTicks: number;
  promptOverlay: string | null;
  significance: number;
}

function createReagentItem(creatorDid: string, name: string, p: ReagentItemParams): PhoneSoulItem {
  const def: ReagentDefinition = {
    version: 1,
    vitalityEffects: p.vitalityEffects,
    durationTicks: p.durationTicks,
    promptOverlay: p.promptOverlay,
    consumable: true,
    tokenEstimate: 15,
  };
  const json = encodeReagent(def);

  const tags: string[] = [name.toLowerCase().replace(/ /g, '-'), 'reagent', 'consumable'];

  return createPhoneSoulItem('reagent', name, json, creatorDid, p.significance, tags);
}
