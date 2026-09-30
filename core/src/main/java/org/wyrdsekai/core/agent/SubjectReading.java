package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.agent.interiority.DriveOODA;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * One reading on part of a subject a companion said she would learn: what was read, and where,
 * or that nothing was found. Pure: the library and web legs are passed in, so the rule for what
 * counts as reading is testable without an actor.
 *
 * <p>What counts as read is only what cleared the library's relevance floor, or what the web
 * returned. The general library search also keeps what a rephrasing found by keyword alone, her
 * own earlier findings, and granted Study hits with no floor, and its result text said "found"
 * for all of them; the first reading path took that text as reading, and a search that found
 * nothing, or only old books on buildings for "transformer architecture", still marked the part
 * as read and closed the want (review of 2026-09-22).
 */
public final class SubjectReading {

    private SubjectReading() {}

    /** Where a passage came from. */
    public enum Source { LIBRARY, WEB }

    /** One passage she read: its title, the start of its text, and where it came from. */
    public record Passage(String title, String text, Source source) {}

    /**
     * What one reading came to.
     *
     * @param passages what she read, empty when nothing was found
     * @param source   where it was found, or null when nothing was
     * @param why      when nothing was found, where she looked
     */
    public record Result(List<Passage> passages, Source source, String why) {
        public Result {
            passages = passages == null ? List.of() : List.copyOf(passages);
        }

        public boolean found() {
            return !passages.isEmpty();
        }

        static Result nothing(String why) {
            return new Result(List.of(), null, why);
        }
    }

    /** At most this many passages are kept from one reading. */
    public static final int MAX_PASSAGES = 4;

    /**
     * Read on {@code part} of {@code subject}: the library first, and the web only when the library
     * had nothing and the web is open to her ({@code web} null when it is not: the bondholder's
     * posture, her tier, or no search service). Both are asked the same thing: the part with the
     * rest of her subject beside it ({@link #queryFor}).
     */
    public static Result read(String part, String subject, Function<String, List<Passage>> library,
            Function<String, List<Passage>> web) {
        if (part == null || part.isBlank()) return Result.nothing("there was nothing to look for");
        var query = queryFor(part, subject);
        // With the other parts in the search, what comes back must name this one; a part searched
        // alone is left to the relevance floor, as before.
        var mustName = query.equals(part.strip()) ? null : part;
        var fromLibrary = take(library, query, mustName);
        if (!fromLibrary.isEmpty()) return new Result(fromLibrary, Source.LIBRARY, null);
        if (web == null) return Result.nothing("the library had nothing on it");
        var fromWeb = take(web, query, mustName);
        if (!fromWeb.isEmpty()) return new Result(fromWeb, Source.WEB, null);
        return Result.nothing("neither the library nor the web had anything on it");
    }

    /**
     * What is searched for one part: the part, then the other parts of her subject. A part alone
     * can name something else: on 2026-09-23 a reading on "transformers" (of "transformers,
     * attention mechanisms, diffusion models") searched that one word, found textbooks on
     * electricity from the 1900s, and kept them as what she read on it. Beside the rest of her
     * subject, the search and the relevance floor's ranking are about the topic she meant. A
     * subject of one part is searched as it is.
     */
    static String queryFor(String part, String subject) {
        var p = part.strip();
        var rest = AspirationWantSynthesizer.partsOf(subject).stream()
            .filter(other -> !other.equalsIgnoreCase(p))
            .toList();
        return rest.isEmpty() ? p : p + " (" + String.join(", ", rest) + ")";
    }

    /** The titles she read, for the want's record: short, and only what was there. */
    public static String note(Result r) {
        if (r == null || !r.found()) return null;
        var titles = new ArrayList<String>();
        for (var p : r.passages()) {
            var t = p.title() == null || p.title().isBlank() ? firstWords(p.text(), 8) : p.title().strip();
            if (!t.isBlank() && titles.stream().noneMatch(x -> x.equalsIgnoreCase(t))) titles.add(t);
        }
        var where = r.source() == Source.WEB ? " (on the web)" : " (in the library)";
        return titles.isEmpty() ? null : String.join("; ", titles) + where;
    }

    /** What she keeps in her memory about one reading. */
    public static String memoryEntry(String subject, String part, Result r) {
        if (r == null || !r.found()) return null;
        var sb = new StringBuilder("What I read on \"").append(part).append("\" (for what I said I would learn: ")
            .append(truncate(subject, 120)).append("), ")
            .append(r.source() == Source.WEB ? "on the web" : "in the library").append(':');
        int i = 1;
        for (var p : r.passages()) {
            sb.append(' ').append(i++).append(". ");
            if (p.title() != null && !p.title().isBlank()) sb.append(p.title().strip()).append(": ");
            sb.append(truncate(p.text() == null ? "" : p.text().strip().replaceAll("\\s+", " "), 160));
        }
        return truncate(sb.toString(), 800);
    }

    private static List<Passage> take(Function<String, List<Passage>> leg, String query, String mustName) {
        if (leg == null) return List.of();
        List<Passage> got;
        try {
            got = leg.apply(query);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (got == null) return List.of();
        var out = new ArrayList<Passage>();
        for (var p : got) {
            if (p == null) continue;
            boolean hasText = p.text() != null && !p.text().isBlank();
            boolean hasTitle = p.title() != null && !p.title().isBlank();
            if (!hasText && !hasTitle) continue;
            if (mustName != null && !namesThePart(p, mustName)) continue;
            out.add(p);
            if (out.size() == MAX_PASSAGES) break;
        }
        return out;
    }

    /**
     * The passage is about this part: its title or text carries one of the part's topic words
     * (four letters or more, singular or plural, not a word that only names a kind of thing).
     * The search carries the rest of her subject, so the same passages come back for every part;
     * kept without this, an attention paper was filed as read on "diffusion", and a part the
     * library had nothing on was marked read (review of 2026-09-23). A part with no such word is
     * not judged.
     */
    static boolean namesThePart(Passage p, String part) {
        var hay = ((p.title() == null ? "" : p.title()) + " " + (p.text() == null ? "" : p.text()))
            .toLowerCase(Locale.ROOT);
        boolean judged = false;
        for (var w : part.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 4 || DriveOODA.KIND_WORDS.contains(w)) continue;
            judged = true;
            if (hay.contains(w.endsWith("s") ? w.substring(0, w.length() - 1) : w)) return true;
        }
        return !judged;
    }

    private static String firstWords(String s, int n) {
        if (s == null) return "";
        var words = s.strip().split("\\s+");
        return String.join(" ", Arrays.copyOf(words, Math.min(n, words.length))).strip();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
