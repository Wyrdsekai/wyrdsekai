package org.wyrdsekai.core.item;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Word-level matching for "what did she mean" questions — a template name the model
 * invented, an item name that describes a thing that already exists.
 *
 * <p>Substring search over descriptions is how {@code backup_vault} became a hammer
 * (household node, 2026-09-12): the item name "…use `create` to pack new backups…"
 * matched "Create Level 1 template items" through the word {@code create}, and the
 * old token scorer counted {@code the}, {@code for} and {@code and} as evidence. This
 * class does the two things that scorer did not: it drops the words that carry no
 * meaning, and it matches whole words, never fragments.
 */
public final class IntentTokens {

    private IntentTokens() {}

    /** Words that name nothing. A match on one of these is not evidence of intent. */
    static final Set<String> STOPWORDS = Set.of(
        "the", "and", "for", "with", "that", "this", "from", "into", "onto", "over",
        "under", "when", "where", "what", "which", "who", "whom", "your", "you", "our",
        "its", "his", "her", "their", "them", "they", "are", "was", "were", "been",
        "has", "have", "had", "not", "but", "can", "could", "will", "would", "should",
        "may", "might", "use", "used", "using", "new", "old", "one", "any", "all", "each",
        "every", "here", "there", "then", "than", "also", "just", "only", "very", "more",
        "most", "some", "such", "own", "same", "other", "about", "after", "before",
        "between", "through", "without", "within", "again", "still", "yet", "now",
        "how", "why", "let", "lets", "get", "gets", "set", "sets", "put", "keep", "keeps",
        "make", "makes", "made", "thing", "things", "item", "items", "tool", "tools",
        "object", "objects", "template", "templates", "level", "room", "rooms", "zone",
        "house", "household", "current", "state", "dated", "specific", "kind", "way");

    /** Distinct meaningful words of {@code sources}, lower-cased, in first-seen order. */
    public static Set<String> tokens(String... sources) {
        var out = new LinkedHashSet<String>();
        if (sources == null) return out;
        for (var src : sources) {
            if (src == null) continue;
            for (var tok : src.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (tok.length() < 3 || STOPWORDS.contains(tok)) continue;
                out.add(stem(tok));
            }
        }
        return out;
    }

    /** "snapshots" and "snapshot" are one word; both sides of a comparison pass through here. */
    static String stem(String tok) {
        if (tok.length() >= 5 && tok.endsWith("ies")) return tok.substring(0, tok.length() - 3) + "y";
        if (tok.length() >= 4 && tok.endsWith("s") && !tok.endsWith("ss")
                && !tok.endsWith("us") && !tok.endsWith("is")) {
            return tok.substring(0, tok.length() - 1);
        }
        return tok;
    }

    /** Words of a phrase, stopwords kept — for whole-phrase containment tests. */
    public static String phrase(String s) {
        if (s == null) return "";
        var sb = new StringBuilder();
        for (var tok : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (tok.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return sb.toString();
    }

    /** True when {@code needle} (a phrase) occurs as whole words inside {@code haystack} (a phrase). */
    public static boolean containsPhrase(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return false;
        return (" " + haystack + " ").contains(" " + needle + " ");
    }

    /** How many of {@code query} appear in {@code words}. */
    public static int overlap(Collection<String> query, Collection<String> words) {
        if (query == null || words == null) return 0;
        int n = 0;
        for (var q : query) if (words.contains(q)) n++;
        return n;
    }
}
