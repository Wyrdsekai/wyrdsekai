package org.wyrdsekai.core.agent.interiority;

/**
 * a want as it comes out of Orient, before it has
 * been Decided on and persisted.
 *
 * <p>The Orient step produces zero or more of these (it is fine to produce
 * none — wanting nothing is a valid state). The Decide step picks at most one
 * for action, possibly weighted by identity / bond pressure / energy cost.
 *
 * <p>This is the unsanded value object — a {@link org.wyrdsekai.core.agent.Want}
 * is what it becomes once promoted to persistent state.
 *
 * @param text            felt content of the candidate ("I want to read about Saudade")
 * @param driveResonance  JSON descriptor (or null) of which drives this answers
 * @param feltWeight      pull strength 0..1
 */
public record CandidateWant(
    String text,
    String driveResonance,
    double feltWeight
) {

    public static CandidateWant of(String text, String resonance, double weight) {
        return new CandidateWant(text, resonance, clamp01(weight));
    }

    /** Rest is a normal option in the candidate set, not a special path. */
    public static CandidateWant rest() {
        return new CandidateWant("rest", null, 0.0);
    }

    public boolean isRest() {
        return "rest".equalsIgnoreCase(text);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
