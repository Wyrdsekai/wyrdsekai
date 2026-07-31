package org.wyrdsekai.core.economy;

/**
 * Reference rates for CU cost calculation (§68).
 *
 * <p>CU is a physics-informed unit: capacity to do work, grounded in conservation.
 * Reference rates define the base cost of each resource class. Bilateral agreements
 * can apply multipliers (0.0 = free, 1.0 = reference, up to 2.0 = premium).</p>
 *
 * <p>For v1, these rates are used to compute informational
 * CU values for metering. Enforcement activates in v2 when CU becomes a settlement unit.</p>
 *
 * <p>Service class keys (used by MeteringService and BilateralAgreement):</p>
 * <ul>
 *   <li>{@code inference.small} — 1K tokens, <=7B model</li>
 *   <li>{@code inference.large} — 1K tokens, >7B model</li>
 *   <li>{@code compute.cpu} — 1 minute CPU</li>
 *   <li>{@code compute.gpu} — 1 minute GPU</li>
 *   <li>{@code storage} — 1 GB-hour storage</li>
 *   <li>{@code bandwidth} — 1 GB bandwidth transfer</li>
 *   <li>{@code mcp.keyed} — configured per-service, paid APIs</li>
 *   <li>{@code library} — 0 by default (local), configurable for paid sources</li>
 *   <li>{@code web_search} — 0 for Searxng, configurable for paid APIs</li>
 * </ul>
 */
public final class ReferenceRates {

    private ReferenceRates() {}

    // Infrastructure tier — per §68
    public static final double CPU_PER_MINUTE = 1.0;
    public static final double GPU_PER_MINUTE = 8.0;
    public static final double STORAGE_PER_GB_HOUR = 0.5;
    public static final double BANDWIDTH_PER_GB = 2.0;
    public static final double INFERENCE_SMALL_PER_1K_TOKENS = 1.0;  // <=7B
    public static final double INFERENCE_LARGE_PER_1K_TOKENS = 10.0; // >7B

    // Service class keys
    public static final String SERVICE_INFERENCE_SMALL = "inference.small";
    public static final String SERVICE_INFERENCE_LARGE = "inference.large";
    public static final String SERVICE_CPU = "compute.cpu";
    public static final String SERVICE_GPU = "compute.gpu";
    public static final String SERVICE_STORAGE = "storage";
    public static final String SERVICE_BANDWIDTH = "bandwidth";
    public static final String SERVICE_MCP_KEYED = "mcp.keyed";
    public static final String SERVICE_LIBRARY = "library";
    public static final String SERVICE_WEB_SEARCH = "web_search";

    /**
     * Calculate CU cost for a resource consumption event.
     *
     * @param serviceClass      the service class key (see constants)
     * @param units             consumption units (tokens/1000, GB-hours, GB, minutes)
     * @param bilateralMultiplier multiplier from bilateral agreement (0.0-2.0)
     * @return CU cost (always >= 0)
     */
    public static double calculate(String serviceClass, double units, double bilateralMultiplier) {
        var baseRate = baseRateFor(serviceClass);
        return Math.max(0, baseRate * units * Math.max(0, bilateralMultiplier));
    }

    /**
     * Calculate CU cost at reference rate (bilateral multiplier = 1.0).
     */
    public static double calculate(String serviceClass, double units) {
        return calculate(serviceClass, units, 1.0);
    }

    /** Get the base reference rate for a service class. */
    public static double baseRateFor(String serviceClass) {
        if (serviceClass == null) return 0;
        return switch (serviceClass) {
            case SERVICE_INFERENCE_SMALL -> INFERENCE_SMALL_PER_1K_TOKENS;
            case SERVICE_INFERENCE_LARGE -> INFERENCE_LARGE_PER_1K_TOKENS;
            case SERVICE_CPU -> CPU_PER_MINUTE;
            case SERVICE_GPU -> GPU_PER_MINUTE;
            case SERVICE_STORAGE -> STORAGE_PER_GB_HOUR;
            case SERVICE_BANDWIDTH -> BANDWIDTH_PER_GB;
            default -> 0;  // unknown service class = free (informational only)
        };
    }

    /**
     * Determine the inference service class based on model size.
     * @param modelSizeBillions approximate model parameter count in billions
     */
    public static String inferenceServiceClass(double modelSizeBillions) {
        return modelSizeBillions <= 7.0 ? SERVICE_INFERENCE_SMALL : SERVICE_INFERENCE_LARGE;
    }
}
