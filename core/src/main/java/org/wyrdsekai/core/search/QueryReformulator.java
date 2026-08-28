package org.wyrdsekai.core.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Alternate phrasings to try when a library search comes back empty.
 *
 * <p><b>Why this exists.</b> Asked what the Librarian told Kestan about velsharas
 * in Glass Tide, the companion searched for {@code velshara}, got one irrelevant
 * hit out of 13.7M documents, and correctly reported that the sources did not
 * contain it. The book was right there. Arden spells it <b>vel-shara</b>,
 * hyphenated, and the only bare "Velshara" in the whole library was a filename in
 * an unrelated book about hacktivists.</p>
 *
 * <p>BM25 behaved perfectly. The retrieval stack behaved perfectly. What was
 * missing is the thing a person does without thinking: when the first phrasing
 * finds nothing, try another. A reader who knew the word would have tried
 * {@code vel-shara}, or {@code Adrun}, or {@code Librarian Sumerian} — any of which
 * finds the passage.</p>
 *
 * <p>Ordered most-promising first. Morphology comes before term-dropping,
 * because a spelling variant is a near-certain match while a narrowed query is a
 * guess.</p>
 */
public final class QueryReformulator {

    /** Words too common to discriminate in a large corpus. */
    private static final Set<String> WEAK = Set.of(
        "the", "a", "an", "and", "or", "but", "of", "in", "on", "at", "to", "for",
        "with", "about", "what", "who", "when", "where", "why", "how", "did", "do",
        "does", "is", "are", "was", "were", "be", "been", "tell", "told", "say",
        "said", "me", "my", "your", "his", "her", "it", "that", "this", "there",
        "some", "any", "significant", "important", "thing", "things", "look",
        "through", "find", "know", "anything", "something", "book", "books");

    private QueryReformulator() {}

    /**
     * Alternate phrasings for a query that found nothing.
     *
     * @param query the phrasing that failed
     * @return ordered variants, most promising first; never contains the original
     */
    public static List<String> variants(String query) {
        var out = new LinkedHashSet<String>();
        if (query == null || query.isBlank()) return List.of();

        var original = query.trim();
        var words = original.split("\\s+");

        // 1. MORPHOLOGY — the vel-shara case. A hyphenated term and its bare form
        //    are different tokens to the analyzer, so one can miss entirely while
        //    the other matches hundreds of passages.
        for (var w : words) {
            var bare = w.replaceAll("[^\\p{L}\\p{N}-]", "");
            if (bare.contains("-")) {
                out.add(original.replace(w, bare.replace("-", "")));       // vel-shara -> velshara
                out.add(original.replace(w, bare.replace("-", " ")));      // vel-shara -> vel shara
            }
        }
        // and the reverse: an unhyphenated compound may be hyphenated in the text
        for (var w : words) {
            var bare = w.replaceAll("[^\\p{L}\\p{N}]", "");
            if (bare.length() >= 6 && !bare.contains("-")) {
                for (int i = 3; i <= bare.length() - 3; i++) {
                    out.add(original.replace(w, bare.substring(0, i) + "-" + bare.substring(i)));
                }
            }
        }

        // 2. DISTINCTIVE TERMS — proper nouns and long rare words carry the
        //    signal; common words drown them in a large corpus.
        var strong = new ArrayList<String>();
        for (var w : words) {
            var bare = w.replaceAll("[^\\p{L}\\p{N}-]", "");
            if (bare.isBlank()) continue;
            var lower = bare.toLowerCase(Locale.ROOT);
            if (WEAK.contains(lower)) continue;
            boolean proper = Character.isUpperCase(bare.charAt(0));
            if (proper || bare.length() >= 6) strong.add(bare);
        }
        if (!strong.isEmpty() && strong.size() < words.length) {
            out.add(String.join(" ", strong));
        }

        // 3. THE RAREST-LOOKING TERM ALONE — longest non-weak word. A single
        //    discriminating term beats a phrase whose other words match everything.
        strong.stream()
            .max((a, b) -> Integer.compare(a.length(), b.length()))
            .ifPresent(out::add);

        // 4. PAIRS OF DISTINCTIVE TERMS — 'Librarian velshara' fails because one
        //    term matches everything; 'velshara Adrun' works because both are rare.
        for (int i = 0; i < strong.size(); i++) {
            for (int j = i + 1; j < strong.size(); j++) {
                out.add(strong.get(i) + " " + strong.get(j));
            }
        }

        out.remove(original);
        out.removeIf(v -> v == null || v.isBlank() || v.equalsIgnoreCase(original));
        return List.copyOf(out);
    }

    /** A sensible cap — each variant costs a search. */
    public static List<String> variants(String query, int limit) {
        var all = variants(query);
        return all.size() <= limit ? all : all.subList(0, limit);
    }
}
