package org.wyrdsekai.core.familiar;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Typed accessor for familiar/bunshin/promotion config keys.
 *
 * <p> — tank ceilings, concurrent bunshin limits
 * promotion policies. Read once at spawn, passed into validators and
 * schedulers; Study-level overrides plug in here when that surface lands.</p>
 */
public record FamiliarConfig(
    Tanks defaults,
    Tanks maxCeiling,
    int bunshinMaxConcurrent,
    int bunshinElasticCeiling,
    int bunshinAbsoluteCeiling,
    boolean promotionAutoEligibility,
    boolean promotionRequireSteward,
    int promotionMaxResidentsPerUser,
    boolean promotionRejectOnResourcePressure,    // §17.3
    int promotionZoneResourceCeiling,             // §17.3 — residents-in-zone ceiling
    double deviationPatchMin,                     // §21 — agent may set patch ceiling ≥ this
    double deviationPatchMax,                     // §21 — agent may set patch ceiling ≤ this
    double deviationMinorMin,                     // §21 — agent may set minor ceiling ≥ this
    double deviationMinorMax,                     // §21 — agent may set minor ceiling ≤ this
    boolean dynamicValidationEnabled              // §13 rules 15-17 fire on shape_form
) {

    private static final Logger log = LoggerFactory.getLogger(FamiliarConfig.class);

    /** Read from the default {@link ConfigFactory#load()} config tree. */
    public static FamiliarConfig load() {
        return load(ConfigFactory.load());
    }

    /** Read from a provided Typesafe config, with sensible fallbacks on missing keys. */
    public static FamiliarConfig load(Config config) {
        return new FamiliarConfig(
            readTanks(config, "wyrdsekai.familiar.default", Tanks.defaults()),
            readTanks(config, "wyrdsekai.familiar.max", Tanks.maxCeiling()),
            intOrDefault(config, "wyrdsekai.bunshin.max-concurrent",
                BunshinScheduler.DEFAULT_MAX_CONCURRENT),
            intOrDefault(config, "wyrdsekai.bunshin.elastic-concurrent",
                BunshinScheduler.DEFAULT_ELASTIC_CEILING),
            intOrDefault(config, "wyrdsekai.bunshin.absolute-ceiling",
                BunshinScheduler.DEFAULT_ABSOLUTE_CEILING),
            boolOrDefault(config, "wyrdsekai.promotion.auto-eligibility", true),
            boolOrDefault(config, "wyrdsekai.promotion.require-steward", false),
            intOrDefault(config, "wyrdsekai.promotion.max-residents-per-user", 10),
            boolOrDefault(config, "wyrdsekai.promotion.reject-on-resource-pressure", true),
            intOrDefault(config, "wyrdsekai.promotion.zone-resource-ceiling", 50),
            doubleOrDefault(config, "wyrdsekai.deviation.patch-min", 0.05),
            doubleOrDefault(config, "wyrdsekai.deviation.patch-max", 0.35),
            doubleOrDefault(config, "wyrdsekai.deviation.minor-min", 0.35),
            doubleOrDefault(config, "wyrdsekai.deviation.minor-max", 0.70),
            boolOrDefault(config, "wyrdsekai.familiar.dynamic-validation.enabled", true));
    }

    /** Hard-coded defaults — never reads from disk. Useful for tests. */
    public static FamiliarConfig defaultsOnly() {
        return new FamiliarConfig(
            Tanks.defaults(), Tanks.maxCeiling(),
            BunshinScheduler.DEFAULT_MAX_CONCURRENT,
            BunshinScheduler.DEFAULT_ELASTIC_CEILING,
            BunshinScheduler.DEFAULT_ABSOLUTE_CEILING,
            true, false, 10,
            true, 50,
            0.05, 0.35, 0.35, 0.70,
            true);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static Tanks readTanks(Config c, String prefix, Tanks fallback) {
        try {
            return new Tanks(
                intOrDefault(c, prefix + ".tokens", fallback.tokens()),
                intOrDefault(c, prefix + ".steps", fallback.steps()),
                intOrDefault(c, prefix + ".wall-clock", fallback.wallClock()),
                intOrDefault(c, prefix + ".nest-depth", fallback.nestDepth()),
                intOrDefault(c, prefix + ".cu", fallback.cu()));
        } catch (Exception e) {
            log.debug("FamiliarConfig: {} missing, using fallback: {}", prefix, e.getMessage());
            return fallback;
        }
    }

    private static int intOrDefault(Config c, String path, int def) {
        try {
            return c.hasPath(path) ? c.getInt(path) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean boolOrDefault(Config c, String path, boolean def) {
        try {
            return c.hasPath(path) ? c.getBoolean(path) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static double doubleOrDefault(Config c, String path, double def) {
        try {
            return c.hasPath(path) ? c.getDouble(path) : def;
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * Clamp a per-agent threshold pair (patch, minor) into the user-configured
     * bounds. §21 "within user-configured bounds."
     */
    public FormEvolutionClassifier.Thresholds clampThresholds(double patch, double minor) {
        var p = Math.max(deviationPatchMin, Math.min(deviationPatchMax, patch));
        var m = Math.max(deviationMinorMin, Math.min(deviationMinorMax, minor));
        if (m < p) m = p;
        return new FormEvolutionClassifier.Thresholds(p, m);
    }
}
