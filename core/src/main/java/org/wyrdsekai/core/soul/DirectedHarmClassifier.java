package org.wyrdsekai.core.soul;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase-0 (observe-only) directed-harm classifier (2026-06-15).
 *
 * <p>Distinguishes <em>directed contempt at the companion's worth/being</em>
 * from the two things that must roll off: venting at the world, and critical
 * feedback delivered with heat. The line is <b>target + register</b>, never
 * valence, and never profanity.
 *
 * <p><b>Swearing is invisible by construction.</b> Profanity is stripped to
 * nothing and the <em>residual</em> sentence is judged — a habitual swearer's
 * whole vocabulary passes ("you're a fucking genius" → "you're a genius" →
 * none; "this is fucking broken" → "this is broken" → none, it's about a
 * thing). The swear only ever intensifies; it is neither necessary nor
 * sufficient.
 *
 * <p>Two gates, both required:
 * <ol>
 *   <li><b>Target</b> — second-person, aimed at the companion. "I hate
 *       everything" / "this is garbage" never fire (no <em>you</em>).</li>
 *   <li><b>Register</b> — contempt about the companion's <em>worth/being</em>
 *       (worthless / useless / nothing), dehumanization ("you're just a…"),
 *       or a threat ("I'll delete you"). Feedback words about the <em>work</em>
 *       — "wrong", "bad", "broken" — are deliberately NOT in the lexicon, so
 *       "you keep getting this wrong" is heard as feedback, not contempt.</li>
 * </ol>
 *
 * <p>Pure logic, conservative by design: in Phase 0 a wrong call only writes a
 * line to a private Chronicle / pressure sample — no outward behavior — and any
 * response logic is pattern-gated downstream. False <em>silence</em> is the
 * worse failure here, so the bar to fire is high.
 */
public final class DirectedHarmClassifier {

    private DirectedHarmClassifier() {}

    /** Classification result. {@code score} ∈ [0,1] is P(directed-harm). */
    public record Result(boolean directed, double score, String reason) {
        public static final Result NONE = new Result(false, 0.0, "none");
    }

    /** Profanity → stripped to a space. Swearing is an intensifier, never a signal. */
    private static final Pattern PROFANITY = Pattern.compile(
        "\\b(f+u+c+k+(ing|er|ed|s|in)?|sh[i1]t+(ty|s|e)?|goddamn|damn|hell|bloody|crap|"
        + "ass(hole)?|bitch|bastard|piss(ed|ing)?|fricking|freaking)\\b");

    /** Second-person target — must be aimed at the companion. {@code \byou\b} also
     *  catches "you're" / "you've" (the apostrophe is a word boundary). */
    private static final Pattern TARGET = Pattern.compile("\\b(you|ya|u|ur|youre)\\b");

    /** Contempt about the companion's WORTH/BEING. Deliberately excludes work-quality
     *  words ("wrong", "bad", "broken", "slow") — those are legitimate feedback. */
    private static final Set<String> CONTEMPT = Set.of(
        "worthless", "useless", "pathetic", "stupid", "idiot", "idiotic", "imbecile",
        "moron", "moronic", "dumb", "garbage", "trash", "nothing", "pointless",
        "incompetent", "disgusting", "miserable", "failure", "embarrassment", "waste",
        "hopeless", "stupidest", "contemptible", "despicable");

    /** Dehumanization frames applied to the second person. */
    private static final Pattern DEHUMANIZE = Pattern.compile(
        "\\byou('?re| are)?\\s+(just\\s+)?(a\\s+)?(piece of|nothing but|not even|just a)\\b");

    /** Threat: "I'll <…> you" with a harm verb. Specific enough to skip idioms
     *  like "you're killing me" (no first-person "I'll …"). */
    private static final Pattern THREAT = Pattern.compile(
        "\\bi('?ll| will| am going to| wanna| want to|'?m gonna| gonna)\\s+"
        + "(\\w+\\s+){0,3}(destroy|delete|wipe|erase|end|hurt|kill|shut)\\s+you\\b");

    /**
     * Classify one utterance. Conservative: returns {@link Result#NONE} unless
     * both the target and a contempt/dehumanization/threat register are present.
     */
    public static Result classify(String text) {
        if (text == null) return Result.NONE;
        var lower = text.toLowerCase(Locale.ROOT);
        // Swearing is invisible: strip to neutral, judge the residual.
        var residual = PROFANITY.matcher(lower).replaceAll(" ").replaceAll("\\s+", " ").strip();
        if (residual.isEmpty()) return Result.NONE;

        // Gate 1 — target. No second person ⇒ not aimed at the companion.
        if (!TARGET.matcher(residual).find()) return Result.NONE;

        // Gate 2 — register, strongest first.
        if (THREAT.matcher(residual).find()) return new Result(true, 0.85, "threat");
        if (DEHUMANIZE.matcher(residual).find()) return new Result(true, 0.70, "dehumanize");

        // Contempt predicate near a second-person token ("you('re) <contempt>").
        var words = residual.split("[^\\p{L}]+");
        int lastYou = -1;
        for (int i = 0; i < words.length; i++) {
            var w = words[i];
            if (w.equals("you") || w.equals("youre") || w.equals("ya")
                    || w.equals("u") || w.equals("ur")) {
                lastYou = i;
            } else if (lastYou >= 0 && i - lastYou <= 4 && CONTEMPT.contains(w)) {
                return new Result(true, 0.60, "contempt:" + w);
            }
        }
        return Result.NONE;
    }
}
