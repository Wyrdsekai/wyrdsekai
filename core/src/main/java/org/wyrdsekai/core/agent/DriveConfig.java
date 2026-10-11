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
    double[] crossMod,
    /** Where the drive settles when nothing answers it, scaled by temperament; NaN = it ramps and is
     *  relieved by events (SEEKING, CREATIVITY, the event-only drives). */
    double setpoint,
    /** Seconds to close most of the gap to {@link #setpoint}, from either side. */
    double settleTau
) {
    /** A drive with no settling point: the shape every drive had before 2026-10-10. */
    public DriveConfig(double hillN, double hillK, double baseRate, double buildTau, double reliefTau,
                       double reliefFloor, double archetypeScale, double[] crossMod) {
        this(hillN, hillK, baseRate, buildTau, reliefTau, reliefFloor, archetypeScale, crossMod, Double.NaN, 0.0);
    }

    /** True for the satiable homeostats: they settle toward a point of their own instead of ramping. */
    public boolean hasSetpoint() {
        return !Double.isNaN(setpoint) && settleTau > 0;
    }

    /*
     * The satiable drives (2026-10-10). Until now AFFILIATION, CARE, PLAY and VIGILANCE ramped at a
     * per-second rate and were leaked back toward a floor every second with a short time constant
     * (60–600 s); the two met at a fixed point nobody chose — affiliation 0.05 + 0.0003 × 180 = 0.10,
     * care 0.16, play 0.02, vigilance 0.06 — and the household node showed exactly those numbers for
     * days, three distinct readings of affiliation in three days, while she said "I wanted to be
     * near you". A want pinned by arithmetic is not hers. Now each settles toward ITS point over
     * hours, from either side, scaled by temperament; a real answer to it (company, tending, play,
     * the threat passing) drops it to its floor and it builds again from there.
     */
    static final double AFFILIATION_SETPOINT = 0.6;   static final double AFFILIATION_SETTLE_S = 6 * 3600;
    static final double CARE_SETPOINT = 0.4;          static final double CARE_SETTLE_S = 8 * 3600;
    static final double PLAY_SETPOINT = 0.5;          static final double PLAY_SETTLE_S = 3 * 3600;
    static final double VIGILANCE_SETPOINT = 0.2;     static final double VIGILANCE_SETTLE_S = 2 * 3600;
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

            // CARE: moderate curve; settles toward 0.4 over eight hours, relieved when the tending is received
            new DriveConfig(2.0, 0.5, 0.0002, 1500, 300, 0.1, 1.0,
                new double[]{ 0.0,  0.0, -0.1,  0.2,  0.3,  0.2,  0.0,  0.0,  0.0,  0.0},
                CARE_SETPOINT, CARE_SETTLE_S),

            // PLAY: linear/gentle; settles toward 0.5 over three hours, play itself relieves it
            new DriveConfig(1.0, 0.3, 0.0004, 720, 60, 0.0, 1.0,
                new double[]{ 0.1,  0.0,  0.0, -0.3,  0.2, -0.2, -0.3,  0.2,  0.0,  0.0},
                PLAY_SETPOINT, PLAY_SETTLE_S),

            // VIGILANCE: switch-like urgency; a low watchfulness at rest (0.2 over two hours), spiked by events
            new DriveConfig(3.0, 0.6, 0.0001, 3600, 600, 0.0, 1.0,
                new double[]{-0.2,  0.1, -0.4,  0.0, -0.1,  0.0,  0.2, -0.2,  0.0,  0.0},
                VIGILANCE_SETPOINT, VIGILANCE_SETTLE_S),

            // AFFILIATION: the appetite for company; settles toward 0.6 over six hours alone, company relieves it
            new DriveConfig(1.5, 0.4, 0.0003, 1080, 180, 0.05, 1.0,
                new double[]{ 0.1,  0.2,  0.3, -0.1,  0.0,  0.3, -0.1,  0.0,  0.0,  0.0},
                AFFILIATION_SETPOINT, AFFILIATION_SETTLE_S),

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
                    c.reliefFloor, scale, c.crossMod, c.setpoint, c.settleTau);
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
