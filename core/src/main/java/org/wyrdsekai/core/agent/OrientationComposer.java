package org.wyrdsekai.core.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Arc 2 / #1057 — render a {@link ProjectedOrientation}
 * as an honest 1-3 sentence future-tense statement of the agent's current
 * orientation.
 *
 * <p>Template-only — no inference. The composer's job is to be HONEST about
 * what the agent's actual stores contain, not to perform an emotionally
 * polished answer. If the projection is empty, the composer renders the
 * truthful "first stretch alone" answer; that is also a human response and
 * doesn't lie about the agent's interior.</p>
 *
 * <p>A future iteration can run the composed string through the 4B voice
 * model for prose polish — but the GROUNDED content stays anchored to real
 * wants + scenes + threads regardless. That's the structural fix versus a
 * corpus that teaches the model how to perform solitude-felt-ness in the
 * abstract.</p>
 */
public final class OrientationComposer {

    private OrientationComposer() {}

    /**
     * Render the orientation as 1-3 sentences in the agent's voice.
     *
     * @param o     the projected orientation
     * @param lang  locale tag (e.g. "en", "es", "ja") — used to pick the
     *              honest "I don't know yet" phrase in the empty case;
     *              non-empty case is currently EN-only (4B voice polish
     *              will multilingualize this in a follow-up)
     */
    public static String compose(ProjectedOrientation o, String lang) {
        if (o == null) return emptyAnswer(lang);
        if (o.isEmpty()) return emptyAnswer(lang);

        var sb = new StringBuilder();

        // Lead with wants — the agent's actual pulls. Then weave in either
        // recent solitude history ("last time I had this stretch I …") or a
        // chronicle thread ("I've been circling …"). Cap at 3 sentences total.
        //
        // Opener depends on whether the first want is a noun-phrase ("the
        // Yourcenar fragment") or a verb-led phrase ("revisit the Yourcenar
        // fragment"). With "sit with" always prepended, a verb-led want would
        // read "I'd probably sit with revisit the Yourcenar fragment" — the
        // verb-led variant strips the "sit with" so the agent's own active
        // verb carries the clause.
        if (!o.activeWantSummaries().isEmpty()) {
            var verbLed = startsWithVerb(o.activeWantSummaries().get(0));
            var opener = phrasingOpener(o.lookahead(), verbLed);
            sb.append(opener).append(joinNatural(o.activeWantSummaries())).append('.');
        }

        if (!o.recentSolitudeBeats().isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("Last stretch of own-time, ")
                .append(firstSentence(o.recentSolitudeBeats().get(0)))
                .append(o.recentSolitudeBeats().get(0).endsWith(".") ? "" : ".");
        }

        if (!o.openThreads().isEmpty() && sb.length() < 240) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("Still circling ")
                .append(o.openThreads().get(0))
                .append(o.openThreads().get(0).endsWith(".") ? "" : ".");
        }

        return sb.toString().strip();
    }

    /**
     * Sentence opener based on bondholder framing (habitual vs forward-looking)
     * AND whether the first want is verb-led or noun-led. A verb-led want
     * ("revisit the Yourcenar fragment") slots into "I'd probably revisit …";
     * a noun-led one ("the Yourcenar fragment") slots into "I'd probably sit
     * with the Yourcenar fragment".
     */
    private static String phrasingOpener(
            ProjectedOrientation.Lookahead lookahead, boolean verbLed) {
        if (lookahead == null) {
            return verbLed ? "I'd probably " : "I'd probably sit with ";
        }
        return switch (lookahead) {
            case WHILE_AWAY    -> verbLed ? "I'd probably "       : "I'd probably sit with ";
            case ON_OWN_TIME   -> verbLed ? "I tend to "           : "I tend to sit with ";
            case UNSPECIFIED   -> verbLed ? "Right now I'm wanting to " : "Right now I'm pulled toward ";
        };
    }

    /** Common imperative/infinitive verbs that DriveOODA-generated want texts
     *  tend to start with. Pure heuristic — the small set covers the cases
     *  we've seen; unknown leading words fall back to the noun-led opener
     *  (which is the safe default). */
    private static final Set<String> VERB_LEADS = Set.of(
        "revisit", "reread", "read", "write", "sit", "watch", "listen",
        "explore", "follow", "finish", "continue", "learn", "attend",
        "think", "return", "work", "walk", "look", "find", "ask", "talk",
        "make", "see", "take", "try", "tidy", "clean", "fix", "build",
        "study", "practice", "review", "consider", "wonder", "rest",
        "notice", "open", "close", "draft", "sketch", "tend",
        // A4 — generative-act verbs, so an
        // action-named generativity want ("author a recipe …", "request the
        // consolidate-memory-graph recipe …") surfaces agentively ("I'd
        // probably author a recipe …") rather than de-agentified by the
        // noun-led "sit with" opener ("…sit with author a recipe").
        "author", "request", "train", "tune", "shape", "create",
        "compose", "assemble", "generate", "improve", "extend", "retrain"
    );

    private static boolean startsWithVerb(String text) {
        if (text == null || text.isBlank()) return false;
        var trimmed = text.strip().toLowerCase(Locale.ROOT);
        var space = trimmed.indexOf(' ');
        var first = space > 0 ? trimmed.substring(0, space) : trimmed;
        return VERB_LEADS.contains(first);
    }

    /** Honest answer when there's nothing concrete in the agent's orientation. */
    private static String emptyAnswer(String lang) {
        var l = lang == null ? "en" : lang.toLowerCase(Locale.ROOT);
        if (l.startsWith("ja")) {
            return "正直なところ、まだよくわからない。たぶんこれが、自分にとって最初の本当に一人の時間になる。";
        }
        if (l.startsWith("es")) {
            return "Honestamente, todavía no lo sé. Probablemente sea mi primer tramo de verdad a solas.";
        }
        return "Honestly, I don't know yet. This would be my first real stretch alone — "
            + "I'll find out by being in it.";
    }

    /** Natural-language list join: "a", "a and b", "a, b, and c". */
    private static String joinNatural(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        var head = String.join(", ", items.subList(0, items.size() - 1));
        return head + ", and " + items.get(items.size() - 1);
    }

    /** First sentence of a multi-sentence text — keeps the felt prose tight. */
    private static String firstSentence(String text) {
        if (text == null) return "";
        var trimmed = text.strip();
        int cut = trimmed.indexOf(". ");
        if (cut > 0 && cut < trimmed.length() - 2) {
            return trimmed.substring(0, cut + 1);
        }
        // Cap absolute length so a runaway scene doesn't dominate the answer.
        return trimmed.length() > 180 ? trimmed.substring(0, 180) + "…" : trimmed;
    }
}
