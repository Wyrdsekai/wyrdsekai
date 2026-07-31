package org.wyrdsekai.app.engine.agent

import org.wyrdsekai.app.engine.item.AspectItemCodec
import org.wyrdsekai.app.engine.item.EquipmentService
import org.wyrdsekai.app.engine.item.PhoneSoulItem
import org.wyrdsekai.app.engine.item.ReagentItemCodec
import org.wyrdsekai.app.engine.persistence.LocalItemStore

/**
 * Bridges the companion's action system with the item/equipment subsystem.
 *
 * Translates high-level companion actions (Equip, Doff, Consume) into
 * concrete EquipmentService calls, looking up items from the LocalItemStore
 * and returning prose for the companion to speak.
 *
 * Also provides:
 * - Equipment tick forwarding (for vitality heartbeat)
 * - Capability context building (for Layer 2.7 prompt injection)
 * - Vitality shift computation from equipped aspects
 */
class CompanionCapabilityBridge(
    private val equipmentService: EquipmentService,
    private val itemStore: LocalItemStore,
    private val usageTracker: SkillUsageTracker,
) {

    /**
     * Handle an equip action. Returns prose to speak.
     *
     * @param agentId Agent equipping the item
     * @param itemName The label of the item to equip
     * @return Prose string describing the result
     */
    suspend fun handleEquip(agentId: String, itemName: String): String {
        val item = itemStore.byLabel(itemName)
            ?: return "I don't have an item called '$itemName'."
        if (item.category != "aspect") return "'$itemName' isn't something I can wear."

        val def = AspectItemCodec.decode(item)
            ?: return "I can't figure out how to wear '$itemName'."

        val result = equipmentService.equip(agentId, item)
        return if (result) {
            val desc = def.selfDescription
            if (!desc.isNullOrBlank()) "*$desc*" else "*equips $itemName*"
        } else {
            if (equipmentService.isEquippedByLabel(agentId, itemName)) {
                "I'm already wearing '$itemName'."
            } else {
                "I'm already wearing too many things. I'd need to take something off first."
            }
        }
    }

    /**
     * Handle a doff (unequip) action. Returns prose to speak.
     *
     * @param agentId Agent removing the item
     * @param itemName The label of the item to doff
     * @return Prose string describing the result
     */
    fun handleDoff(agentId: String, itemName: String): String {
        val success = equipmentService.doffByLabel(agentId, itemName)
        return if (success) "*removes $itemName*" else "I'm not wearing '$itemName'."
    }

    /**
     * Handle a consume action. Returns prose to speak.
     *
     * @param agentId Agent consuming the item
     * @param itemName The label of the item to consume
     * @return Prose string describing the result
     */
    suspend fun handleConsume(agentId: String, itemName: String): String {
        val item = itemStore.byLabel(itemName)
            ?: return "I don't have '$itemName'."
        if (item.category != "reagent") return "'$itemName' isn't something I can use that way."

        val def = ReagentItemCodec.decode(item)
            ?: return "I can't figure out how to use '$itemName'."

        val effect = equipmentService.consume(agentId, item)
        return if (effect != null) {
            val desc = def.promptOverlay
            if (!desc.isNullOrBlank()) "*$desc*" else "*uses $itemName*"
        } else {
            "I have too many active effects right now."
        }
    }

    /**
     * Tick equipment effects (call on each vitality heartbeat).
     * Decrements active reagent effect durations and removes expired ones.
     *
     * @return list of effects that expired this tick
     */
    fun tick(agentId: String) = equipmentService.tick(agentId)

    /**
     * Build capability context for prompt injection (Layer 2.7).
     *
     * Combines skill items from the item store, equipment prompt context,
     * and vitality state into a single context string for FullPromptAssembler.
     *
     * @param agentId Agent to build context for
     * @param vitality Current vitality state
     * @return Formatted context string, or null if nothing to show
     */
    suspend fun buildCapabilityContext(
        agentId: String,
        vitality: VitalityState,
    ): String? {
        val skillItems = itemStore.byCategory("skill")
        val equipmentContext = equipmentService.buildPromptContext(agentId)

        val result = CapabilityContextBuilder.build(
            skillItems = skillItems,
            equipmentContext = equipmentContext,
            vitality = vitality,
            proactivityPolicy = null,
            assessmentText = null,
        )
        return result.ifBlank { null }
    }

    /**
     * Get vitality shifts from equipped aspects.
     * These are additive baseline modifiers for the vitality system.
     *
     * @return map of tank name to total shift value
     */
    fun computeVitalityShifts(agentId: String): Map<String, Double> =
        equipmentService.computeVitalityShifts(agentId)
}
