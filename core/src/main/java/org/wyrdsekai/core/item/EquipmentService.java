package org.wyrdsekai.core.item;

import org.wyrdsekai.core.soul.SoulItem;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages equipped aspect items and active reagent effects per agent.
 *
 * Parallel to InventoryService (room-level items), EquipmentService tracks
 * soul-level active state: what the companion is "wearing" and what temporary
 * effects are running. Builds prompt context for Layer 2.5 injection.
 *
 * Thread-safe: uses ConcurrentHashMap for state storage.
 */
public class EquipmentService {

    /** Global singleton for cross-actor equipment queries (look-at, ward checks). */
    private static volatile EquipmentService globalInstance;

    /** Get or create the global singleton. */
    public static EquipmentService get() {
        if (globalInstance == null) {
            globalInstance = new EquipmentService();
        }
        return globalInstance;
    }

    /** Maximum number of aspects that can be equipped simultaneously. */
    public static final int MAX_EQUIPPED_ASPECTS = 3;

    /** Maximum number of active reagent effects. */
    public static final int MAX_ACTIVE_EFFECTS = 5;

    /** Default token budget for equipment prompt context. */
    public static final int DEFAULT_TOKEN_BUDGET = 150;

    private final Map<String, List<EquipmentState.EquippedItem>> equippedItems = new ConcurrentHashMap<>();
    private final Map<String, List<EquipmentState.ActiveEffect>> activeEffects = new ConcurrentHashMap<>();

    // --- Equip / Doff ---

    /**
     * Equip an aspect item.
     *
     * @param agentId Agent equipping the item
     * @param item    SoulItem with category "aspect"
     * @return true if equipped, false if limit reached or invalid
     */
    public boolean equip(String agentId, SoulItem item) {
        if (item == null || !"aspect".equals(item.category())) return false;

        var def = AspectItemCodec.decode(item);
        if (def == null) return false;

        var equipped = equippedItems.computeIfAbsent(agentId, k -> new ArrayList<>());

        // Check if already equipped
        if (equipped.stream().anyMatch(e -> e.itemHash().equals(item.hash()))) return false;

        // Check limit
        if (equipped.size() >= MAX_EQUIPPED_ASPECTS) return false;

        equipped.add(new EquipmentState.EquippedItem(
            item.hash(), item.label(), def.slotHint(),
            def.promptOverlay(), def.selfDescription(),
            def.vitalityShifts(), def.tokenEstimate(),
            Instant.now()
        ));
        return true;
    }

    /**
     * Equip a ward item directly (bypasses aspect category check).
     * Used for Study Wards and other non-aspect equipped items.
     *
     * @param agentId   Agent receiving the ward
     * @param hash      Content-addressed hash
     * @param label     Display label (e.g., "Study Ward (mas)")
     * @param slotHint  Slot category (e.g., "study-ward")
     * @param overlay   Prompt overlay with ward data (contains study room ID)
     * @param appearance Visible description ("carrying a warm crystal ward")
     * @return true if equipped
     */
    public boolean equipWard(String agentId, String hash, String label,
                              String slotHint, String overlay, String appearance) {
        var equipped = equippedItems.computeIfAbsent(agentId, k -> new ArrayList<>());
        if (equipped.stream().anyMatch(e -> e.itemHash().equals(hash))) return false;
        equipped.add(new EquipmentState.EquippedItem(
            hash, label, slotHint, overlay, appearance,
            Map.of(), 10, Instant.now()));
        return true;
    }

    /**
     * Doff (unequip) an item by hash.
     *
     * @return true if item was equipped and is now removed
     */
    public boolean doff(String agentId, String itemHash) {
        var equipped = equippedItems.get(agentId);
        if (equipped == null) return false;
        return equipped.removeIf(e -> e.itemHash().equals(itemHash));
    }

    /**
     * Doff an item by label (case-insensitive match).
     *
     * @return true if item was equipped and is now removed
     */
    public boolean doffByLabel(String agentId, String label) {
        var equipped = equippedItems.get(agentId);
        if (equipped == null) return false;
        return equipped.removeIf(e -> e.label().equalsIgnoreCase(label));
    }

    // --- Consume ---

    /**
     * Consume a reagent item, creating an active effect.
     *
     * @param agentId Agent consuming the item
     * @param item    SoulItem with category "reagent"
     * @return the created ActiveEffect, or null if invalid or limit reached
     */
    public EquipmentState.ActiveEffect consume(String agentId, SoulItem item) {
        if (item == null || !"reagent".equals(item.category())) return null;

        var def = ReagentItemCodec.decode(item);
        if (def == null) return null;

        var effects = activeEffects.computeIfAbsent(agentId, k -> new ArrayList<>());
        if (effects.size() >= MAX_ACTIVE_EFFECTS) return null;

        var effect = new EquipmentState.ActiveEffect(
            item.hash(), item.label(), def.vitalityEffects(),
            def.promptOverlay(), def.effectiveDuration(), def.tokenEstimate()
        );
        effects.add(effect);

        // Note: consumed items are tracked by the caller. FamilyLocker is
        // content-addressed and append-only — tombstoning is handled at sync time.

        return effect;
    }

