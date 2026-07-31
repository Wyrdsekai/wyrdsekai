package org.wyrdsekai.core.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ten motivational drives that create internal impulse for proactive behavior.
 * Each drive accumulates pressure (0.0-1.0) over time and spikes on relevant events.
 * When pressure crosses a threshold, the agent considers acting via ProactivityJudgment.
 *
 * <p>Drives are Panksepp-informed subcortical affective systems, adapted for text-native AI:
 * <ul>
 *   <li>SEEKING — exploration, goal pursuit, resource acquisition (dopamine analog)
 *   <li>CARE — nurturing, protective behavior toward bonded entities (oxytocin)
 *   <li>PLAY — social learning, humor, creative exploration in safe contexts (endorphins)
 *   <li>VIGILANCE — threat detection, environmental monitoring (norepinephrine)
 *   <li>AFFILIATION — bonding, belonging, social connection (oxytocin/vasopressin)
 *   <li>GRIEF — separation distress when bonds severed (opioid withdrawal)
 *   <li>FRUSTRATION — goal blockage, repeated failure (substance P / low serotonin)
 *   <li>CREATIVITY — making, crafting, expressing, building (endorphins/dopamine)
 *   <li>STARTLE — sharp involuntary response to sudden stimulus (Phase 1A stub)
 *   <li>SURPRISE — appraisal-based reaction to violated expectation (Phase 1A stub)
 * </ul>
 *
 * <p>VitalityState = how the agent FEELS. DriveState = what the agent WANTS TO DO.
 *
 * <p><b>Phase 1A (structural-only):</b> {@code startle} and {@code surprise} are present in
 * the record and round-trip through arrays/maps, but they are NOT yet wired into spike rules
 * or {@link DriveEngine} cross-modulation effects (their crossMod columns are all 0.0). They
 * also do NOT appear in {@link #prefix(VitalityState)} output — that format remains the
 * legacy 8-drive form to preserve the Drive-9B model contract until Phase 3 retraining.
 *
 * @see DriveConfig for per-drive Hill function parameters and cross-modulation
 * @see DriveEngine for the tick/spike/relief engine with interaction matrix
 */
public record DriveState(
    double seeking,
    double care,
    double play,
    double vigilance,
    double affiliation,
    double grief,
    double frustration,
    double creativity,
    double startle,
    double surprise
) {
    /**
     * 8-arg backward-compatible constructor. Older call sites (tests, legacy callers)
     * that don't know about startle/surprise still compile; new fields default to 0.0.
     */
    public DriveState(double seeking, double care, double play, double vigilance,
                      double affiliation, double grief, double frustration, double creativity) {
        this(seeking, care, play, vigilance, affiliation, grief, frustration, creativity,
             0.0, 0.0);
    }

    /** All drives at zero — freshly relieved or newly created agent. */
    public static DriveState initial() {
        return new DriveState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /** Construct from array (canonical order per DriveConfig indices). Accepts 8 or 10 elements. */
    public static DriveState fromArray(double[] d) {
        if (d.length == 8) {
            return new DriveState(d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], 0.0, 0.0);
        }
        if (d.length < DriveConfig.DRIVE_COUNT) {
            throw new IllegalArgumentException(
                "Need " + DriveConfig.DRIVE_COUNT + " drive values, got " + d.length);
        }
        return new DriveState(d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9]);
    }

    /** Export to array (canonical order per DriveConfig indices, length 10). */
    public double[] toArray() {
        return new double[]{seeking, care, play, vigilance, affiliation, grief, frustration, creativity,
                            startle, surprise};
    }

    /**
     * Export to a name-keyed map (10 entries). Useful for JSON serialization and persistence
     * shapes that don't bind to the record's field order.
     */
    public Map<String, Double> toMap() {
        var m = new LinkedHashMap<String, Double>();
        double[] d = toArray();
        for (int i = 0; i < DriveConfig.DRIVE_COUNT; i++) {
            m.put(DriveConfig.DRIVE_NAMES[i], d[i]);
        }
        return m;
    }

    /**
     * Reconstruct from a name-keyed map. Missing keys default to 0.0, so an 8-key legacy map
     * still round-trips with new fields defaulted.
     */
    public static DriveState fromMap(Map<String, Double> m) {
        if (m == null) return initial();
        double[] d = new double[DriveConfig.DRIVE_COUNT];
        for (int i = 0; i < DriveConfig.DRIVE_COUNT; i++) {
            Double v = m.get(DriveConfig.DRIVE_NAMES[i]);
            d[i] = v == null ? 0.0 : v;
        }
        return fromArray(d);
    }

    // ── Legacy compatibility (maps old drive names to new) ──────────────

    /** @deprecated Use {@link #seeking()} — curiosity merged into SEEKING. */
    @Deprecated
    public double curiosity() { return seeking; }

    /** @deprecated Use {@link #affiliation()} — social merged into AFFILIATION. */
    @Deprecated
    public double social() { return affiliation; }

    /** @deprecated Use {@link #seeking()} — achievement merged into SEEKING. */
    @Deprecated
    public double achievement() { return seeking; }

    /** @deprecated Use {@link #vigilance()} — alertness renamed to VIGILANCE. */
    @Deprecated
    public double alertness() { return vigilance; }

    // ── Simple tick (used only when DriveEngine is not wired) ───────────

    private static final double SEEKING_RATE     = 0.0003;
    private static final double CARE_RATE        = 0.0002;
    private static final double PLAY_RATE        = 0.0004;
    private static final double VIGILANCE_RATE   = 0.0001;
    private static final double AFFILIATION_RATE = 0.0003;
    private static final double GRIEF_RATE       = 0.0;    // event-only
    private static final double FRUSTRATION_RATE = 0.0;    // event-only
    private static final double CREATIVITY_RATE  = 0.0002;
    // STARTLE/SURPRISE are event-only — no passive accumulation (like grief/frustration).
    // They are spiked by prediction error in OracleDriveIntegration.applyPredictionError.
    private static final double STARTLE_RATE     = 0.0;
    private static final double SURPRISE_RATE    = 0.0;

    /**
     * Simple passive tick — linear accumulation (legacy fallback).
     * Prefer DriveEngine.tick() for Hill-function shaped accumulation with cross-modulation.
     *
     * <p>Phase 1A: startle and surprise pass through unchanged (no accumulation).
     */
    public DriveState tick() {
        return new DriveState(
            clamp(seeking + SEEKING_RATE),
            clamp(care + CARE_RATE),
            clamp(play + PLAY_RATE),
            clamp(vigilance + VIGILANCE_RATE),
            clamp(affiliation + AFFILIATION_RATE),
            clamp(grief + GRIEF_RATE),
            clamp(frustration + FRUSTRATION_RATE),
            clamp(creativity + CREATIVITY_RATE),
            clamp(startle + STARTLE_RATE),
            clamp(surprise + SURPRISE_RATE)
        );
    }

    // ── Event spikes ─────────────────────────────────────────────────────

    public DriveState spikeSeeking(double amount) {
        return new DriveState(clamp(seeking + amount), care, play, vigilance, affiliation, grief, frustration, creativity, startle, surprise);
    }

    public DriveState spikeCare(double amount) {
        return new DriveState(seeking, clamp(care + amount), play, vigilance, affiliation, grief, frustration, creativity, startle, surprise);
    }

    public DriveState spikePlay(double amount) {
        return new DriveState(seeking, care, clamp(play + amount), vigilance, affiliation, grief, frustration, creativity, startle, surprise);
    }

    public DriveState spikeVigilance(double amount) {
        return new DriveState(seeking, care, play, clamp(vigilance + amount), affiliation, grief, frustration, creativity, startle, surprise);
    }

    public DriveState spikeAffiliation(double amount) {
        return new DriveState(seeking, care, play, vigilance, clamp(affiliation + amount), grief, frustration, creativity, startle, surprise);
    }

    public DriveState spikeGrief(double amount) {
        return new DriveState(seeking, care, play, vigilance, affiliation, clamp(grief + amount), frustration, creativity, startle, surprise);
    }

    public DriveState spikeFrustration(double amount) {
        return new DriveState(seeking, care, play, vigilance, affiliation, grief, clamp(frustration + amount), creativity, startle, surprise);
    }

    public DriveState spikeCreativity(double amount) {
        return new DriveState(seeking, care, play, vigilance, affiliation, grief, frustration, clamp(creativity + amount), startle, surprise);
    }

    /**
     * Spike STARTLE — the reflexive jolt. Fed live by a large, abrupt prediction
     * error ({@link OracleDriveIntegration#applyPredictionError}); decays fast (~30s)
     * and amplifies VIGILANCE via the cross-modulation matrix.
     */
    public DriveState spikeStartle(double amount) {
        return new DriveState(seeking, care, play, vigilance, affiliation, grief, frustration, creativity, clamp(startle + amount), surprise);
    }

    /**
     * Spike SURPRISE — the graded expectation-violation feeling. Fed live by the
     * magnitude of any prediction error ({@link OracleDriveIntegration#applyPredictionError});
     * decays in ~60s. Its behavioral consequences are routed there (seeking on
     * positive, grief/frustration on negative), so this drive is the felt signal.
     */
    public DriveState spikeSurprise(double amount) {
        return new DriveState(seeking, care, play, vigilance, affiliation, grief, frustration, creativity, startle, clamp(surprise + amount));
    }

    /** Spike by drive index (canonical order). */
    public DriveState spike(int driveIndex, double amount) {
        double[] d = toArray();
        d[driveIndex] = clamp(d[driveIndex] + amount);
        return fromArray(d);
    }

    // ── Legacy spike compatibility ───────────────────────────────────────

    /** @deprecated Use {@link #spikeSeeking(double)} */
    @Deprecated
    public DriveState spikeCuriosity(double amount) { return spikeSeeking(amount); }

    /** @deprecated Use {@link #spikeAffiliation(double)} */
    @Deprecated
    public DriveState spikeSocial(double amount) { return spikeAffiliation(amount); }

    /** @deprecated Use {@link #spikeSeeking(double)} */
    @Deprecated
    public DriveState spikeAchievement(double amount) { return spikeSeeking(amount); }

    /** @deprecated Use {@link #spikeVigilance(double)} */
    @Deprecated
    public DriveState spikeAlertness(double amount) { return spikeVigilance(amount); }

    // ── Relief (after acting on a drive) ─────────────────────────────────

    public DriveState relieveSeeking()     { return withDrive(DriveConfig.SEEKING, 0.05); }
    public DriveState relieveCare()        { return withDrive(DriveConfig.CARE, 0.1); }
    public DriveState relievePlay()        { return withDrive(DriveConfig.PLAY, 0.0); }
    public DriveState relieveVigilance()   { return withDrive(DriveConfig.VIGILANCE, 0.0); }
    public DriveState relieveAffiliation() { return withDrive(DriveConfig.AFFILIATION, 0.05); }
    public DriveState relieveGrief()       { return withDrive(DriveConfig.GRIEF, 0.0); }
    public DriveState relieveFrustration() { return withDrive(DriveConfig.FRUSTRATION, 0.0); }
    public DriveState relieveCreativity()  { return withDrive(DriveConfig.CREATIVITY, 0.0); }
    public DriveState relieveStartle()     { return withDrive(DriveConfig.STARTLE, 0.0); }
    public DriveState relieveSurprise()    { return withDrive(DriveConfig.SURPRISE, 0.0); }

    /** Relieve by drive index to its configured relief floor. */
    public DriveState relieve(int driveIndex, double floor) {
        return withDrive(driveIndex, floor);
    }

    /** Legacy relief methods — map to new drive names. */
    @Deprecated public DriveState relieveCuriosity()   { return relieveSeeking(); }
    @Deprecated public DriveState relieveSocial()      { return relieveAffiliation(); }
    @Deprecated public DriveState relieveAchievement() { return relieveSeeking(); }
    @Deprecated public DriveState relieveAlertness()   { return relieveVigilance(); }

    // ── Drive Prefix for SSD-Trained Models ─────────────────────────────

    /**
     * Structured drive prefix for SSD-trained models (drives only, legacy).
     */
    public String prefix() {
        return prefix(null);
    }

    /**
     * Structured drive prefix for SSD-trained models with vitality tanks.
     * Format: [drives: seeking=0.3 care=0.1 ... | energy=0.7 confidence=0.5 integrity=0.7 disgust=0.0]
     * This is the exact format the model was trained on during SSD fine-tuning.
     *
     * <p><b>Phase 1A constraint:</b> output stays restricted to the legacy 8 drives + the legacy
     * 4 vitality tanks. STARTLE/SURPRISE and the 10 new tanks are deliberately omitted here —
     * the Drive-9B model was trained on this exact format and changing it now would break the
     * model contract. Phase 3 will retrain on an extended format and update this method.
     */
    public String prefix(VitalityState vitality) {
        var sb = new StringBuilder("[drives:");
        // Legacy 8 drives only — STARTLE/SURPRISE intentionally excluded (Phase 3 retrain).
        double[] d = toArray();
        for (int i = 0; i < 8; i++) {
            sb.append(' ').append(DriveConfig.DRIVE_NAMES[i]).append('=').append(String.format("%.1f", d[i]));
        }
        if (vitality != null) {
            sb.append(" | energy=").append(String.format("%.1f", vitality.energy()));
            sb.append(" confidence=").append(String.format("%.1f", vitality.confidence()));
            sb.append(" integrity=").append(String.format("%.1f", vitality.integrity()));
            sb.append(" disgust=").append(String.format("%.1f", vitality.disgust()));
        }
        sb.append(']');
        return sb.toString();
    }

    // ── Description for LLM prompt ──────────────────────────────────────

    /**
     * Natural language description of drive state for system prompt injection.
     * Gives the LLM awareness of what the agent currently wants/feels.
     * Used as fallback when SSD-trained model is not available.
     *
     * <p>All ten drives are surfaced, STARTLE/SURPRISE included (spiked by prediction
     * error). i18n keys for them are still a follow-up; the EN clauses live below.
     */
    public String describe() {
        var sb = new StringBuilder("Drives: ");
        var peak = peak();

        // Describe the dominant drive
        if (peak.pressure() > 0.5) {
            sb.append("You feel a strong pull toward ").append(driveFeeling(peak.name())).append(". ");
        } else if (peak.pressure() > 0.3) {
            sb.append("You feel a gentle pull toward ").append(driveFeeling(peak.name())).append(". ");
        } else {
            sb.append("Your drives are quiet — no strong impulses. ");
        }

        // Mention any secondary drives above 0.3 — all ten, including STARTLE/SURPRISE
        // (now spiked by prediction error in OracleDriveIntegration.applyPredictionError).
        double[] d = toArray();
        for (int i = 0; i < DriveConfig.DRIVE_COUNT; i++) {
            if (d[i] > 0.3 && !DriveConfig.DRIVE_NAMES[i].equals(peak.name())) {
                sb.append("Also feeling some ").append(driveFeeling(DriveConfig.DRIVE_NAMES[i])).append(". ");
            }
        }

        // Grief and frustration get special mention even at moderate levels
        if (grief > 0.2) {
            sb.append("A sense of loss lingers. ");
        }
        if (frustration > 0.3) {
            sb.append("Something feels blocked or stuck. ");
        }

        return sb.toString().trim();
    }

    private static String driveFeeling(String driveName) {
        return switch (driveName) {
            case "seeking" -> "exploration and discovery";
            case "care" -> "looking after someone";
            case "play" -> "playfulness and fun";
            case "vigilance" -> "watchfulness and alertness";
            case "affiliation" -> "connection and belonging";
            case "grief" -> "something you miss or have lost";
            case "frustration" -> "pushing through a block";
            case "creativity" -> "making or building something";
            // STARTLE/SURPRISE are live: spiked by prediction error
            // (OracleDriveIntegration.applyPredictionError) and surfaced like any drive.
            case "startle" -> "a sudden jolt of alertness";
            case "surprise" -> "an expectation-violating turn";
            default -> driveName;
        };
    }

    /**
     * Compact representation for dashboard/look display.
     */
    public String dashboard() {
        var sb = new StringBuilder();
        double[] d = toArray();
        for (int i = 0; i < DriveConfig.DRIVE_COUNT; i++) {
            String name = DriveConfig.DRIVE_NAMES[i];
            int bars = (int) (d[i] * 10);
            String arrow = d[i] > 0.5 ? " ↑" : d[i] > 0.1 ? " →" : " ·"; // ↑ → ·
            sb.append(String.format("  %-12s %s%s %.2f%s%n",
                name.toUpperCase(),
                "█".repeat(bars),
                "░".repeat(10 - bars),
                d[i], arrow));
        }
        return sb.toString();
    }

    // ── Queries ──────────────────────────────────────────────────────────

    /** Returns the name and pressure of the highest drive. */
    public DrivePeak peak() {
        double[] d = toArray();
        int maxIdx = 0;
        for (int i = 1; i < d.length; i++) {
            if (d[i] > d[maxIdx]) maxIdx = i;
        }
        return new DrivePeak(DriveConfig.DRIVE_NAMES[maxIdx], d[maxIdx]);
    }

    /** Whether any drive exceeds the given threshold. */
    public boolean anyAbove(double threshold) {
        double[] d = toArray();
        for (double v : d) {
            if (v > threshold) return true;
        }
        return false;
    }

    /** Get drive value by index. */
    public double get(int index) {
        return toArray()[index];
    }

    public record DrivePeak(String name, double pressure) {}

    // ── Internal ─────────────────────────────────────────────────────────

    private DriveState withDrive(int index, double value) {
        double[] d = toArray();
        d[index] = clamp(value);
        return fromArray(d);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
