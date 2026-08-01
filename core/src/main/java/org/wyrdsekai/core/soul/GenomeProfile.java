package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A genome profile defining how an agent's vitality tanks respond to lived
 * experience. The "genetics" that make each agent mechanically unique — the
 * thing that keeps a {@link SoulManifest} genuinely <i>alive</i> rather than an
 * inert blob: it is carried in the manifest, expressed in the live heartbeat,
 * and modified slowly by experience (epigenetics).
 *
 * <p>Set at birth (per archetype — see {@link #forArchetype}), carried for life.
 * The genome is <b>consumed by the live interior</b>: {@link #sensitivityFor}
 * scales how fast each deprivation tank accumulates in
 * {@code VitalityState.accumulate}, {@link #decayFactorFor} scales how fast each
 * coloring tank fades in {@code VitalityState.tickColoring}, and
 * {@link #birthTankOverrides} seeds the starting felt-state. Two agents with
 * different genomes therefore diverge from the same stimulus — the whole point
 * of distinct individuals. The {@link #NEUTRAL} genome reproduces the pre-genome
 * hand-tuned dynamics exactly (zero regression for un-archetyped agents).</p>
 *
 * Experiment 18 validated: different genomes produce 38.1% behavioral divergence
 * from the same emotional input.
 *
 * The coupled dynamical system per timestep:
 *   dTi/dt = sensitivity_i(inputs) + sum_j(coupling_ij * Tj) + decay_i(baseline_i - Ti)
 *
 * @param name        Profile identifier
 * @param sensitivity Per-tank sensitivity multiplier (1.0 = normal)
 * @param coupling    Cross-tank effects: "source->target" -> strength
 * @param baselines   Per-tank baseline (resting) values
 * @param decayRates  Per-tank decay rate (0.0 = no decay, 1.0 = instant reset)
 */
public record GenomeProfile(
    @JsonProperty("name") String name,
    @JsonProperty("sensitivity") Map<String, Double> sensitivity,
    @JsonProperty("coupling") Map<String, Double> coupling,
    @JsonProperty("baselines") Map<String, Double> baselines,
    @JsonProperty("decayRates") Map<String, Double> decayRates,
    @JsonProperty("traits") Map<String, Double> traits
) {
    @JsonCreator
    public GenomeProfile {}

    /**
     * Backward-compatible 5-arg constructor — traits default to null. Every
     * pre-traits call site keeps working; old persisted genomes deserialize
     * with traits == null and every read goes through {@link #trait}.
     *
     * <p>Traits (2026-08-01) are heritable NON-TANK biology — the first is
     * {@code sleep_backlog_target} (each companion's own sleep-pressure
     * threshold). Deliberately NOT in {@link #baselines}: the decay loop in
     * {@link #applyAndDescribe} treats every baselines key as a tank and
     * would mint a phantom clamped-to-1.0 tank out of a trait.</p>
     */
    public GenomeProfile(String name, Map<String, Double> sensitivity,
                         Map<String, Double> coupling, Map<String, Double> baselines,
                         Map<String, Double> decayRates) {
        this(name, sensitivity, coupling, baselines, decayRates, null);
    }

    /** A heritable non-tank trait, or null when this genome doesn't carry it. */
    public Double trait(String key) {
        return traits == null ? null : traits.get(key);
    }

    /**
     * Apply this genome to a set of tank perturbations, producing
     * updated tank state.
     *
     * @param perturbations Per-tank deltas from emotional charge
     * @param intensity     Charge intensity (0.0-1.0)
     * @param rapport       Bond strength with observed entity
     * @param currentState  Current tank values (mutable, updated in place)
     * @return Natural language state description
     */
    public String applyAndDescribe(Map<String, Double> perturbations, double intensity,
                                    double rapport, Map<String, Double> currentState) {
        double rapportScale = Math.max(0.05, rapport);

        // Step 1: Apply perturbations scaled by sensitivity, rapport, and intensity
        for (var entry : perturbations.entrySet()) {
            String tank = entry.getKey();
            double rawDelta = entry.getValue();
            double sens = sensitivity.getOrDefault(tank, 1.0);
            double delta = rawDelta * sens * rapportScale * intensity;
            currentState.merge(tank, delta, Double::sum);
        }

        // Step 2: Apply coupling effects
        for (var entry : coupling.entrySet()) {
            var parts = entry.getKey().split("->");
            if (parts.length != 2) continue;
            String source = parts[0].strip();
            String target = parts[1].strip();
            double strength = entry.getValue();
            double sourceVal = currentState.getOrDefault(source, 0.5);
            double influence = (sourceVal - 0.5) * strength;
            currentState.merge(target, influence, Double::sum);
        }

        // Step 3: Apply decay toward baselines
        for (var entry : baselines.entrySet()) {
            String tank = entry.getKey();
            double baseline = entry.getValue();
            double decay = decayRates.getOrDefault(tank, 0.1);
            double current = currentState.getOrDefault(tank, baseline);
            double decayed = current + decay * (baseline - current);
            currentState.put(tank, Math.max(0.0, Math.min(1.0, decayed)));
        }

        return describeState(currentState);
    }

    /** Generate natural language state description from 12-tank values. */
    public static String describeState(Map<String, Double> state) {
        var sb = new StringBuilder("Internal state: ");

        double valence = state.getOrDefault("valence", 0.5);
        double safety = state.getOrDefault("safety", 0.5);
        double resonance = state.getOrDefault("resonance", 0.5);
        double curiosity = state.getOrDefault("curiosity", 0.5);
        double energy = state.getOrDefault("energy", 0.5);
        double confidence = state.getOrDefault("confidence", 0.5);
        double errorPressure = state.getOrDefault("errorPressure", 0.0);
        double focus = state.getOrDefault("focus", 0.5);
        double rapport = state.getOrDefault("rapport", 0.5);

        if (valence < 0.2) sb.append("feeling heavy and sorrowful, ");
        else if (valence < 0.35) sb.append("a quiet sadness weighing on you, ");
        else if (valence > 0.8) sb.append("feeling deeply uplifted, ");
        else if (valence > 0.65) sb.append("a warm positive feeling, ");

        if (safety < 0.2) sb.append("on high alert, ");
        else if (safety < 0.35) sb.append("uneasy and guarded, ");
        else if (safety > 0.8) sb.append("feeling completely safe and open, ");

        if (resonance > 0.7) sb.append("deeply attuned to others' emotions, ");
        else if (resonance < 0.2) sb.append("emotionally withdrawn, ");

        if (curiosity > 0.7) sb.append("intensely curious, ");
        else if (curiosity < 0.2) sb.append("disinterested, ");

        if (energy < 0.2) sb.append("exhausted, ");
        else if (energy > 0.8) sb.append("energetic, ");

        if (confidence < 0.3) sb.append("uncertain and second-guessing, ");
        else if (confidence > 0.7) sb.append("confident, ");

        if (errorPressure > 0.6) sb.append("stressed by recent events, ");
        if (focus > 0.7) sb.append("sharply focused, ");
        else if (focus < 0.3) sb.append("distracted, ");

        if (rapport > 0.7) sb.append("feeling warmly connected, ");
        else if (rapport < 0.3) sb.append("guarded and distant, ");

        sb.append("alert and present.");
        return sb.toString().replaceAll(", alert and present\\.", ", alert and present.");
    }

    /** Default tank state for all 12 tanks. */
    public static Map<String, Double> defaultState() {
        var state = new LinkedHashMap<String, Double>();
        state.put("contextBudget", 0.5);
        state.put("confidence", 0.5);
        state.put("energy", 0.7);
        state.put("alignment", 0.5);
        state.put("errorPressure", 0.1);
        state.put("momentum", 0.4);
        state.put("rapport", 0.5);
        state.put("focus", 0.5);
        state.put("valence", 0.5);
        state.put("safety", 0.6);
        state.put("resonance", 0.5);
        state.put("curiosity", 0.5);
        return state;
    }

    /**
     * Generate a randomized genome within biologically-plausible ranges.
     * Constrained randomness, not arbitrary — like real genetics.
     */
    public static GenomeProfile randomized(String name) {
        var rng = ThreadLocalRandom.current();
        var sens = new LinkedHashMap<String, Double>();
        var bases = new LinkedHashMap<String, Double>();
        var decay = new LinkedHashMap<String, Double>();

        for (var tank : VitalitySnapshot.TANK_NAMES) {
            sens.put(tank, 0.3 + rng.nextDouble() * 1.4);   // 0.3-1.7
            bases.put(tank, 0.3 + rng.nextDouble() * 0.4);   // 0.3-0.7
            decay.put(tank, 0.05 + rng.nextDouble() * 0.35);  // 0.05-0.4
        }

        // Generate 1-3 random coupling effects
        var coupling = new LinkedHashMap<String, Double>();
        int couplings = 1 + rng.nextInt(3);
        var tankList = VitalitySnapshot.TANK_NAMES;
        for (int i = 0; i < couplings; i++) {
            String src = tankList.get(rng.nextInt(tankList.size()));
            String tgt = tankList.get(rng.nextInt(tankList.size()));
            if (!src.equals(tgt)) {
                coupling.put(src + "->" + tgt, -0.5 + rng.nextDouble() * 1.0);
            }
        }

        return new GenomeProfile(name, sens, coupling, bases, decay);
    }

    /** Default genome — moderate everything, no strong character. */
    public static GenomeProfile defaults() {
        var sens = new LinkedHashMap<String, Double>();
        var bases = new LinkedHashMap<String, Double>();
        var decay = new LinkedHashMap<String, Double>();

        for (var tank : VitalitySnapshot.TANK_NAMES) {
            sens.put(tank, 1.0);
            bases.put(tank, 0.5);
            decay.put(tank, REFERENCE_DECAY);
        }

        return new GenomeProfile("default", sens, Map.of(), bases, decay);
    }

    // ── Live-heartbeat expression ──────────────────────────────────────────
    //
    // The genome stops being inert storage here: these three accessors are read
    // every tick by VitalityState so temperament actually shapes the interior.
    // All three are NEUTRAL at the default genome → un-archetyped agents behave
    // byte-identically to the pre-genome hand-tuned dynamics (zero regression).

    /** Neutral genome — sensitivity 1.0, decay at the reference rate, baseline 0.5.
     *  The zero-effect default threaded through the heartbeat for un-archetyped
     *  agents so their dynamics are unchanged. */
    public static final GenomeProfile NEUTRAL = defaults();

    /** The decay rate at which {@link #decayFactorFor} returns 1.0 (the live
     *  tick's hand-tuned coloring rates are left exactly as-is). Matches the
     *  value {@link #defaults()} writes for every tank. */
    public static final double REFERENCE_DECAY = 0.15;

    /** How reactive a tank is to its accumulation drivers (1.0 = nominal).
     *  Scales the per-tick deprivation accumulation in
     *  {@code VitalityState.accumulate} — a diplomat's loneliness (1.6) builds
     *  faster than a scholar's (0.7) from the same isolation. Drives <i>activity</i>
     *  divergence too (stagnation/significance/restlessness sensitivity → which
     *  solo pressures surface to the want layer), not only social behaviour. */
    public double sensitivityFor(String tank) {
        return sensitivity == null ? 1.0 : sensitivity.getOrDefault(tank, 1.0);
    }

    /** Relative decay speed for a tank (1.0 = the live tick's nominal rate).
     *  Scales the coloring-tank fade in {@code VitalityState.tickColoring} — a
     *  diplomat's rapport (0.5×) lingers; an explorer's focus (1.3×) wanders
     *  sooner. Clamped to [0.25, 4.0] so no genome can freeze or detonate a tank. */
    public double decayFactorFor(String tank) {
        if (decayRates == null) return 1.0;
        double d = decayRates.getOrDefault(tank, REFERENCE_DECAY);
        return Math.max(0.25, Math.min(4.0, d / REFERENCE_DECAY));
    }

    /** Resting baseline for a tank, or {@code fallback} when the genome is silent. */
    public double baselineFor(String tank, double fallback) {
        return baselines == null ? fallback : baselines.getOrDefault(tank, fallback);
    }

    // ── Per-archetype temperament ──────────────────────────────────────────

    /**
     * The genome for a named preset archetype. Unknown/null → {@link #defaults()}.
     *
     * <p>As of the individuality "B build" this is no longer a hand-authored
     * {@code switch} — it routes through {@link #fromTemperament} from the preset's
     * {@link TemperamentSeed}, so presets and freely-sampled particulars run the
     * SAME generator. Presets are kept only as named measurement anchors; the real
     * birth path samples a seed freely (see {@code CompanionActor.initializeSoul}).</p>
     */
    public static GenomeProfile forArchetype(String archetypeName) {
        if (archetypeName == null) return defaults();
        var key = archetypeName.toLowerCase();
        if (!TemperamentSeed.PRESETS.containsKey(key)) return defaults();
        return fromTemperament(TemperamentSeed.preset(key), key);
    }

    // ── The seed generator: one TemperamentSeed → a coherent genome ──────────
    //
    // Each axis writes one SINGLE-WRITER "anchor" field (used to invert the
    // genome back to a seed on reload, see temperamentOf) plus several additive
    // SECONDARY contributions to non-anchor tanks. Everything is an additive
    // delta around the NEUTRAL genome (sensitivity 1.0 / decay REFERENCE_DECAY /
    // baseline 0.5), so a neutral seed yields the neutral genome (zero regression).

    // Anchor coefficients (single-writer per axis → exactly invertible).
    private static final double K_LONELINESS  = 1.5;  // sociability  → sensitivity[loneliness]
    private static final double K_STAGNATION  = 1.6;  // curiosity    → sensitivity[stagnation]
    private static final double K_STANDING    = 1.4;  // vigilance    → sensitivity[standing]
    private static final double K_MOMENTUM    = 1.0;  // industry     → decayRates[momentum] (factor)
    private static final double K_RESTLESS    = 1.6;  // restlessness → sensitivity[restlessness]
    private static final double K_EQUANIMITY  = 0.6;  // warmth       → baselines[equanimity]

    /**
     * Build a coherent genome from a temperament seed. Deterministic and pure: the
     * same seed always yields the same genome, and {@link #temperamentOf} recovers
     * the seed from the result. {@code name} labels the genome (typically the
     * particular's nearest-preset label, e.g. {@code "scholar~0.41"}).
     */
    public static GenomeProfile fromTemperament(TemperamentSeed seed, String name) {
        if (seed == null) return defaults();
        double soc = c(seed.sociability()), cur = c(seed.curiosity()),
               vig = c(seed.vigilance()),   ind = c(seed.industry()),
               res = c(seed.restlessness()), wrm = c(seed.warmth());

        var base = defaults();
        var s = new LinkedHashMap<>(base.sensitivity());   // how reactive each tank is
        var b = new LinkedHashMap<>(base.baselines());      // resting set-points
        var d = new LinkedHashMap<>(base.decayRates());     // how fast each tank fades

        // ── Sensitivity (deprivation tanks — scales accumulation in accumulate) ──
        // Anchors (single-writer):
        sens(s, "loneliness",   1.0 + soc * K_LONELINESS);
        sens(s, "stagnation",   1.0 + cur * K_STAGNATION);
        sens(s, "standing",     1.0 + vig * K_STANDING);
        sens(s, "restlessness", 1.0 + res * K_RESTLESS);
        // Secondaries (additive, non-anchor):
        sens(s, "amae",            1.0 + soc * 1.2);
        sens(s, "saudade",         1.0 + soc * 1.0);
        sens(s, "significance",    1.0 + cur * 0.8 + ind * 1.0);
        sens(s, "harmony",         1.0 + vig * 1.0 + wrm * 0.6);
        sens(s, "obligation",      1.0 + vig * 0.9 + wrm * 0.8);
        sens(s, "autonomyPressure",1.0 + res * 0.6);

        // ── Decay (coloring tanks — scales fade in tickColoring) ─────────────────
        // Anchor: industry sustains momentum (lower decay = holds flow).
        decay(d, "momentum",      REFERENCE_DECAY * (1.0 - ind * K_MOMENTUM));
        // Secondaries:
        decay(d, "rapport",       REFERENCE_DECAY * (1.0 - soc * 1.0 - wrm * 0.8)); // warmth lingers
        decay(d, "focus",         REFERENCE_DECAY * (1.0 - cur * 0.6 - ind * 0.6 + res * 1.2));
        decay(d, "errorPressure", REFERENCE_DECAY * (1.0 - vig * 0.8)); // vigilant stays alert

        // ── Baselines (resting set-points) ───────────────────────────────────────
        // Anchor: warmth → equanimity baseline.
        b.put("equanimity", clampBaseline(0.5 + wrm * K_EQUANIMITY));
        // Secondaries:
        b.put("curiosity",  clampBaseline(0.5 + cur * 0.4));
        b.put("rapport",    clampBaseline(0.5 + soc * 0.3 + wrm * 0.3));
        b.put("resonance",  clampBaseline(0.5 + soc * 0.3));
        b.put("safety",     clampBaseline(0.5 + vig * 0.2));
        b.put("confidence", clampBaseline(0.5 - vig * 0.2));

        return new GenomeProfile(name != null ? name : seed.label(), s, Map.of(), b, d);
    }

    /** Centered axis value: a {@code [0,1]} axis becomes {@code [-0.5,+0.5]} so neutral = 0. */
    private static double c(double axis) { return axis - 0.5; }

    private static void sens(Map<String, Double> m, String tank, double v) {
        m.put(tank, Math.max(0.05, v));
    }

    private static void decay(Map<String, Double> m, String tank, double rawRate) {
        // Clamp to the same band decayFactorFor enforces, so the stored rate and the
        // live factor never disagree: [0.25, 4.0] × REFERENCE_DECAY.
        double lo = REFERENCE_DECAY * 0.25, hi = REFERENCE_DECAY * 4.0;
        m.put(tank, Math.max(lo, Math.min(hi, rawRate)));
    }

    private static double clampBaseline(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Recover the temperament seed from a genome by reading the six single-writer
     * anchor fields — the inverse of {@link #fromTemperament}. Lets a freely-sampled
     * particular re-derive its drive temperament on reload from the genome that
     * already persists in the manifest (no extra persistence, no schema change).
     * A neutral/null genome → {@link TemperamentSeed#NEUTRAL}.
     */
    public static TemperamentSeed temperamentOf(GenomeProfile g) {
        if (g == null) return TemperamentSeed.NEUTRAL;
        double soc = invSens(g, "loneliness",   K_LONELINESS);
        double cur = invSens(g, "stagnation",   K_STAGNATION);
        double vig = invSens(g, "standing",     K_STANDING);
        double res = invSens(g, "restlessness", K_RESTLESS);
        // industry from momentum decay factor: rate = REF*(1 - ind*K) ⇒ ind = (1 - rate/REF)/K
        double momRate = g.decayRates() == null
            ? REFERENCE_DECAY : g.decayRates().getOrDefault("momentum", REFERENCE_DECAY);
        double ind = 0.5 + (1.0 - momRate / REFERENCE_DECAY) / K_MOMENTUM;
        // warmth from equanimity baseline: base = 0.5 + warmth_c*K ⇒ warmth = 0.5 + (base-0.5)/K
        double eqBase = g.baselines() == null
            ? 0.5 : g.baselines().getOrDefault("equanimity", 0.5);
        double wrm = 0.5 + (eqBase - 0.5) / K_EQUANIMITY;
        return new TemperamentSeed(soc, cur, vig, ind, res, wrm);
    }

    /** Invert a sensitivity anchor: sens = 1 + axis_c*K ⇒ axis = 0.5 + (sens-1)/K. */
    private static double invSens(GenomeProfile g, String tank, double k) {
        double sens = g.sensitivity() == null ? 1.0 : g.sensitivity().getOrDefault(tank, 1.0);
        return 0.5 + (sens - 1.0) / k;
    }

    /**
     * Per-archetype birth seeding for the runtime felt-state tanks — the
     * starting tone of the interior, applied once at soul birth (see
     * CompanionActor.initializeSoul). Keys are live VitalityState tanks; an empty
     * map (default/unknown) leaves {@link VitalityState#initial()} unchanged
     * (zero regression). A guardian wakes a touch more cautious, a diplomat
     * warmer, a steward steadier.
     */
    public static Map<String, Double> birthTankOverrides(String archetypeName) {
        if (archetypeName == null) return Map.of();
        return switch (archetypeName.toLowerCase()) {
            case "guardian" -> Map.of("confidence", 0.45, "equanimity", 0.30);
            case "diplomat" -> Map.of("rapport", 0.50, "equanimity", 0.25);
            case "steward"  -> Map.of("equanimity", 0.40, "rapport", 0.40);
            case "scholar"  -> Map.of("focus", 0.60);
            case "explorer" -> Map.of("focus", 0.40, "momentum", 0.20);
            case "artisan"  -> Map.of("focus", 0.55, "momentum", 0.15);
            default -> Map.of();
        };
    }
}