    // --- Tick ---

    /**
     * Advance time by one tick. Decrements active effect durations and
     * removes expired effects.
     *
     * @return list of effects that expired this tick
     */
    public List<EquipmentState.ActiveEffect> tick(String agentId) {
        var effects = activeEffects.get(agentId);
        if (effects == null || effects.isEmpty()) return List.of();

        var expired = new ArrayList<EquipmentState.ActiveEffect>();
        var updated = new ArrayList<EquipmentState.ActiveEffect>();

        for (var effect : effects) {
            var ticked = effect.tick();
            if (ticked.expired()) {
                expired.add(effect);
            } else {
                updated.add(ticked);
            }
        }

        activeEffects.put(agentId, updated);
        return expired;
    }

    // --- Queries ---

    /** Get currently equipped items for an agent. */
    public List<EquipmentState.EquippedItem> getEquipped(String agentId) {
        return List.copyOf(equippedItems.getOrDefault(agentId, List.of()));
    }

    /** Get active reagent effects for an agent. */
    public List<EquipmentState.ActiveEffect> getActiveEffects(String agentId) {
        return List.copyOf(activeEffects.getOrDefault(agentId, List.of()));
    }

    /** Get full equipment state for an agent. */
    public EquipmentState getState(String agentId) {
        return new EquipmentState(agentId, getEquipped(agentId), getActiveEffects(agentId));
    }

    /** Check if an item is currently equipped. */
    public boolean isEquipped(String agentId, String itemHash) {
        var equipped = equippedItems.get(agentId);
        return equipped != null && equipped.stream().anyMatch(e -> e.itemHash().equals(itemHash));
    }

    /** Check if an item is equipped by label (case-insensitive). */
    public boolean isEquippedByLabel(String agentId, String label) {
        var equipped = equippedItems.get(agentId);
        return equipped != null && equipped.stream().anyMatch(e -> e.label().equalsIgnoreCase(label));
    }

    // --- Vitality ---

    /**
     * Compute aggregate vitality baseline shifts from all equipped aspects.
     * These shifts are additive — equipping multiple aspects stacks their effects.
     *
     * @return map of tank name → total shift value
     */
    public Map<String, Double> computeVitalityShifts(String agentId) {
        var equipped = equippedItems.get(agentId);
        if (equipped == null || equipped.isEmpty()) return Map.of();

        var shifts = new HashMap<String, Double>();
        for (var item : equipped) {
            if (item.vitalityShifts() != null) {
                item.vitalityShifts().forEach((tank, shift) ->
                    shifts.merge(tank, shift, Double::sum));
            }
        }
        return Map.copyOf(shifts);
    }

    // --- Prompt Context ---

    /**
     * Build prompt context string for Layer 2.5 injection.
     * Assembles equipped aspects and active reagent effects into a compact
     * text block, respecting the given token budget.
     *
     * @param agentId     Agent to build context for
     * @param tokenBudget Maximum tokens for the equipment context
     * @return Formatted context string, or null if nothing equipped/active
     */
    public String buildPromptContext(String agentId, int tokenBudget) {
        var equipped = equippedItems.getOrDefault(agentId, List.of());
        var effects = activeEffects.getOrDefault(agentId, List.of());

        if (equipped.isEmpty() && effects.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("## Current Attire & Effects\n");
        int tokensUsed = 8; // header estimate

        // Equipped aspects (sorted by significance — oldest first as proxy)
        for (var item : equipped) {
            if (tokensUsed + item.tokenEstimate() > tokenBudget) break;
            if (item.promptOverlay() != null && !item.promptOverlay().isBlank()) {
                sb.append("[Wearing: ").append(item.label()).append("] ")
                  .append(item.promptOverlay()).append("\n");
                tokensUsed += item.tokenEstimate();
            }
        }

        // Active effects
        for (var effect : effects) {
            if (tokensUsed + effect.tokenEstimate() > tokenBudget) break;
            if (effect.promptOverlay() != null && !effect.promptOverlay().isBlank()) {
                int minutesLeft = Math.max(1, effect.remainingTicks() / 60);
                sb.append("[Active: ").append(effect.label())
                  .append(", ~").append(minutesLeft).append("m remaining] ")
                  .append(effect.promptOverlay()).append("\n");
                tokensUsed += effect.tokenEstimate();
            }
        }

        // Appearance line (aggregated selfDescriptions)
        var appearances = equipped.stream()
            .filter(e -> e.selfDescription() != null && !e.selfDescription().isBlank())
            .map(EquipmentState.EquippedItem::selfDescription)
            .toList();
        if (!appearances.isEmpty()) {
            sb.append("[Appearance: ").append(String.join(", ", appearances)).append("]\n");
        }

        return sb.length() > 30 ? sb.toString() : null; // Skip if only header
    }

    /**
     * Build prompt context with default token budget.
     */
    public String buildPromptContext(String agentId) {
        return buildPromptContext(agentId, DEFAULT_TOKEN_BUDGET);
    }
}
