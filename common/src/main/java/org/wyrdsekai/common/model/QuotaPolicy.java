package org.wyrdsekai.common.model;

import java.util.Map;

/**
 * Per-bilateral quota defining what each zone allows the other to consume.
 *
 * <p>Quotas are enforced locally — the providing zone rejects requests that exceed
 * configured limits. Enforcement is blunt (request count / daily byte budget) but
 * effective for v1: it prevents abuse and makes "how much can this partner use"
 * explicit and negotiable per bilateral agreement.</p>
 *
 * <p>A quota of 0 for any field means "unlimited" (use with {@code family} trust).</p>
 *
 * @param inferenceTokensPerDay max inference tokens/day (0 = unlimited)
 * @param storageBytesTotal     max storage bytes total (0 = unlimited)
 * @param bandwidthBytesPerDay  max bandwidth/day (0 = unlimited)
 * @param allowTransit          can entities travel here
 * @param allowTell             can send cross-zone tells
 * @param allowInventory        can items cross
 * @param maxConcurrentSessions max simultaneous visitors (0 = unlimited)
 * @param custom                extensible per-service quotas
 */
public record QuotaPolicy(
    long inferenceTokensPerDay,
    long storageBytesTotal,
    long bandwidthBytesPerDay,
    boolean allowTransit,
    boolean allowTell,
    boolean allowInventory,
    int maxConcurrentSessions,
    Map<String, Long> custom
) {

    public QuotaPolicy {
        if (custom == null) custom = Map.of();
    }

    /** Unlimited quota for family-trust bilateral agreements. */
    public static QuotaPolicy family() {
        return new QuotaPolicy(0, 0, 0, true, true, true, 0, Map.of());
    }

    /** Moderate quota for partner-trust bilateral agreements. */
    public static QuotaPolicy partner() {
        return new QuotaPolicy(
            500_000L,                // 500K tokens/day
            50L * 1024 * 1024 * 1024, // 50 GB total
            10L * 1024 * 1024 * 1024, // 10 GB/day
            true, true, true, 10, Map.of()
        );
    }

    /** Tight quota for tourist-trust (stranger) bilateral agreements. */
    public static QuotaPolicy tourist() {
        return new QuotaPolicy(
            50_000L,                  // 50K tokens/day
            1L * 1024 * 1024 * 1024,  // 1 GB total
            1L * 1024 * 1024 * 1024,  // 1 GB/day
            true, true, false, 3, Map.of()
        );
    }

    /** Most restrictive — deny everything. Used as suspended state. */
    public static QuotaPolicy denied() {
        return new QuotaPolicy(0, 0, 0, false, false, false, 0, Map.of("denied", 1L));
    }

    /** Get preset by trust level name. */
    public static QuotaPolicy forTrustLevel(String trustLevel) {
        return switch (trustLevel != null ? trustLevel.toLowerCase() : "") {
            case "family" -> family();
            case "partner" -> partner();
            case "tourist" -> tourist();
            default -> tourist();
        };
    }

    /** Check if a quota value is unlimited (0 means unlimited). */
    public static boolean isUnlimited(long quota) {
        return quota == 0;
    }

    /**
     * Check if an inference request fits within quota.
     * @param usedToday tokens already consumed today
     * @param requestTokens tokens for this request
     */
    public boolean allowInference(long usedToday, long requestTokens) {
        if (inferenceTokensPerDay == 0) return true;
        return usedToday + requestTokens <= inferenceTokensPerDay;
    }

    /** Check if a storage allocation fits within quota. */
    public boolean allowStorage(long currentBytes, long requestBytes) {
        if (storageBytesTotal == 0) return true;
        return currentBytes + requestBytes <= storageBytesTotal;
    }

    /** Check if a bandwidth transfer fits within quota. */
    public boolean allowBandwidth(long usedToday, long requestBytes) {
        if (bandwidthBytesPerDay == 0) return true;
        return usedToday + requestBytes <= bandwidthBytesPerDay;
    }

    /** Check if concurrent session limit allows a new visitor. */
    public boolean allowNewSession(int currentSessions) {
        if (maxConcurrentSessions == 0) return true;
        return currentSessions < maxConcurrentSessions;
    }
}
