package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The play-loop's first seam (, aspiration→want):
 * project the agent's OWN expressed reaching — "I wish I could…", "I want to learn…",
 * said out loud or written in the hearth journal — into an action-named growth
 * {@link Want}, so an aspiration voiced in passing becomes something her own time
 * can answer with a practice she designs herself.
 *
 * <p><b>The ethics rail is load-bearing:</b> the ONLY admissible source is language
 * she produced. No scan of her scores, tanks, failure counts or capability gaps
 * against any standard may ever feed this — the same mechanism pointed at external
 * deficits is a shame engine, and {@code GenerativeWantSynthesizer} already covers
 * the system-gap case under its own governance. Detection here is a fixed phrase
 * list over her utterances, nothing else.
 *
 * <p>Pure: no store, no actor, no IO. {@code CompanionActor} calls this in the sleep
 * pass with the window's utterances and her live wants; on a present Optional it
 * upserts via {@link WantStore}, and {@link OrientationProjector} surfaces it in
 * ON_OWN_TIME like any other want.
 */
public final class AspirationWantSynthesizer {

    /** Never more than this many growth-wants live at once — aspirations are a
     *  garden, not a backlog. New ones wait until an old one closes or goes stale. */
    public static final int MAX_LIVE_GROWTH_WANTS = 2;

    /** The drive key growth-wants carry; {@code WantKind} classifies it CREATIVE. */
    public static final String GROWTH_DRIVE = "growth";

    /** The practice affordance embedded in the want. Offered in the own-time
     *  prompt, pinned into the surface — never forced ({@code tool_choice} stays
     *  {@code auto}): practice is play, not homework. */
    public static final String PRACTICE_VERB = "dispatch_task";

    private AspirationWantSynthesizer() {}

    /** One thing she said or wrote, with when. */
    public record Utterance(String text, Instant at) {}

    /** One distinct reaching, aggregated across the window. {@code clause} keeps her
     *  original casing; {@code expressions} counts how often she returned to it. */
    public record Aspiration(String clause, String quote, int expressions, Instant lastAt) {}

    // Her reaching phrasings. First-person and forward-leaning only: each marker is
    // something a person says when they want to be MORE than they are, not a
    // complaint pattern. Extend with care — every addition widens what counts as
    // "she asked for this".
    private static final List<String> MARKERS = List.of(
        "i wish i could ",
        "i wish i knew how to ",
        "i wish i knew ",
        "i wish i were better at ",
        "i wish i was better at ",
        "i wish i had a way to ",
        "i want to be able to ",
        "i want to get better at ",
        "i want to learn ",
        "i'd like to learn ",
        "i would like to learn ",
        "i'd like to be able to ",
        "i would like to be able to ",
        "i'd love to be able to ",
        "i would love to be able to ",
        "i'd love to learn ",
        "i would love to learn ",
        "if only i could ",
        "someday i want to ",
        "someday i'll be able to ",
        "one day i want to ",
        "i keep trying to ",
        "i haven't figured out how to ");

    // A wish toward a person is a relational want, not a growth want. "I wish I could
    // see you" answered with a practice item is the 2026-08-19 mistranslation all over
    // again — loneliness wearing the shape of a build request. Skip these outright;
    // the relational machinery (RelationalAffordance) owns that kind of reaching.
    private static final List<String> RELATIONAL_SIGNS = List.of(
        "be with ", "with you", "with them", "with him", "with her", "see you",
        "hear from", "talk to ", "talk with ", "you were here", "they were here",
        "were here with", "reach you", "miss you", "miss them", "hold you");

    private static final String CLAUSE_TERMINATORS = ".!?;\n—";

