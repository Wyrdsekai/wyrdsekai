package org.wyrdsekai.core.agent;

import java.util.Map;

/**
 * Configuration for a single drive in the 8-drive system.
 * Each drive has Hill function parameters, time constants, and cross-modulation coefficients.
 *
 * <p>The Hill function {@code H(x, n, K) = x^n / (K^n + x^n)} shapes how drive pressure
 * translates to urgency. n=1 is gradual (Michaelis-Menten), n=3+ is switch-like.
 *
 * @param hillN          Hill coefficient — response curve shape [1.0, 4.0]
 * @param hillK          Half-saturation — where urgency inflects [0.1, 0.9]
 * @param baseRate       Passive accumulation rate per second
 * @param buildTau       Time constant for building (seconds, informational)
 * @param reliefTau      Time constant for relief decay (seconds)
 * @param reliefFloor    Minimum value after relief — not always zero [0.0, 0.3]
 * @param archetypeScale Archetype multiplier on accumulation [0.5, 2.0]
 * @param crossMod       Modulation FROM each other drive (length 10 — STARTLE/SURPRISE
 *                       slots are present but unused until Phase 4 wires their effects)
 */
public record DriveConfig(
    double hillN,
    double hillK,
    double baseRate,
    double buildTau,
    double reliefTau,
    double reliefFloor,
    double archetypeScale,
    double[] crossMod
) {
    // Drive indices — canonical ordering
    public static final int SEEKING      = 0;
    public static final int CARE         = 1;
    public static final int PLAY         = 2;
    public static final int VIGILANCE    = 3;
    public static final int AFFILIATION  = 4;
    public static final int GRIEF        = 5;
    public static final int FRUSTRATION  = 6;
    public static final int CREATIVITY   = 7;
    // Phase 1A additions — structural only; no behavior wired yet (Phase 4).
    public static final int STARTLE      = 8;
    public static final int SURPRISE     = 9;
    public static final int DRIVE_COUNT  = 10;

    public static final String[] DRIVE_NAMES = {
        "seeking", "care", "play", "vigilance",
        "affiliation", "grief", "frustration", "creativity",
        "startle", "surprise"
    };

    /**
     * The default drive configurations (no archetype scaling).
     * Cross-modulation values encode Panksepp-informed interactions:
     * - PLAY suppresses VIGILANCE (-0.4): can't play when afraid
     * - GRIEF suppresses PLAY (-0.4): mourning precludes joy
     * - GRIEF amplifies CARE (+0.3): loss makes you tender
     * - AFFILIATION amplifies PLAY (+0.3): connection enables playfulness
     * - FRUSTRATION amplifies SEEKING (+0.2): blocked goals drive harder pursuit
     * - SEEKING amplifies CREATIVITY (+0.3): exploration feeds creation
     */
    public static DriveConfig[] defaults() {
        return new DriveConfig[] {
            // SEEKING: gradual build, quick satisfaction, never fully gone
            new DriveConfig(1.5, 0.4, 0.0003, 1080, 120, 0.05, 1.0,
                //           SEEK  CARE  PLAY  VIGIL AFFIL GRIEF FRUST CREAT START SURP
                new double[]{ 0.0,  0.0,  0.2, -0.1,  0.1,  0.0, -0.2,  0.3,  0.0,  0.0}),

            // CARE: moderate curve, slow relief (caring lingers)
            new DriveConfig(2.0, 0.5, 0.0002, 1500, 300, 0.1, 1.0,
                new double[]{ 0.0,  0.0, -0.1,  0.2,  0.3,  0.2,  0.0,  0.0,  0.0,  0.0}),

            // PLAY: linear/gentle, fast relief (play is its own reward)
            new DriveConfig(1.0, 0.3, 0.0004, 720, 60, 0.0, 1.0,
                new double[]{ 0.1,  0.0,  0.0, -0.3,  0.2, -0.2, -0.3,  0.2,  0.0,  0.0}),

            // VIGILANCE: switch-like, slow relaxation
            new DriveConfig(3.0, 0.6, 0.0001, 3600, 600, 0.0, 1.0,
                new double[]{-0.2,  0.1, -0.4,  0.0, -0.1,  0.0,  0.2, -0.2,  0.0,  0.0}),

            // AFFILIATION: similar to seeking but social
            new DriveConfig(1.5, 0.4, 0.0003, 1080, 180, 0.05, 1.0,
                new double[]{ 0.1,  0.2,  0.3, -0.1,  0.0,  0.3, -0.1,  0.0,  0.0,  0.0}),

            // GRIEF: event-only, very slow relief
            //   GRIEF←CARE and GRIEF←AFFILIATION zeroed (2026-06-07). The crossMod ROW is modulation
            //   INTO this drive FROM each j (DriveEngine applies cfg.crossMod()[j] * d[j]). Standing
            //   care/affiliation PRESSURE (wanting to tend / wanting company) was manufacturing grief
            //   every tick: the agent who got lonely and reached (affil ~0.42) had grief pushed to 1.0,
            //   the solitary one (affil ~0.06) stayed grief-free — proven across two live soaks. Grief
            //   is LOSS; it spikes from severance/mourning events, not from how high the appetitive
            //   social drives sit. (SEEKING/PLAY/CREATIVITY still EASE grief; FRUSTRATION→GRIEF kept —
            //   sustained frustration→despair is a real, event-gated dynamic.)
            new DriveConfig(3.0, 0.7, 0.0, 0, 1800, 0.0, 1.0,
                new double[]{-0.3,  0.0, -0.4,  0.1,  0.0,  0.0,  0.2, -0.3,  0.0,  0.0}),

            // FRUSTRATION: event-triggered, moderate relief
            new DriveConfig(2.5, 0.5, 0.0, 0, 300, 0.0, 1.0,
                new double[]{ 0.2, -0.1, -0.3,  0.3, -0.2,  0.1,  0.0, -0.1,  0.0,  0.0}),

            // CREATIVITY: gentle build, fast relief (making is the reward)
            new DriveConfig(1.0, 0.3, 0.0002, 1500, 90, 0.0, 1.0,
                new double[]{ 0.2,  0.0,  0.2, -0.1,  0.0, -0.1, -0.2,  0.0,  0.0,  0.0}),

            // STARTLE: event-only, fast relief (a jolt fades in ~30s). Spiked by a large,
            // abrupt prediction error (OracleDriveIntegration.applyPredictionError); a
            // startle sharpens alertness → amplifies VIGILANCE (+0.2).
            new DriveConfig(3.0, 0.6, 0.0, 0, 30, 0.0, 1.0,
                //           SEEK  CARE  PLAY  VIGIL AFFIL GRIEF FRUST CREAT START SURP
                new double[]{ 0.0,  0.0,  0.0,  0.2,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0}),

            // SURPRISE: event-only, relief in ~60s. Spiked by the magnitude of any
            // expectation violation (prediction error); its behavioral consequences
            // (seeking / grief / frustration) are routed explicitly in
            // applyPredictionError, so its cross-mod row stays neutral.
            new DriveConfig(2.0, 0.5, 0.0, 0, 60, 0.0, 1.0,
                new double[]{ 0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0,  0.0}),
        };
    }

    /**
     * Apply archetype scaling to a set of drive configs.
     * Returns a new array with archetypeScale applied.
     */
    public static DriveConfig[] withArchetype(DriveConfig[] base, AgentArchetype archetype) {
        return withBoosts(base, archetype.driveBoosts());
    }

    /**
     * Apply a drive-boost map to a set of drive configs — the generalization of
     * {@link #withArchetype} that the individuality "B build" feeds from a freely
     * sampled {@code TemperamentSeed.driveBoosts()} (continuous in the seed) instead
     * of a 1-of-6 archetype table. Each {@code name → boost} entry becomes an
     * {@code archetypeScale} of {@code 1.0 + boost}; signed, so a negative boost
     * (e.g. low sociability → affiliation) makes that drive accumulate slower.
     * Unknown drive names are ignored.
     */
    public static DriveConfig[] withBoosts(DriveConfig[] base, Map<String, Double> boosts) {
        var configs = new DriveConfig[DRIVE_COUNT];
        for (int i = 0; i < DRIVE_COUNT; i++) {
            configs[i] = base[i];
        }
        if (boosts == null) return configs;
        for (var entry : boosts.entrySet()) {
            int idx = indexFor(entry.getKey());
            if (idx >= 0) {
                var c = configs[idx];
                // Boost translates to archetypeScale: +0.3 boost → 1.3x scale.
                // Clamp to the documented [0.5, 2.0] archetypeScale band.
                double scale = Math.max(0.5, Math.min(2.0, 1.0 + entry.getValue()));
                configs[idx] = new DriveConfig(
                    c.hillN, c.hillK, c.baseRate, c.buildTau, c.reliefTau,
                    c.reliefFloor, scale, c.crossMod);
            }
        }
        return configs;
    }

    /** Resolve drive name to index, or -1 if unknown. */
    public static int indexFor(String name) {
        return switch (name.toLowerCase()) {
            case "seeking", "curiosity" -> SEEKING;
            case "care" -> CARE;
            case "play" -> PLAY;
            case "vigilance", "alertness", "caution" -> VIGILANCE;
            case "affiliation", "social", "empathy" -> AFFILIATION;
            case "grief" -> GRIEF;
            case "frustration" -> FRUSTRATION;
            case "creativity", "focus" -> CREATIVITY;
            case "startle" -> STARTLE;
            case "surprise" -> SURPRISE;
            default -> -1;
        };
    }

    /** Hill function: H(x, n, K) = x^n / (K^n + x^n). */
    public static double hill(double x, double n, double K) {
        if (x <= 0) return 0.0;
        if (n == 1.0) return x / (K + x); // Michaelis-Menten fast path
        double xn = Math.pow(x, n);
        double kn = Math.pow(K, n);
        return xn / (kn + xn);
    }

    /** Michaelis-Menten saturation: v = Vmax * deficit / (Km + deficit). */
    public static double michaelisMenten(double deficit, double vmax, double km) {
        if (deficit <= 0) return 0.0;
        return vmax * deficit / (km + deficit);
    }
}
