package org.wyrdsekai.app.engine.agent

import org.wyrdsekai.app.engine.item.PhoneSoulItem
import org.wyrdsekai.app.engine.skill.SkillItemCodec

/**
 * Builds PromptAssembler Layer 2.7: Capability Context for the phone client.
 *
 * Simplified port of core/agent/CapabilityContextBuilder.java.
 * Phone version omits MCP gateway, OpenClaw, zone context, and workshop
 * sections (those require server infrastructure). Focuses on:
 *   - Skill items from soul inventory
 *   - Equipped item context
 *   - Vitality gates
 *   - Proactivity policy
 *   - Self-assessment note
 *
 * Budget: 10% of remaining tokens after Layer 2.6.
 */
object CapabilityContextBuilder {

    /** Budget cap: capability context uses at most 10% of remaining tokens. */
    const val CAPABILITY_BUDGET_FRACTION: Double = 0.10

    /** Minimum energy to show skill capabilities. */
    private const val SKILL_ENERGY_GATE: Double = 0.4

    /** Minimum energy for proactive skill display. */
    private const val PROACTIVE_ENERGY_GATE: Double = 0.6

    /**
     * Build the capability context string for prompt injection.
     *
     * @param skillItems       Skill-category PhoneSoulItems from the agent's inventory
     * @param equipmentContext  Pre-built equipment prompt text (from EquipmentService), nullable
     * @param vitality          Current vitality state, nullable
     * @param proactivityPolicy Proactivity policy, nullable
     * @param assessmentText    Latest self-assessment text, nullable
     * @return Formatted context string, or empty string if nothing to show
     */
    fun build(
        skillItems: List<PhoneSoulItem>,
        equipmentContext: String?,
        vitality: VitalityState?,
        proactivityPolicy: ProactivityPolicy?,
        assessmentText: String?,
    ): String {
        val sb = StringBuilder()

        // Section 0: Available tools (matches server ToolItemStarterKit — 14 tools)
        sb.append("## Available Tools\n")
        sb.append("Your actions are provided as tools. Use JSON action blocks to act.\n")
        sb.append("You can CREATE new tools using craft_from_template. Crafted items become immediately usable.\n")
        sb.append("Equipped: Library Card, Searching Glass, Quill, Sending Stone, Task Ledger, Channel Stone, Craft From Template\n")
        sb.append("Inherent: move, examine, express, remember, goal_done, pick up, introspect\n\n")

        // Section 1: Skill items (additional soul skills beyond starter kit)
        val skillSection = buildSkillSection(skillItems, vitality)
        if (skillSection != null) sb.append(skillSection)

        // Section 2: Equipment context
        if (!equipmentContext.isNullOrBlank()) {
            sb.append(equipmentContext).append("\n")
        }

        // Section 3: Vitality gates
        if (vitality != null) {
            sb.append("Energy: ").append(truncate2(vitality.energy))
            sb.append(" | Context Budget: ")
                .append(truncate2(vitality.contextBudget)).append("\n")

            if (vitality.energy < SKILL_ENERGY_GATE) {
                sb.append("(Low energy — skill usage restricted)\n")
            }
        }

        // Section 4: Proactivity note
        if (proactivityPolicy != null && vitality != null) {
            val proactiveSection = proactivityPolicy.buildContextSection(
                vitality.energy, vitality.confidence,
            )
            if (proactiveSection != null) {
                sb.append("\n").append(proactiveSection)
            }
        }

        // Section 5: Assessment note
        if (!assessmentText.isNullOrBlank()) {
            sb.append("\n## Recent Assessment\n")
            sb.append(assessmentText).append("\n")
        }

        return sb.toString()
    }

    /**
     * Minimal overload — skills and vitality only.
     */
    fun build(
        skillItems: List<PhoneSoulItem>,
        vitality: VitalityState?,
    ): String = build(
        skillItems = skillItems,
        equipmentContext = null,
        vitality = vitality,
        proactivityPolicy = null,
        assessmentText = null,
    )

    // --- Internal ---

    internal fun buildSkillSection(
        skillItems: List<PhoneSoulItem>,
        vitality: VitalityState?,
    ): String? {
        if (skillItems.isEmpty()) return null

        // Gate: don't show skills when energy is too low
        if (vitality != null && vitality.energy < SKILL_ENERGY_GATE) return null

        val decoded = skillItems.mapNotNull { item ->
            val def = SkillItemCodec.decode(item)
            if (def != null) item.label to def else null
        }
        if (decoded.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("## Available Capabilities\n")
        for ((label, def) in decoded) {
            sb.append("- ").append(label)
            if (!def.description.isNullOrBlank()) {
                sb.append(": ").append(def.description)
            }
            if (def.params.isNotEmpty()) {
                val paramNames = def.params.joinToString(", ") { p ->
                    if (p.required) p.name else "${p.name}?"
                }
                sb.append(" (").append(paramNames).append(")")
            }
            sb.append("\n")
        }
        sb.append("\n")
        return sb.toString()
    }
}

/** Truncate a Double to 2 decimal places (KMP-safe, no String.format). */
private fun truncate2(v: Double): String {
    val rounded = kotlin.math.round(v * 100) / 100.0
    return rounded.toString()
}
