package org.wyrdsekai.app.engine.agent

import kotlin.math.max
import kotlin.math.min

/**
 * Computes agent behavior modulations from vitality tank levels.
 * Port of core/agent/VitalityModulation.java.
 */
data class VitalityModulation(
    val maxResponseTokens: Int,
    val temperature: Double,
    val debounceDelayMs: Long,
    val conversationHistorySize: Int,
) {
    companion object {
        fun compute(vitality: VitalityState, profile: AgentProfile): VitalityModulation {
            // maxResponseTokens: scales with energy (low energy -> shorter responses)
            val energyFactor = 0.3 + (0.7 * vitality.energy)
            val maxTokens = max(64, (profile.maxResponseTokens * energyFactor).toInt())

            // temperature: inversely scales with confidence
            var tempFactor = 1.0 + (0.3 * (1.0 - vitality.confidence))
            if (vitality.errorPressure > 0.5) tempFactor *= 0.8
            val temp = min(1.5, profile.temperature * tempFactor)

            // debounce: shorter with high momentum, longer when tired
            val debounceFactor = 1.0 - (0.5 * vitality.momentum) +
                (0.3 * (1.0 - vitality.energy))
            val debounceMs = (500 * max(0.3, debounceFactor)).toLong()

            // conversation history: more when focused, less when distracted
            val focusFactor = 0.4 + (0.6 * vitality.focus)
            val historySize = max(5, (20 * focusFactor).toInt())

            return VitalityModulation(maxTokens, temp, debounceMs, historySize)
        }
    }
}
