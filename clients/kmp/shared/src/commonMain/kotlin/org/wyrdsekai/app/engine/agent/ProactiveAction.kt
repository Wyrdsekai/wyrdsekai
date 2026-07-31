package org.wyrdsekai.app.engine.agent

import kotlinx.serialization.Serializable

/**
 * An action the agent wants to take proactively (not in response to human speech).
 * Three tiers of intrusion, each costing different amounts of proactivity budget.
 *
 * Port of core/agent/ProactiveAction.java for the KMP phone client.
 * Uses sealed class pattern instead of sealed interface + records.
 */
@Serializable
sealed class ProactiveAction {
    /** Budget cost for this action tier. */
    abstract val budgetCost: Double

    /** Which drive triggered this action. */
    abstract val driveName: String

    /**
     * Low-intrusion: emotes, room presence, idle behaviors.
     * Examples: *adjusts crystal thoughtfully*, *glances at bookshelf*
     */
    @Serializable
    data class Ambient(
        val emoteText: String,
        override val driveName: String,
    ) : ProactiveAction() {
        override val budgetCost: Double get() = 0.1
    }

    /**
     * Medium-intrusion: share what the agent noticed.
     * Examples: "The Oracle noticed a pattern...", "I found something interesting..."
     */
    @Serializable
    data class Observation(
        val speechText: String,
        override val driveName: String,
        val category: String,
    ) : ProactiveAction() {
        override val budgetCost: Double get() = 0.3
    }

    /**
     * High-intrusion: autonomous navigation, search, commitment fulfillment.
     * Examples: navigate to Library and search, act on a commitment, delegate to another agent
     */
    @Serializable
    data class Initiative(
        val actionJson: String,
        override val driveName: String,
        val description: String,
    ) : ProactiveAction() {
        override val budgetCost: Double get() = 0.7
    }
}