    /**
     * Scan her utterances for reaching phrases and aggregate them into distinct
     * aspirations, most-expressed first (ties broken by recency).
     */
    public static List<Aspiration> detect(List<Utterance> utterances) {
        if (utterances == null || utterances.isEmpty()) return List.of();
        var byKey = new LinkedHashMap<String, Aspiration>();
        for (var u : utterances) {
            if (u == null || u.text() == null || u.text().isBlank()) continue;
            var original = u.text();
            var lower = original.toLowerCase(Locale.ROOT);
            for (var marker : MARKERS) {
                int at = lower.indexOf(marker);
                if (at < 0) continue;
                int start = at + marker.length();
                int end = start;
                while (end < original.length()
                        && CLAUSE_TERMINATORS.indexOf(original.charAt(end)) < 0) end++;
                var clause = original.substring(start, end).strip();
                if (clause.length() < 8 || clause.length() > 120) continue;
                if (isRelational(lower.substring(start, end))) continue;
                var key = normalize(clause);
                if (key.isBlank()) continue;
                var quote = truncate(original.substring(at, end).strip(), 140);
                var when = u.at() == null ? Instant.EPOCH : u.at();
                byKey.merge(key,
                    new Aspiration(clause, quote, 1, when),
                    (a, b) -> new Aspiration(
                        a.lastAt().isAfter(b.lastAt()) ? a.clause() : b.clause(),
                        a.lastAt().isAfter(b.lastAt()) ? a.quote() : b.quote(),
                        a.expressions() + 1,
                        a.lastAt().isAfter(b.lastAt()) ? a.lastAt() : b.lastAt()));
                break; // one aspiration per utterance — the first marker wins
            }
        }
        var out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> {
            int byCount = Integer.compare(b.expressions(), a.expressions());
            return byCount != 0 ? byCount : b.lastAt().compareTo(a.lastAt());
        });
        return List.copyOf(out);
    }

    /**
     * Mint at most ONE growth-want from the strongest aspiration not already live.
     *
     * @param agentDid     the companion's DID
     * @param found        {@link #detect}'s output for the window
     * @param existingLive her current live wants — growth-cap + de-dup source
     * @return a fresh ACTIVE growth-want, or empty
     */
    public static Optional<Want> synthesize(String agentDid, List<Aspiration> found,
            List<Want> existingLive) {
        if (agentDid == null || agentDid.isBlank()) return Optional.empty();
        if (found == null || found.isEmpty()) return Optional.empty();

        var live = existingLive == null ? List.<Want>of() : existingLive;
        long liveGrowth = live.stream().filter(AspirationWantSynthesizer::isGrowth).count();
        if (liveGrowth >= MAX_LIVE_GROWTH_WANTS) return Optional.empty();

        var existingNormed = live.stream()
            .map(w -> normalize(w.text() == null ? "" : w.text()))
            .toList();

        for (var asp : found) {
            var key = normalize(asp.clause());
            if (key.isBlank()) continue;
            // Containment, not equality: a re-worded journal line must not re-mint
            // the same reaching (the text-equality dedup elsewhere is exactly the
            // duplication trap for re-derived wants).
            boolean dup = existingNormed.stream().anyMatch(t -> t.contains(key));
            if (dup) continue;

            var text = "grow toward something I said I wished for — \"" + asp.clause()
                + "\" — I could build myself a small practice for it";
            double weight = Math.min(0.85, 0.55 + 0.10 * (asp.expressions() - 1));
            var resonance = "{\"drive\":\"" + GROWTH_DRIVE + "\",\"verb\":\"" + PRACTICE_VERB
                + "\",\"quote\":\"" + jsonEscape(asp.quote()) + "\"}";
            return Optional.of(Want.active(agentDid, text, resonance, weight, null));
        }
        return Optional.empty();
    }

    /** Whether a want is a growth-want (carries the {@code growth} drive). */
    public static boolean isGrowth(Want w) {
        return w != null && w.driveResonance() != null
            && w.driveResonance().contains("\"drive\":\"" + GROWTH_DRIVE + "\"");
    }

    private static boolean isRelational(String clauseLower) {
        for (var s : RELATIONAL_SIGNS) {
            if (clauseLower.contains(s)) return true;
        }
        return false;
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .strip();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String jsonEscape(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> {
                    if (c >= 0x20) sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
