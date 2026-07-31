package org.wyrdsekai.common.home;

/**
 * How quickly a revocation on a {@link Grant} must take effect at remote
 * callers. Chosen by the issuer at grant-time based on the cost of a
 * stale-honor.
 */
public enum RevocationMode {
    /** Caller must check the issuing Home on every use; no caching. Stale window = 0. */
    strict,
    /** Caller may cache up to TTL (30s default); invalidated via pub/sub. Default. */
    standard,
    /** Caller may cache indefinitely; invalidation via pub/sub only. */
    eventual;

    public static RevocationMode parse(String name) {
        if (name == null) return standard;
        try {
            return RevocationMode.valueOf(name.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return standard;
        }
    }

    /** Default cache TTL in seconds for {@code standard} mode. */
    public static final long STANDARD_TTL_SECONDS = 30;
}
