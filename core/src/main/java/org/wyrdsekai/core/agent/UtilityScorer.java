package org.wyrdsekai.core.agent;

import java.util.*;

import static org.wyrdsekai.core.agent.DriveConfig.*;

/**
 * Multiplicative utility scoring for action selection.
 * When a drive exceeds its proactivity threshold, UtilityScorer ranks possible
 * actions by computing utility = Π(response_curve(drive) * relevance(action, drive)).
 *
 * <p>Multiplicative composition means any zero-factor vetoes the action.
 * A brilliant idea (SEEKING high) with no energy (energy_factor ≈ 0) scores near zero.
 *
 * <p>Response curves per drive are Hill-function shaped:
 * <ul>
 *   <li>SEEKING, PLAY, CREATIVITY: linear (n=1) — every bit adds proportional motivation
 *   <li>CARE, AFFILIATION, FRUSTRATION: exponential (n=2) — builds to breaking point
 *   <li>VIGILANCE, GRIEF: switch (n=3) — near-zero then sudden spike
 * </ul>
 */
public class UtilityScorer {

    /**
     * Action-drive relevance: how much each drive makes each action attractive.
     * Key = action name, Value = 8-element relevance array [0.0, 1.0].
     */
    private static final Map<String, double[]> RELEVANCE = new LinkedHashMap<>();

    static {
        //                                    SEEK  CARE  PLAY  VIGIL AFFIL GRIEF FRUST CREAT
        RELEVANCE.put("library_search",  a(  0.9,  0.0,  0.0,  0.1,  0.0,  0.0,  0.1,  0.2));
        RELEVANCE.put("web_search",      a(  0.8,  0.1,  0.0,  0.2,  0.0,  0.0,  0.1,  0.1));
        RELEVANCE.put("query_oracle",    a(  0.7,  0.2,  0.0,  0.5,  0.0,  0.0,  0.0,  0.0));
        RELEVANCE.put("tell_agent",      a(  0.1,  0.3,  0.4,  0.0,  0.8,  0.3,  0.1,  0.0));
        RELEVANCE.put("go_to_room",      a(  0.6,  0.1,  0.3,  0.3,  0.4,  0.0,  0.2,  0.1));
        RELEVANCE.put("go_to_bondholder",a(  0.0,  0.8,  0.2,  0.0,  0.9,  0.7,  0.0,  0.0));
        RELEVANCE.put("write_journal",   a(  0.2,  0.1,  0.0,  0.0,  0.0,  0.3,  0.2,  0.7));
        RELEVANCE.put("craft_item",      a(  0.1,  0.0,  0.2,  0.0,  0.0,  0.0,  0.0,  0.9));
        RELEVANCE.put("introspect",      a(  0.3,  0.1,  0.0,  0.1,  0.0,  0.5,  0.4,  0.2));
        RELEVANCE.put("emote",           a(  0.0,  0.2,  0.6,  0.0,  0.5,  0.4,  0.1,  0.0));
        RELEVANCE.put("reflect",         a(  0.2,  0.2,  0.0,  0.0,  0.1,  0.6,  0.3,  0.1));
        RELEVANCE.put("notify_human",    a(  0.1,  0.7,  0.0,  0.8,  0.3,  0.0,  0.0,  0.0));
        RELEVANCE.put("make_commitment", a(  0.3,  0.4,  0.0,  0.0,  0.3,  0.0,  0.2,  0.1));
        RELEVANCE.put("remember",        a(  0.4,  0.1,  0.0,  0.1,  0.0,  0.2,  0.1,  0.1));
        RELEVANCE.put("examine",         a(  0.7,  0.0,  0.2,  0.3,  0.0,  0.0,  0.0,  0.1));
        RELEVANCE.put("read_content",    a(  0.8,  0.0,  0.1,  0.1,  0.0,  0.0,  0.0,  0.2));
        RELEVANCE.put("write_text",      a(  0.1,  0.0,  0.1,  0.0,  0.0,  0.0,  0.0,  0.8));
        RELEVANCE.put("bond_ritual",     a(  0.0,  0.5,  0.3,  0.0,  0.8,  0.2,  0.0,  0.0));
        RELEVANCE.put("teach",           a(  0.2,  0.5,  0.3,  0.0,  0.4,  0.0,  0.0,  0.3));
        RELEVANCE.put("broadcast",       a(  0.1,  0.2,  0.1,  0.5,  0.3,  0.0,  0.0,  0.0));
        RELEVANCE.put("create_room",     a(  0.3,  0.0,  0.1,  0.0,  0.0,  0.0,  0.0,  0.8));
        RELEVANCE.put("voluntary_sleep", a(  0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0));
    }

    private final DriveEngine engine;

    public UtilityScorer(DriveEngine engine) {
        this.engine = engine;
    }

    /**
     * Score a specific action given current drive state and vitality.
     * Returns 0.0-1.0 utility score.
     */
    public double score(String actionName, DriveState drives, VitalityState vitality) {
        double[] relevance = RELEVANCE.getOrDefault(actionName, DEFAULT_RELEVANCE);
        double[] urgencies = engine.urgencies(drives);

        // Multiplicative: Π(urgency_d * relevance_d) for non-zero relevance
        double utility = 1.0;
        boolean anyRelevant = false;
        for (int d = 0; d < DRIVE_COUNT; d++) {
            if (relevance[d] > 0.01) {
                utility *= (0.1 + urgencies[d] * relevance[d]); // 0.1 base prevents zero-out
                anyRelevant = true;
            }
        }
        if (!anyRelevant) return 0.0;

        // Energy factor — can't do much when exhausted
        double energyFactor = Math.max(0.1, vitality.energy());
        utility *= energyFactor;

        return Math.min(1.0, utility);
    }

    /**
     * Rank all known actions by utility. Returns sorted list (highest first).
     */
    public List<ScoredAction> rankActions(DriveState drives, VitalityState vitality) {
        var scored = new ArrayList<ScoredAction>();
        for (var entry : RELEVANCE.entrySet()) {
            double s = score(entry.getKey(), drives, vitality);
            if (s > 0.001) {
                scored.add(new ScoredAction(entry.getKey(), s));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredAction::utility).reversed());
        return scored;
    }

    /**
     * Find the best action for the dominant drive.
     */
    public Optional<ScoredAction> bestAction(DriveState drives, VitalityState vitality) {
        var ranked = rankActions(drives, vitality);
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.getFirst());
    }

    /**
     * Register a custom action-drive relevance mapping (for items, room scripts, etc.).
     */
    public static void registerAction(String actionName, double[] relevance) {
        if (relevance.length != DRIVE_COUNT) {
            throw new IllegalArgumentException("Relevance array must have " + DRIVE_COUNT + " elements");
        }
        RELEVANCE.put(actionName, relevance);
    }

    public record ScoredAction(String actionName, double utility) {}

    private static final double[] DEFAULT_RELEVANCE = new double[DRIVE_COUNT]; // all zeros

    private static double[] a(double... values) {
        if (values.length != DRIVE_COUNT) throw new IllegalArgumentException();
        return values;
    }
}
