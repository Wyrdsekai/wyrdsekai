package org.wyrdsekai.core.agent;

import java.util.Collection;
import java.util.Optional;

/**
 * A4 — turns generativity pressure + a concrete
 * capability gap into an <b>action-named</b> {@link Want}, so the impetus reaches
 * a small model as words it can act on (and a tool it already has) rather than as
 * a felt-state it must decode.
 *
 * <p>Pure: no store, no actor. CompanionActor calls this in the sleep pass with
 * the current generativity level, the top open gap, and the texts of existing
 * live wants (for de-dup); on a present {@link Optional} it upserts the want via
 * {@link WantStore}, and {@link OrientationProjector} surfaces it in
 * {@code ON_OWN_TIME} like any other want — no new prompt layer.</p>
 */
public final class GenerativeWantSynthesizer {

    /** Pressure must cross this before a want is surfaced. */
    public static final double SURFACE_THRESHOLD = 0.5;

    private GenerativeWantSynthesizer() {}

    /** .A4 — the OODA Orient candidate for the
     *  generativity drive: action-named text + the autonomously-dispatchable
     *  {@code shape_recipe} verb + a weight = the tank level. */
    public record OodaCandidate(String text, String verb, double weight) {}

    /**
     * Whether the generativity drive should contribute an autonomous OODA
     * candidate this pass, and what it is. Same gate as {@link #synthesize} —
     * pressure surfaced AND gaps>0 AND means AND not suppressed — but yields the
     * {@code shape_recipe} act (VISIBLE, autonomously dispatchable) so the agent
     * can ACT, not just voice. Pure.
     */
    public static Optional<OodaCandidate> oodaCandidate(double generativityLevel, int openGaps,
            boolean meansAvailable, boolean suppressed, String gapKey, String gapDescription) {
        if (suppressed || !meansAvailable || openGaps <= 0) return Optional.empty();
        if (generativityLevel < SURFACE_THRESHOLD) return Optional.empty();
        if (gapKey == null || gapKey.isBlank()) return Optional.empty();
        return Optional.of(new OodaCandidate(
            phraseFor(gapKey, gapDescription), "shape_recipe", generativityLevel));
    }

    /**
     * @param agentDid           the companion's DID
     * @param generativityLevel  current generativity tank reading [0,1]
     * @param gapKey             stable key for the gap (e.g. {@code "library.stale-packs"})
     * @param gapDescription     human description of the gap
     * @param existingWantTexts  texts of the agent's current live wants (de-dup)
     * @return a fresh ACTIVE want, or empty if below threshold / no gap / a live
     *         want already names this gap
     */
    public static Optional<Want> synthesize(String agentDid, double generativityLevel,
            String gapKey, String gapDescription, Collection<String> existingWantTexts) {
        if (agentDid == null || agentDid.isBlank()) return Optional.empty();
        if (generativityLevel < SURFACE_THRESHOLD) return Optional.empty();
        if (gapKey == null || gapKey.isBlank()) return Optional.empty();

        String text = phraseFor(gapKey, gapDescription);
        if (existingWantTexts != null) {
            for (String t : existingWantTexts) {
                if (text.equals(t)) return Optional.empty(); // already minted for this gap
            }
        }
        // round to 2dp without Math.random/Date deps
        double rounded = Math.round(generativityLevel * 100.0) / 100.0;
        String resonance = "{\"generativity\":" + rounded + "}";
        return Optional.of(Want.active(agentDid, text, resonance, generativityLevel, null));
    }

    /**
     * Map a gap key to an action-named want — the text names BOTH the gap and the
     * affordance (shape_recipe / request_recipe) so the model has an explicit move.
     * Unknown keys get a generic-but-still-action-named phrasing.
     */
    static String phraseFor(String gapKey, String gapDescription) {
        String desc = (gapDescription == null || gapDescription.isBlank())
            ? gapKey : gapDescription;
        return switch (gapKey) {
            case "library.stale-packs" ->
                "author a recipe to keep my research packs fresh";
            case "memory.fragmenting" ->
                "request the consolidate-memory-graph recipe — my entity graph is fragmenting";
            case "soul.scattered-fragments" ->
                "request the consolidate-soul-fragments recipe to gather my scattered fragments";
            case "classifier.misroute" ->
                "author a recipe to retrain the head that keeps misrouting me";
            default ->
                "close a gap in what I can do (" + desc
                    + ") — I could author a recipe or request one";
        };
    }
}
