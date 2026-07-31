package org.wyrdsekai.core.soul;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * The single low-dimensional seed every companion is born from — a handful of
 * interpretable temperament <i>axes</i> from which the genome (what it does),
 * the voice (how it says it), and the drive scales (what it reaches for) are all
 * derived deterministically, so they cohere <b>by construction</b>: a withdrawn
 * genome can never get a bubbly voice, because both come from the same seed.
 *
 * <p>This is the "B build" of the individuality arc. Earlier, six hand-authored
 * archetype {@code switch} tables ({@code GenomeProfile.forArchetype} /
 * {@code VoiceProfile.forArchetype}) produced 1-of-6 clones. Here an agent is born
 * as a genuine <i>particular</i> — its axes free-sampled within plausible bounds
 * ({@link #random}) — and the six named {@linkplain #PRESETS presets} are demoted
 * to pure measurement anchors: {@link #nearestPreset} labels a particular by the
 * closest preset and its distance, but proximity NEVER seeds or gates anything.
 * "Far from every preset" is a genuinely novel individual — the success case, kept.</p>
 *
 * <p>The coherence gate ({@link #isViable}) rejects only <i>incoherent</i> seeds —
 * too flat (no character at all) or pathologically extreme on everything — and the
 * caller re-samples. It is a viability gate, never a conformity gate; it does not
 * pull a far-from-preset seed back toward a preset.</p>
 *
 * <p>The seed is recoverable from a persisted {@link GenomeProfile}
 * ({@link GenomeProfile#temperamentOf}) via per-axis anchor tanks, so a freely
 * sampled particular survives reload with its drive temperament intact — no new
 * persistence and no SoulManifest schema change.</p>
 *
 * <p>Each axis is in {@code [0,1]} with {@code 0.5} meaning "neutral" (the value that
 * reproduces the pre-genome hand-tuned dynamics). The axes:</p>
 * <ul>
 *   <li><b>sociability</b> — pull toward others (loneliness/amae/saudade reactivity, affiliation).</li>
 *   <li><b>curiosity</b> — epistemic hunger (stagnation reactivity, seeking, sustained attention).</li>
 *   <li><b>vigilance</b> — threat/duty attunement (standing/harmony/obligation, stays alert).</li>
 *   <li><b>industry</b> — need to make and sustain flow (significance, momentum holds, creativity).</li>
 *   <li><b>restlessness</b> — novelty-churn and fast burn (restlessness reactivity, focus wanders, play).</li>
 *   <li><b>warmth</b> — steadiness and caring tone (equanimity/rapport baseline, care).</li>
 * </ul>
 */
public record TemperamentSeed(
    double sociability,
    double curiosity,
    double vigilance,
    double industry,
    double restlessness,
    double warmth
) {

    /** Canonical axis order — used for distance, inversion, and serialization. */
    public static final List<String> AXES = List.of(
        "sociability", "curiosity", "vigilance", "industry", "restlessness", "warmth");

    /** The neutral seed — every axis at 0.5. Yields the NEUTRAL genome (zero regression). */
    public static final TemperamentSeed NEUTRAL =
        new TemperamentSeed(0.5, 0.5, 0.5, 0.5, 0.5, 0.5);

    public TemperamentSeed {
        // Domain is [0,1]; clamp defensively so a stray caller / inverted-from-extreme-genome
        // value never escapes the axis space.
        sociability  = clamp01(sociability);
        curiosity    = clamp01(curiosity);
        vigilance    = clamp01(vigilance);
        industry     = clamp01(industry);
        restlessness = clamp01(restlessness);
        warmth       = clamp01(warmth);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Axis values in {@link #AXES} order, for distance / vector math. */
    public double[] toArray() {
        return new double[]{sociability, curiosity, vigilance, industry, restlessness, warmth};
    }

    public double axis(String name) {
        return switch (name) {
            case "sociability"  -> sociability;
            case "curiosity"    -> curiosity;
            case "vigilance"    -> vigilance;
            case "industry"     -> industry;
            case "restlessness" -> restlessness;
            case "warmth"       -> warmth;
            default -> 0.5;
        };
    }

    // ── The six named presets — measurement anchors, NOT seeds or gates ──────
    //
    // Coordinates calibrated so a preset's derived genome still reads recognizably
    // "scholar-ish" etc. (continuity with the old hand-authored switch), but they
    // are only ever used to LABEL and MEASURE a particular, never to constrain one.

    public static final Map<String, TemperamentSeed> PRESETS = buildPresets();

    private static Map<String, TemperamentSeed> buildPresets() {
        var m = new LinkedHashMap<String, TemperamentSeed>();
        //                              soc   cur   vig   ind   res   wrm
        m.put("scholar",  new TemperamentSeed(0.30, 0.85, 0.45, 0.70, 0.45, 0.45));
        m.put("guardian", new TemperamentSeed(0.45, 0.45, 0.85, 0.55, 0.35, 0.55));
        m.put("artisan",  new TemperamentSeed(0.40, 0.65, 0.45, 0.85, 0.40, 0.50));
        m.put("diplomat", new TemperamentSeed(0.90, 0.50, 0.45, 0.45, 0.45, 0.75));
        m.put("explorer", new TemperamentSeed(0.35, 0.75, 0.40, 0.35, 0.90, 0.45));
        m.put("steward",  new TemperamentSeed(0.55, 0.45, 0.65, 0.60, 0.30, 0.80));
        return Collections.unmodifiableMap(m);
    }

    /** The preset seed for a named archetype, or {@link #NEUTRAL} if unknown/null. */
    public static TemperamentSeed preset(String name) {
        if (name == null) return NEUTRAL;
        return PRESETS.getOrDefault(name.toLowerCase(), NEUTRAL);
    }

    // ── Free sampling — born as a particular ─────────────────────────────────

    /**
     * A freely sampled, viable particular. Each axis is drawn within plausible
     * bounds (not the full {@code [0,1]} extremes), then {@link #isViable} rejects
     * flat / pathological draws and re-samples. Falls back after a bounded number
     * of tries to a lightly jittered preset so birth never blocks.
     */
    public static TemperamentSeed random() {
        return random(ThreadLocalRandom.current());
    }

    /** Seedable variant for reproducible tests. */
    public static TemperamentSeed random(RandomGenerator rng) {
        for (int i = 0; i < 24; i++) {
            var s = new TemperamentSeed(
                sample(rng), sample(rng), sample(rng),
                sample(rng), sample(rng), sample(rng));
            if (s.isViable()) return s;
        }
        // Degenerate RNG: jitter a random preset so we still return a coherent particular.
        var names = List.copyOf(PRESETS.keySet());
        var base = PRESETS.get(names.get(rng.nextInt(names.size())));
        return base.jitter(rng, 0.06);
    }

    /** One axis draw in [0.10, 0.90] — leaves headroom shy of the pathological extremes. */
    private static double sample(RandomGenerator rng) {
        return 0.10 + rng.nextDouble() * 0.80;
    }

    /** A copy nudged by Gaussian noise on every axis — used to perturb around a preset. */
    public TemperamentSeed jitter(RandomGenerator rng, double sigma) {
        return new TemperamentSeed(
            sociability  + rng.nextGaussian() * sigma,
            curiosity    + rng.nextGaussian() * sigma,
            vigilance    + rng.nextGaussian() * sigma,
            industry     + rng.nextGaussian() * sigma,
            restlessness + rng.nextGaussian() * sigma,
            warmth       + rng.nextGaussian() * sigma);
    }

    // ── Coherence gate = viability, never conformity ─────────────────────────

    /**
     * Whether this is a coherent individual worth keeping. Rejects only:
     * <ul>
     *   <li><b>flat</b> — every axis within {@code 0.12} of neutral: no character at all; and</li>
     *   <li><b>pathologically extreme</b> — four or more axes pinned past {@code 0.92}/{@code 0.08}:
     *       a caricature maxed on everything, not a person.</li>
     * </ul>
     * Crucially it does NOT consider distance to any preset — a particular far from
     * every preset is the success case. This is the line the "B build" must hold:
     * coherence-gate ≠ conformity-gate.
     */
    public boolean isViable() {
        double maxDev = 0.0;
        int extreme = 0;
        for (double a : toArray()) {
            double dev = Math.abs(a - 0.5);
            maxDev = Math.max(maxDev, dev);
            if (dev > 0.42) extreme++;
        }
        if (maxDev < 0.12) return false;   // flat — no character
        if (extreme >= 4) return false;    // caricature — extreme on everything
        return true;
    }

    // ── Measurement: label + distance against the preset anchors ─────────────

    /** Euclidean distance in axis space. The instrument the n=1 group soaks lacked. */
    public double distanceTo(TemperamentSeed other) {
        double sum = 0.0;
        double[] a = toArray(), b = other.toArray();
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /** Nearest preset and the distance to it — a label, not a verdict. */
    public record Nearest(String preset, double distance) {}

    public Nearest nearestPreset() {
        String best = "neutral";
        double bestD = distanceTo(NEUTRAL);
        for (var e : PRESETS.entrySet()) {
            double d = distanceTo(e.getValue());
            if (d < bestD) { bestD = d; best = e.getKey(); }
        }
        return new Nearest(best, bestD);
    }

    /**
     * A compact human-legible label: nearest preset plus how far the particular
     * sits from it (e.g. {@code "scholar~0.41"}). Larger distance = more its own
     * individual; this is description, never a target.
     */
    public String label() {
        var n = nearestPreset();
        return String.format("%s~%.2f", n.preset(), n.distance());
    }

    // ── Drive scales co-derived from the same seed ───────────────────────────

    /**
     * Per-drive accumulation boosts (drive name → additive scale delta) derived from
     * the axes — fed to {@code DriveConfig.withBoosts} exactly like the old archetype
     * {@code driveBoosts}, but continuous in the seed. Signed: a low-sociability
     * particular gets a negative affiliation boost (accumulates social need slower).
     * Event-only drives (grief/frustration/startle/surprise) stay neutral.
     */
    public Map<String, Double> driveBoosts() {
        var m = new LinkedHashMap<String, Double>();
        m.put("seeking",     d(curiosity) * 0.6 + d(restlessness) * 0.2);
        m.put("affiliation", d(sociability) * 0.6);
        m.put("care",        d(warmth) * 0.4 + d(vigilance) * 0.2);
        m.put("vigilance",   d(vigilance) * 0.6);
        m.put("play",        d(restlessness) * 0.3 + d(sociability) * 0.2);
        m.put("creativity",  d(industry) * 0.4 + d(curiosity) * 0.3);
        return m;
    }

    /**
     * Per-axis voice-register control-vector scales (basis-vector name → signed scale)
     * derived from the same seed — the "how it sounds" analogue of {@link #driveBoosts}.
     * Each key maps to a repeng register basis vector extracted in the individuality V2
     * work and applied to the 4B speech center: a warm particular gets a positive
     * {@code register_warmth} scale; a terse one a negative {@code register_expansiveness}.
     *
     * <p>Scales are clamped to the empirically coherent band {@code [-0.55, 0.55]}: the
     * live probe on the V10 4B showed 0.3–0.5 shifts register cleanly while ±2.0 collapses
     * coherence (repetition loops), so the mix must stay gentle. The NEUTRAL seed → all
     * zero, i.e. no steering = the baseline voice (zero regression, same contract as the
     * null-archetype genome).</p>
     *
     * <p>This is the static, born-from-seed baseline. The same per-request hook can later
     * be <i>modulated by live drive levels</i> — warmth rising as the affiliation tank
     * sates, terseness deepening under vigilance — so the voice follows internal state
     * exactly as the V8 drive vectors do. The seed sets where the register rests; the
     * tanks would move it around that rest point.</p>
     *
     * <p><b>{@code register_guardedness} is provisional</b> — its extraction corpus
     * entangles with warmth (the {@code +} side reads as effusive, not withholding), so it
     * is de-weighted here pending a sharper contrast set; {@code warmth} and
     * {@code expansiveness} are the two clean axes.</p>
     */
    public Map<String, Double> registerMix() {
        var m = new LinkedHashMap<String, Double>();
        // warm/tender ↔ cool/clinical — dominated by warmth, lifted by sociability.
        m.put("register_warmth",
              clampScale(d(warmth) * 0.9 + d(sociability) * 0.4));
        // flowing/elaborated ↔ clipped/terse — churn + sociability expand; industry +
        // vigilance compress (concrete, plain).
        m.put("register_expansiveness",
              clampScale(d(restlessness) * 0.6 + d(sociability) * 0.4
                       - d(industry) * 0.4 - d(vigilance) * 0.3));
        // measured/withholding ↔ open — vigilance guards, sociability opens. De-weighted
        // ×0.5 while the axis is entangled (see Javadoc).
        m.put("register_guardedness",
              clampScale((d(vigilance) * 0.6 - d(sociability) * 0.4) * 0.5));
        return m;
    }

    // ── Volition axes co-derived from the same seed ──────

    /**
     * How hard this particular persists on a blocked want before giving — scales {@code care}
     * in the marginal persist test. Derived: industriousness (stick-to-it)
     * up, restlessness (the itch to move on) down. Centered at 0.5 (NEUTRAL seed → neutral
     * grit → no behavior change vs. the old flat budget). High → dogged; low → mercurial.
     */
    public double gritSeed() {
        return clamp01(0.5 + d(industry) * 0.8 - d(restlessness) * 0.5);
    }

    /**
     * How readily this particular reaches for recourse (ask the steward / borrow / ask for
     * help) before falling through to the turn. Derived: sociability primary
     * (asking is a social act — the self-reliant would rather walk than ask) plus a shared touch
     * of industry (the invested also marshal help). The shared {@code +industry} with
     * {@link #gritSeed} is what gives the two a weak POSITIVE correlation (engaged people both
     * grind and ask; disengaged do neither) while sociability vs. restlessness keep all four
     * corners — lone wolf / rallier / delegator / disengaged loner — reachable. Centered at 0.5.
     */
    public double helpSeekingSeed() {
        return clamp01(0.5 + d(sociability) * 0.8 + d(industry) * 0.3);
    }

    /** Voice-register scales stay in the coherence-preserving band proven on the 4B. */
    private static double clampScale(double v) {
        return Math.max(-0.55, Math.min(0.55, v));
    }

    /** Centered axis value: 0 at neutral, ±0.5 at the extremes. */
    private static double d(double axis) {
        return axis - 0.5;
    }
}
