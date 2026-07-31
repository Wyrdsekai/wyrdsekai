package org.wyrdsekai.core.agent;

/**
 * An action the agent wants to take proactively (not in response to human speech).
 * Three tiers of intrusion, each costing different amounts of proactivity budget.
 *
 * Ambient: low-cost idle behaviors (emotes, room presence)
 * Observation: medium-cost sharing of noticed things (Oracle narration, patterns)
 * Initiative: high-cost autonomous actions (navigate, search, act on commitments)
 */
public sealed interface ProactiveAction {

    /** Budget cost for this action tier. */
    double budgetCost();

    /** Which drive triggered this action. */
    String driveName();

    /**
     * Low-intrusion: emotes, room presence, idle behaviors.
     * Examples: *adjusts crystal thoughtfully*, *glances at bookshelf*
     */
    record Ambient(String emoteText, String driveName) implements ProactiveAction {
        @Override public double budgetCost() { return 0.1; }
    }

    /**
     * Medium-intrusion: share what the agent noticed.
     * Examples: "The Oracle noticed a pattern...", "I found something interesting..."
     */
    record Observation(String speechText, String driveName, String category) implements ProactiveAction {
        @Override public double budgetCost() { return 0.3; }
    }

    /**
     * High-intrusion: autonomous navigation, search, commitment fulfillment.
     * Examples: navigate to Library and search, act on a commitment, delegate to another agent
     */
    record Initiative(String actionJson, String driveName, String description) implements ProactiveAction {
        @Override public double budgetCost() { return 0.7; }
    }
}
