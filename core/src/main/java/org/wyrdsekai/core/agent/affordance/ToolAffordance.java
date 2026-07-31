package org.wyrdsekai.core.agent.affordance;

import java.util.Map;

/**
 * what a tool affords, as DATA.
 *
 * <p>{@code servedNeeds} maps drive/tank name → weight: how strongly using this tool
 * relieves that need. A tool's relevance at a moment is {@code baseSalience + Σ
 * servedNeeds[n]·needPressure[n]} — so a tool serving currently-high drives rises.
 * This is the need-relative affordance (Gibson): the body's needs decide what's
 * salient in the action space.</p>
 *
 * <p>Values come from the {@link AffordanceSeed} (principled cold-start defaults,
 * resolved from the tool's {@code ActionPolicy.domain}) and are overridden per-tool by
 * the agent-owned {@link ToolAffordanceStore}. Nothing here is permission — relevance
 * only (SPEC §4).</p>
 */
public record ToolAffordance(
        String toolName,
        Map<String, Double> servedNeeds,
        String whenToUse,
        double baseSalience) {

    public ToolAffordance {
        servedNeeds = servedNeeds == null ? Map.of() : Map.copyOf(servedNeeds);
    }

    /** Relevance of this tool given the current need pressures. */
    public double score(Map<String, Double> needPressures) {
        double s = baseSalience;
        if (needPressures != null) {
            for (var e : servedNeeds.entrySet()) {
                Double p = needPressures.get(e.getKey());
                if (p != null) s += e.getValue() * p;
            }
        }
        return s;
    }
}
