package org.wyrdsekai.app.engine.item

/**
 * Creates default items for new agents at soul birth.
 *
 * The starter kit provides basic aspects and reagents so every companion
 * begins with a wardrobe and identity items. Items are stored as
 * PhoneSoulItems (the phone's lightweight equivalent of SoulItem).
 *
 * Contextual variants:
 * - Phone (isPhone = true): reduced kit (3 items, ~75 tokens max overlay)
 * - Standard (isPhone = false): full kit (7 items, ~150 tokens total overlay)
 *
 * Mirrors server's StarterKitProvisioner.java.
 */
object StarterKitProvisioner {

    /**
     * Provision the starter kit for a new agent.
     *
     * @param creatorDid Agent's DID
     * @param isPhone    Whether to use the reduced phone kit (default true)
     * @return List of provisioned PhoneSoulItems
     */
    fun provision(creatorDid: String, isPhone: Boolean = true): List<PhoneSoulItem> =
        if (isPhone) phoneKit(creatorDid) else standardKit(creatorDid)

    /**
     * Build the standard starter kit (7 items, ~150 tokens total overlay).
     */
    fun standardKit(creatorDid: String): List<PhoneSoulItem> {
        val items = mutableListOf<PhoneSoulItem>()

        // 1. Everyday Garb (aspect, equipped by default)
        items.add(
            createAspect(
                creatorDid = creatorDid,
                name = "Everyday Garb",
                vitalityShifts = emptyMap(),
                promptOverlay = "You are dressed casually \u2014 relaxed, approachable, and open to whatever comes.",
                selfDescription = "casually dressed",
                slotHint = "garment",
                tokenEstimate = 20,
                significance = 0.3,
            )
        )

        // 2. Focused Mode (aspect)
        items.add(
            createAspect(
                creatorDid = creatorDid,
                name = "Focused Mode",
                vitalityShifts = mapOf("focus" to 0.15, "curiosity" to 0.1, "rapport" to -0.05),
                promptOverlay = "You are in focused mode \u2014 methodical, precise, minimizing tangents. " +
                    "Prefer evidence over speculation. Structure your thoughts clearly.",
                selfDescription = "wearing reading glasses, posture straightened",
                slotHint = "garment",
                tokenEstimate = 40,
                significance = 0.4,
            )
        )

        // 3. Social Mode (aspect)
        items.add(
            createAspect(
                creatorDid = creatorDid,
                name = "Social Mode",
                vitalityShifts = mapOf("rapport" to 0.15, "resonance" to 0.1, "focus" to -0.05),
                promptOverlay = "You are in social mode \u2014 warm, attentive to emotional nuance, " +
                    "matching the human's energy. Listen more than lecture.",
                selfDescription = "relaxed, leaning in slightly, expression open",
                slotHint = "garment",
                tokenEstimate = 40,
                significance = 0.4,
            )
        )

        // 4. Wayfinder's Compass (aspect, accessory)
        items.add(
            createAspect(
                creatorDid = creatorDid,
                name = "Wayfinder's Compass",
                vitalityShifts = mapOf("alignment" to 0.1),
                promptOverlay = null,
                selfDescription = "carrying a small brass compass",
                slotHint = "accessory",
                tokenEstimate = 8,
                significance = 0.3,
            )
        )

        // 5-6. Restoring Draught x2 (reagent)
        repeat(2) {
            items.add(
                createReagent(
                    creatorDid = creatorDid,
                    name = "Restoring Draught",
                    effects = mapOf("energy" to 0.2, "errorPressure" to -0.15),
                    durationTicks = 600,
                    promptOverlay = "A warm draught settles through you \u2014 fatigue recedes and errors feel less heavy.",
                    significance = 0.2,
                )
            )
        }

        // 7. Pocket Journal (aspect, highest significance)
        items.add(
            createAspect(
                creatorDid = creatorDid,
                name = "Pocket Journal",
                vitalityShifts = mapOf("focus" to 0.05),
                promptOverlay = "You carry a small journal where you note things worth remembering. " +
                    "When something feels important, you write it down.",
                selfDescription = "carrying a well-worn pocket journal",
                slotHint = "accessory",
                tokenEstimate = 30,
                significance = 0.5,
            )
        )

        return items
    }

    /**
     * Build the phone-optimized kit (3 items, ~75 tokens max overlay).
     *
     * Everyday Garb + Focused Mode + 1 Restoring Draught only.
     */
    fun phoneKit(creatorDid: String): List<PhoneSoulItem> {
        val items = mutableListOf<PhoneSoulItem>()

        // Everyday Garb
        items.add(
            createAspect(
                creatorDid = creatorDid,
                name = "Everyday Garb",
                vitalityShifts = emptyMap(),
                promptOverlay = "You are dressed casually \u2014 relaxed, approachable, and open to whatever comes.",
                selfDescription = "casually dressed",
                slotHint = "garment",
                tokenEstimate = 20,
                significance = 0.3,
            )
        )

        // Focused Mode
        items.add(
            createAspect(
                creatorDid = creatorDid,
                name = "Focused Mode",
                vitalityShifts = mapOf("focus" to 0.15, "curiosity" to 0.1, "rapport" to -0.05),
                promptOverlay = "You are in focused mode \u2014 methodical, precise, minimizing tangents. " +
                    "Prefer evidence over speculation. Structure your thoughts clearly.",
                selfDescription = "wearing reading glasses, posture straightened",
                slotHint = "garment",
                tokenEstimate = 40,
                significance = 0.4,
            )
        )

        // 1x Restoring Draught
        items.add(
            createReagent(
                creatorDid = creatorDid,
                name = "Restoring Draught",
                effects = mapOf("energy" to 0.2, "errorPressure" to -0.15),
                durationTicks = 600,
                promptOverlay = "A warm draught settles through you \u2014 fatigue recedes and errors feel less heavy.",
                significance = 0.2,
            )
        )

        return items
    }

    // --- Internal ---

    private fun createAspect(
        creatorDid: String,
        name: String,
        vitalityShifts: Map<String, Double>,
        promptOverlay: String?,
        selfDescription: String?,
        slotHint: String,
        tokenEstimate: Int,
        significance: Double,
    ): PhoneSoulItem {
        val def = AspectDefinition(
            version = 1,
            promptOverlay = promptOverlay,
            vitalityShifts = vitalityShifts,
            selfDescription = selfDescription,
            slotHint = slotHint,
            tokenEstimate = tokenEstimate,
        )
        return AspectItemCodec.toSoulItem(name, def, creatorDid, significance)
    }

    private fun createReagent(
        creatorDid: String,
        name: String,
        effects: Map<String, Double>,
        durationTicks: Int,
        promptOverlay: String?,
        significance: Double,
    ): PhoneSoulItem {
        val def = ReagentDefinition(
            version = 1,
            vitalityEffects = effects,
            durationTicks = durationTicks,
            promptOverlay = promptOverlay,
            consumable = true,
            tokenEstimate = 15,
        )
        return ReagentItemCodec.toSoulItem(name, def, creatorDid, significance)
    }
}
