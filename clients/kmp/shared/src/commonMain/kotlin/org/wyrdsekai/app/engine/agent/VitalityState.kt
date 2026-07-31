package org.wyrdsekai.app.engine.agent

import kotlin.math.max
import kotlin.math.min
import org.wyrdsekai.app.engine.soul.ClientGenome

/**
 * The 8 vitality tanks for an agent. Each tank ranges from 0.0 to 1.0.
 * Port of core/agent/VitalityState.java.
 *
 * When a genome is applied, recovery/decay rates are modulated by sensitivity
 * multipliers, coupling between tanks shifts proportionally, and decay pulls
 * tanks toward genome-defined baselines.
 */
data class VitalityState(
    val contextBudget: Double,
    val confidence: Double,
    val energy: Double,
    val alignment: Double,
    val errorPressure: Double,
    val momentum: Double,
    val rapport: Double,
    val focus: Double,
    val genome: ClientGenome? = null,
) {
    companion object {
        fun initial() = VitalityState(0.5, 0.5, 1.0, 0.3, 0.0, 0.0, 0.3, 0.5)

        /** Tank names for map-based access. */
        val TANK_NAMES = listOf(
            "contextBudget", "confidence", "energy", "alignment",
            "errorPressure", "momentum", "rapport", "focus",
        )

        /** Default recovery/decay rates per tick (positive = recovery, negative = decay). */
        private val DEFAULT_RATES = mapOf(
            "contextBudget" to 0.003,
            "confidence" to 0.0,
            "energy" to 0.005,
            "alignment" to -0.001,
            "errorPressure" to -0.005,
            "momentum" to -0.003,
            "rapport" to -0.001,
            "focus" to -0.002,
        )
    }

    fun clamped() = copy(
        contextBudget = clamp(contextBudget),
        confidence = clamp(confidence),
        energy = clamp(energy),
        alignment = clamp(alignment),
        errorPressure = clamp(errorPressure),
        momentum = clamp(momentum),
        rapport = clamp(rapport),
        focus = clamp(focus),
    )

    /** Apply natural recovery/decay per tick (1 second), modulated by genome if present. */
    fun tick(): VitalityState {
        val g = genome
        if (g == null) {
            // No genome: use original fixed rates
            return copy(
                contextBudget = contextBudget + 0.003,
                confidence = confidence,
                energy = energy + 0.005,
                alignment = alignment - 0.001,
                errorPressure = errorPressure - 0.005,
                momentum = momentum - 0.003,
                rapport = rapport - 0.001,
                focus = focus - 0.002,
            ).clamped()
        }

        // Genome-modulated tick:
        // 1. Apply sensitivity-scaled recovery/decay rates
        // 2. Apply coupling (tank-to-tank influence)
        // 3. Apply baseline decay (pull toward genome baselines)
        val tanks = toTankMap()
        val newTanks = tanks.toMutableMap()

        for (tankName in TANK_NAMES) {
            val current = tanks[tankName] ?: continue
            val defaultRate = DEFAULT_RATES[tankName] ?: 0.0
            val sensitivity = g.sensitivity[tankName] ?: 1.0
            val baseline = g.baselines[tankName] ?: current
            val decayRate = g.decayRates[tankName] ?: 0.01

            // Step 1: Sensitivity-scaled recovery/decay
            var delta = defaultRate * sensitivity

            // Step 2: Coupling — other tanks influence this one
            for (otherTank in TANK_NAMES) {
                if (otherTank == tankName) continue
                val couplingKey = "${otherTank}->${tankName}"
                val couplingStrength = g.coupling[couplingKey] ?: continue
                val otherValue = tanks[otherTank] ?: continue
                // Coupling: proportional to how far other tank is from 0.5 (neutral)
                delta += couplingStrength * (otherValue - 0.5) * 0.01
            }

            // Step 3: Baseline decay — gentle pull toward genome baseline
            delta += decayRate * (baseline - current)

            newTanks[tankName] = current + delta
        }

        return copy(
            contextBudget = newTanks["contextBudget"] ?: contextBudget,
            confidence = newTanks["confidence"] ?: confidence,
            energy = newTanks["energy"] ?: energy,
            alignment = newTanks["alignment"] ?: alignment,
            errorPressure = newTanks["errorPressure"] ?: errorPressure,
            momentum = newTanks["momentum"] ?: momentum,
            rapport = newTanks["rapport"] ?: rapport,
            focus = newTanks["focus"] ?: focus,
        ).clamped()
    }

    /** Convert to a map of tank name -> value. */
    fun toTankMap(): Map<String, Double> = mapOf(
        "contextBudget" to contextBudget,
        "confidence" to confidence,
        "energy" to energy,
        "alignment" to alignment,
        "errorPressure" to errorPressure,
        "momentum" to momentum,
        "rapport" to rapport,
        "focus" to focus,
    )

    /** Apply a genome, returning a new state with genome attached. */
    fun withGenome(genome: ClientGenome?) = copy(genome = genome)

    fun withContextBudget(v: Double) = copy(contextBudget = clamp(v))
    fun withConfidence(v: Double) = copy(confidence = clamp(v))
    fun withEnergy(v: Double) = copy(energy = clamp(v))
    fun withAlignment(v: Double) = copy(alignment = clamp(v))
    fun withErrorPressure(v: Double) = copy(errorPressure = clamp(v))
    fun withMomentum(v: Double) = copy(momentum = clamp(v))
    fun withRapport(v: Double) = copy(rapport = clamp(v))
    fun withFocus(v: Double) = copy(focus = clamp(v))

    /** Human-readable description for the system prompt. */
    fun describe(): String {
        val sb = StringBuilder("Current state: ")

        if (energy < 0.2) sb.append("exhausted, ")
        else if (energy < 0.4) sb.append("tired, ")
        else if (energy > 0.8) sb.append("energetic, ")

        if (confidence < 0.3) sb.append("uncertain, ")
        else if (confidence > 0.7) sb.append("confident, ")

        if (errorPressure > 0.6) sb.append("high error pressure, ")
        else if (errorPressure > 0.3) sb.append("moderate error pressure, ")

        if (focus > 0.7) sb.append("highly focused, ")
        else if (focus < 0.3) sb.append("distracted, ")

        if (rapport > 0.7) sb.append("strong rapport, ")
        else if (rapport < 0.3) sb.append("low rapport, ")

        if (momentum > 0.7) sb.append("high momentum, ")
        else if (momentum < 0.2) sb.append("low momentum, ")

        if (alignment > 0.7) sb.append("well-aligned.")
        else if (alignment < 0.3) sb.append("misaligned.")
        else sb.append("aware.")

        var result = sb.toString()
        result = result.replace(", .", ".").replace(Regex(", $"), ".")
        return result
    }

    /** In-world appearance description based on vitality state. */
    fun appearance(): String = when {
        energy > 0.7 && focus > 0.6 -> "radiant and focused"
        energy > 0.5 && rapport > 0.6 -> "warm and attentive"
        energy < 0.3 -> "dim and fading"
        errorPressure > 0.6 -> "unsteady, flickering"
        focus < 0.3 -> "unfocused, drifting"
        else -> "watchful and present"
    }
}

private fun clamp(v: Double): Double = max(0.0, min(1.0, v))
