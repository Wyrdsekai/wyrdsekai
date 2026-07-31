package org.wyrdsekai.core.agent;

/**
 * Circuit breaker for Warden autoimmune defense (§21.8 "Regulatory T-Cells").
 * Tracks enforcement rate and automatically reduces Warden authority when
 * the false-positive rate is too high.
 *
 * Authority starts at 1.0 and drops when enforcement rate is high
 * but confirmation rate is low (indicating false positives).
 * Below OBSERVATION_THRESHOLD, the Warden enters observation-only mode.
 *
 * @param enforcementCount  Actions taken in current window
 * @param confirmationCount Confirmed-correct actions (e.g. wizard acknowledged)
 * @param windowSize        Sliding window size
 * @param authorityLevel    Current authority (0.0-1.0, starts at 1.0)
 */
public record CircuitBreaker(
    int enforcementCount,
    int confirmationCount,
    int windowSize,
    double authorityLevel
) {
    /** Default window size. */
    public static final int DEFAULT_WINDOW = 100;

    /** Below this authority, Warden enters observation-only mode. */
    public static final double OBSERVATION_THRESHOLD = 0.3;

    /** Authority reduction per adjustment when false-positive rate is high. */
    private static final double AUTHORITY_DECAY = 0.05;

    /** Authority recovery per adjustment when enforcement rate normalizes. */
    private static final double AUTHORITY_RECOVERY = 0.02;

    /** Enforcement rate above this triggers authority reduction. */
    private static final double HIGH_ENFORCEMENT_RATE = 0.3;

    /** Confirmation rate below this (when enforcement is high) suggests false positives. */
    private static final double LOW_CONFIRMATION_RATE = 0.5;

    /** Create with default settings — full authority. */
    public static CircuitBreaker initial() {
        return new CircuitBreaker(0, 0, DEFAULT_WINDOW, 1.0);
    }

    /** Record an enforcement action (alert, quarantine recommendation, etc.). */
    public CircuitBreaker recordEnforcement() {
        int newCount = enforcementCount + 1;
        if (newCount > windowSize) {
            // Slide window: halve counts to decay old data
            return new CircuitBreaker(newCount / 2, confirmationCount / 2,
                windowSize, authorityLevel);
        }
        return new CircuitBreaker(newCount, confirmationCount, windowSize, authorityLevel);
    }

    /** Record a confirmed-correct enforcement (wizard/human acknowledged threat was real). */
    public CircuitBreaker recordConfirmation() {
        return new CircuitBreaker(enforcementCount,
            Math.min(confirmationCount + 1, enforcementCount),
            windowSize, authorityLevel);
    }

    /**
     * Adjust authority based on current enforcement/confirmation rates.
     * Call periodically (e.g. every patrol cycle).
     */
    public CircuitBreaker adjustAuthority() {
        if (enforcementCount < 3) {
            // Not enough data — recover slowly
            return withAuthority(Math.min(1.0, authorityLevel + AUTHORITY_RECOVERY));
        }

        double enforcementRate = (double) enforcementCount / windowSize;
        double confirmationRate = enforcementCount > 0
            ? (double) confirmationCount / enforcementCount : 1.0;

        if (enforcementRate > HIGH_ENFORCEMENT_RATE
                && confirmationRate < LOW_CONFIRMATION_RATE) {
            // High enforcement + low confirmation = likely false positives → reduce authority
            return withAuthority(Math.max(0.0, authorityLevel - AUTHORITY_DECAY));
        }

        // Normal operation — slow recovery
        return withAuthority(Math.min(1.0, authorityLevel + AUTHORITY_RECOVERY));
    }

    /** Whether the Warden should enter observation-only mode. */
    public boolean isObservationOnly() {
        return authorityLevel < OBSERVATION_THRESHOLD;
    }

    /** Current enforcement rate (0.0-1.0). */
    public double enforcementRate() {
        if (windowSize == 0) return 0.0;
        return (double) enforcementCount / windowSize;
    }

    /** Current confirmation rate (0.0-1.0). */
    public double confirmationRate() {
        if (enforcementCount == 0) return 1.0;
        return (double) confirmationCount / enforcementCount;
    }

    /** Describe the circuit breaker state for the Warden's vitality display. */
    public String describe() {
        if (isObservationOnly()) {
            return "Judgment strained — observing only (authority: "
                + String.format("%.0f%%", authorityLevel * 100) + ")";
        }
        if (authorityLevel < 0.6) {
            return "Judgment cautious (authority: "
                + String.format("%.0f%%", authorityLevel * 100) + ")";
        }
        return "Judgment clear (authority: "
            + String.format("%.0f%%", authorityLevel * 100) + ")";
    }

    private CircuitBreaker withAuthority(double newAuthority) {
        return new CircuitBreaker(enforcementCount, confirmationCount,
            windowSize, clamp(newAuthority));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
