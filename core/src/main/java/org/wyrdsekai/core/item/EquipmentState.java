package org.wyrdsekai.core.item;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Per-agent equipment state: what's currently equipped and what effects are active.
 *
 * Equipped items are aspect SoulItem references. Active effects are from consumed
 * reagents and expire after their duration. This is the runtime state — SoulItems
 * in FamilyLocker are the source of truth for item definitions.
 */
public record EquipmentState(
    String agentId,
    List<EquippedItem> equipped,
    List<ActiveEffect> activeEffects
) {
    /** An aspect item currently equipped by the agent. */
    public record EquippedItem(
        String itemHash,
        String label,
        String slotHint,
        String promptOverlay,
        String selfDescription,
        Map<String, Double> vitalityShifts,
        int tokenEstimate,
        Instant equippedAt
    ) {}

    /** A temporary effect from a consumed reagent. */
    public record ActiveEffect(
        String sourceHash,
        String label,
        Map<String, Double> effects,
        String promptOverlay,
        int remainingTicks,
        int tokenEstimate
    ) {
        /** Create a new effect with decremented tick count. */
        public ActiveEffect tick() {
            return new ActiveEffect(sourceHash, label, effects,
                promptOverlay, remainingTicks - 1, tokenEstimate);
        }

        /** Whether this effect has expired. */
        public boolean expired() {
            return remainingTicks <= 0;
        }
    }

    /** Empty state for a new agent. */
    public static EquipmentState empty(String agentId) {
        return new EquipmentState(agentId, List.of(), List.of());
    }
}
