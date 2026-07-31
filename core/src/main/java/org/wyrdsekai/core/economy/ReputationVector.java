package org.wyrdsekai.core.economy;

import org.wyrdsekai.common.i18n.I18n;

/**
 * Multi-dimensional reputation vector for an entity (§17).
 * Each dimension is scored 0.0 to 1.0.
 *
 * @param entityId      entity identifier
 * @param uptime        fraction of time the entity has been active/responsive
 * @param quality       quality of contributions (trade satisfaction, task completion)
 * @param contribution  volume of positive activity (trades, grants, tasks)
 * @param consistency   regularity and predictability of behavior
 * @param composite     weighted aggregate score
 */
public record ReputationVector(
    String entityId,
    double uptime,
    double quality,
    double contribution,
    double consistency,
    double composite
) {
    /** Weights for composite calculation. */
    private static final double W_UPTIME = 0.2;
    private static final double W_QUALITY = 0.3;
    private static final double W_CONTRIBUTION = 0.3;
    private static final double W_CONSISTENCY = 0.2;

    /** Create a reputation vector with auto-calculated composite. */
    public static ReputationVector of(String entityId, double uptime,
                                       double quality, double contribution,
                                       double consistency) {
        double composite = clamp(W_UPTIME * uptime + W_QUALITY * quality
            + W_CONTRIBUTION * contribution + W_CONSISTENCY * consistency);
        return new ReputationVector(entityId, clamp(uptime), clamp(quality),
            clamp(contribution), clamp(consistency), composite);
    }

    /** Blank reputation for a new entity. */
    public static ReputationVector initial(String entityId) {
        return new ReputationVector(entityId, 0.5, 0.5, 0.0, 0.5, 0.25);
    }

    /** Human-readable summary. */
    public String describe() {
        return String.format(
            "%s — %s: %.2f (%s: %.2f, %s: %.2f, %s: %.2f, %s: %.2f)",
            entityId,
            I18n.get("economy.reputation.composite"), composite,
            I18n.get("economy.reputation.uptime"), uptime,
            I18n.get("economy.reputation.quality"), quality,
            I18n.get("economy.reputation.contribution"), contribution,
            I18n.get("economy.reputation.consistency"), consistency);
    }

    /** Reputation tier label. */
    public String tier() {
        if (composite >= 0.8) return I18n.get("economy.reputation.tier.exemplary");
        if (composite >= 0.6) return I18n.get("economy.reputation.tier.trusted");
        if (composite >= 0.4) return I18n.get("economy.reputation.tier.established");
        if (composite >= 0.2) return I18n.get("economy.reputation.tier.newcomer");
        return I18n.get("economy.reputation.tier.unknown");
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
