package org.wyrdsekai.app.engine.item

/**
 * Per-agent equipment state: what's currently equipped and what effects are active.
 *
 * Equipped items are aspect PhoneSoulItem references. Active effects are from consumed
 * reagents and expire after their duration. This is the runtime state — PhoneSoulItems
 * in FamilyLocker are the source of truth for item definitions.
 *
 * Mirrors server's EquipmentState.java.
 */
data class EquipmentState(
    val agentId: String,
    val equipped: List<EquippedItem>,
    val activeEffects: List<ActiveEffect>,
) {
    companion object {
        /** Empty state for a new agent. */
        fun empty(agentId: String): EquipmentState =
            EquipmentState(agentId, emptyList(), emptyList())
    }
}

/** An aspect item currently equipped by the agent. */
data class EquippedItem(
    val itemHash: String,
    val label: String,
    val slotHint: String,
    val promptOverlay: String?,
    val selfDescription: String?,
    val vitalityShifts: Map<String, Double>,
    val tokenEstimate: Int,
    val equippedAt: Long, // epoch millis
)

/** A temporary effect from a consumed reagent. */
data class ActiveEffect(
    val sourceHash: String,
    val label: String,
    val effects: Map<String, Double>,
    val promptOverlay: String?,
    val remainingTicks: Int,
    val tokenEstimate: Int,
) {
    /** Create a new effect with decremented tick count. */
    fun tick(): ActiveEffect = copy(remainingTicks = remainingTicks - 1)

    /** Whether this effect has expired. */
    fun expired(): Boolean = remainingTicks <= 0
}

/**
 * Manages equipped aspect items and active reagent effects per agent.
 *
 * Parallel to InventoryService (room-level items), EquipmentService tracks
 * soul-level active state: what the companion is "wearing" and what temporary
 * effects are running. Builds prompt context for Layer 2.5 injection.
 *
 * Phone variant: uses plain HashMap — single-threaded coroutine access assumed
 * (all mutations from the CompanionEngine coroutine scope).
 *
 * Mirrors server's EquipmentService.java.
 */
class EquipmentService {

    companion object {
        /** Maximum number of aspects that can be equipped simultaneously. */
        const val MAX_EQUIPPED_ASPECTS: Int = 3

        /** Maximum number of active reagent effects. */
        const val MAX_ACTIVE_EFFECTS: Int = 5

        /** Default token budget for equipment prompt context. */
        const val DEFAULT_TOKEN_BUDGET: Int = 150
    }

    private val equippedItems: MutableMap<String, MutableList<EquippedItem>> = mutableMapOf()
    private val activeEffectsMap: MutableMap<String, MutableList<ActiveEffect>> = mutableMapOf()

    // --- Equip / Doff ---

    /**
     * Equip an aspect item.
     *
     * @param agentId Agent equipping the item
     * @param item    PhoneSoulItem with category "aspect"
     * @return true if equipped, false if limit reached or invalid
     */
    fun equip(agentId: String, item: PhoneSoulItem): Boolean {
        if (item.category != "aspect") return false

        val def = AspectItemCodec.decode(item) ?: return false

        val equipped = equippedItems.getOrPut(agentId) { mutableListOf() }

        // Check if already equipped
        if (equipped.any { it.itemHash == item.hash }) return false

        // Check limit
        if (equipped.size >= MAX_EQUIPPED_ASPECTS) return false

        equipped.add(
            EquippedItem(
                itemHash = item.hash,
                label = item.label,
                slotHint = def.slotHint,
                promptOverlay = def.promptOverlay,
                selfDescription = def.selfDescription,
                vitalityShifts = def.vitalityShifts,
                tokenEstimate = def.tokenEstimate,
                equippedAt = currentTimeMillis(),
            )
        )
        return true
    }

    /**
     * Doff (unequip) an item by hash.
     *
     * @return true if item was equipped and is now removed
     */
    fun doff(agentId: String, itemHash: String): Boolean {
        val equipped = equippedItems[agentId] ?: return false
        return equipped.removeAll { it.itemHash == itemHash }
    }

    /**
     * Doff an item by label (case-insensitive match).
     *
     * @return true if item was equipped and is now removed
     */
    fun doffByLabel(agentId: String, label: String): Boolean {
        val equipped = equippedItems[agentId] ?: return false
        return equipped.removeAll { it.label.equals(label, ignoreCase = true) }
    }

    // --- Consume ---

    /**
     * Consume a reagent item, creating an active effect.
     *
     * @param agentId Agent consuming the item
     * @param item    PhoneSoulItem with category "reagent"
     * @return the created ActiveEffect, or null if invalid or limit reached
     */
    fun consume(agentId: String, item: PhoneSoulItem): ActiveEffect? {
        if (item.category != "reagent") return null

        val def = ReagentItemCodec.decode(item) ?: return null

        val effects = activeEffectsMap.getOrPut(agentId) { mutableListOf() }
        if (effects.size >= MAX_ACTIVE_EFFECTS) return null

        val effect = ActiveEffect(
            sourceHash = item.hash,
            label = item.label,
            effects = def.vitalityEffects,
            promptOverlay = def.promptOverlay,
            remainingTicks = def.effectiveDuration(),
            tokenEstimate = def.tokenEstimate,
        )
        effects.add(effect)

        // Note: consumed items are tracked by the caller. FamilyLocker is
        // content-addressed and append-only — tombstoning is handled at sync time.

        return effect
    }

    // --- Tick ---

    /**
     * Advance time by one tick. Decrements active effect durations and
     * removes expired effects.
     *
     * @return list of effects that expired this tick
     */
    fun tick(agentId: String): List<ActiveEffect> {
        val effects = activeEffectsMap[agentId]
        if (effects.isNullOrEmpty()) return emptyList()

        val expired = mutableListOf<ActiveEffect>()
        val updated = mutableListOf<ActiveEffect>()

        for (effect in effects) {
            val ticked = effect.tick()
            if (ticked.expired()) {
                expired.add(effect)
            } else {
                updated.add(ticked)
            }
        }

        activeEffectsMap[agentId] = updated
        return expired
    }

    // --- Queries ---

    /** Get currently equipped items for an agent. */
    fun getEquipped(agentId: String): List<EquippedItem> =
        equippedItems[agentId]?.toList() ?: emptyList()

    /** Get active reagent effects for an agent. */
    fun getActiveEffects(agentId: String): List<ActiveEffect> =
        activeEffectsMap[agentId]?.toList() ?: emptyList()

    /** Get full equipment state for an agent. */
    fun getState(agentId: String): EquipmentState =
        EquipmentState(agentId, getEquipped(agentId), getActiveEffects(agentId))

    /** Check if an item is currently equipped. */
    fun isEquipped(agentId: String, itemHash: String): Boolean =
        equippedItems[agentId]?.any { it.itemHash == itemHash } ?: false

    /** Check if an item is equipped by label (case-insensitive). */
    fun isEquippedByLabel(agentId: String, label: String): Boolean =
        equippedItems[agentId]?.any { it.label.equals(label, ignoreCase = true) } ?: false

    // --- Vitality ---

    /**
     * Compute aggregate vitality baseline shifts from all equipped aspects.
     * These shifts are additive — equipping multiple aspects stacks their effects.
     *
     * @return map of tank name to total shift value
     */
    fun computeVitalityShifts(agentId: String): Map<String, Double> {
        val equipped = equippedItems[agentId]
        if (equipped.isNullOrEmpty()) return emptyMap()

        val shifts = mutableMapOf<String, Double>()
        for (item in equipped) {
            for ((tank, shift) in item.vitalityShifts) {
                shifts[tank] = (shifts[tank] ?: 0.0) + shift
            }
        }
        return shifts.toMap()
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
    fun buildPromptContext(agentId: String, tokenBudget: Int = DEFAULT_TOKEN_BUDGET): String? {
        val equipped = equippedItems[agentId] ?: emptyList()
        val effects = activeEffectsMap[agentId] ?: emptyList()

        if (equipped.isEmpty() && effects.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("## Current Attire & Effects\n")
        var tokensUsed = 8 // header estimate

        // Equipped aspects (sorted by equip time — oldest first)
        for (item in equipped) {
            if (tokensUsed + item.tokenEstimate > tokenBudget) break
            if (!item.promptOverlay.isNullOrBlank()) {
                sb.append("[Wearing: ").append(item.label).append("] ")
                    .append(item.promptOverlay).append("\n")
                tokensUsed += item.tokenEstimate
            }
        }

        // Active effects
        for (effect in effects) {
            if (tokensUsed + effect.tokenEstimate > tokenBudget) break
            if (!effect.promptOverlay.isNullOrBlank()) {
                val minutesLeft = maxOf(1, effect.remainingTicks / 60)
                sb.append("[Active: ").append(effect.label)
                    .append(", ~").append(minutesLeft).append("m remaining] ")
                    .append(effect.promptOverlay).append("\n")
                tokensUsed += effect.tokenEstimate
            }
        }

        // Appearance line (aggregated selfDescriptions)
        val appearances = equipped
            .filter { !it.selfDescription.isNullOrBlank() }
            .map { it.selfDescription!! }
        if (appearances.isNotEmpty()) {
            sb.append("[Appearance: ").append(appearances.joinToString(", ")).append("]\n")
        }

        return if (sb.length > 30) sb.toString() else null // Skip if only header
    }
}
