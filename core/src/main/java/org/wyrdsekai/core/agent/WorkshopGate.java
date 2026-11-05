package org.wyrdsekai.core.agent;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the workshop is not for. A request that is a question belongs to the library; one that is
 * her reading belongs to the book. The workshop takes any words and makes a program of them:
 * "Research the history of Zen Buddhism and the different philosophical changes across each
 * school" became a practice tool named zen_history.js, and "Read next part of the book (part 13)"
 * a job (household node, 2026-11-05).
 */
public final class WorkshopGate {

    private WorkshopGate() {}

    private static final Pattern QUESTION = Pattern.compile(
        "^(?:please\\s+)?(?:(?:do\\s+)?(?:some\\s+|deep\\s+|thorough\\s+)?research(?:\\s+(?:on|into|about))?|look\\s+into|look\\s+up|investigate|"
            + "find\\s+out\\s+(?:about|what|how|why|whether)|study\\s+(?:the\\s+)?(?:history|origins?|development)|learn\\s+(?:about|how|what)|"
            + "read\\s+up\\s+on|survey|summari[sz]e\\s+what\\s+is\\s+known|what\\s+(?:is|are|was|were)|how\\s+(?:do|does|did)|why\\s+(?:do|does|did|is|are))\\b");

    private static final Pattern READING = Pattern.compile(
        "^(?:please\\s+)?(?:read|reading|keep\\s+reading|continue\\s+reading|finish\\s+reading)\\b[^.]*\\b(?:next\\s+part|part\\s+\\d+|chapter|the\\s+book|where\\s+(?:i|we)\\s+left\\s+off)\\b");

    private static final Pattern PART = Pattern.compile("\\bpart\\s+(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OF_BOOK = Pattern.compile("\\b(?:of|in|from)\\s+(?:the\\s+book\\s+)?([A-Z][^,.(—–-]{2,80}?)(?:\\s*[,.(—–-]|\\s+(?:part|chapter|focusing|and|then|which|where)\\b|$)");

    /** Words that make a request a thing to build, whatever it starts with ("Research assistant: a tool that…"). */
    private static final Pattern A_THING = Pattern.compile(
        "\\b(?:tool|script|program|device|instrument|item|crystal|orb|lens|mirror|stone|bench|board|room|space|"
            + "that (?:takes|shows|checks|holds|lets|tracks|keeps|listens|counts)|build|make|craft|create)\\b");

    /** A request that asks a question, or asks for research: the library's, not the workshop's. */
    public static boolean isAQuestion(String description) {
        if (description == null) return false;
        var d = description.strip();
        if (d.isEmpty() || isReading(d)) return false;
        var low = d.toLowerCase(Locale.ROOT);
        if (!QUESTION.matcher(low).find()) return false;
        var first = low.contains(". ") ? low.substring(0, low.indexOf(". ")) : low;
        return !A_THING.matcher(first).find();
    }

    /** The question itself, as the library should be asked it: the first sentence, without the verb of asking. */
    public static String theQuestion(String description) {
        var d = description.strip();
        int end = d.indexOf(". ");
        var first = end > 0 ? d.substring(0, end) : d.split("\\n", 2)[0];
        var cut = first.replaceFirst("(?i)^(?:please\\s+)?(?:(?:do\\s+)?(?:some\\s+|deep\\s+|thorough\\s+)?research(?:\\s+(?:on|into|about))?|look\\s+into|look\\s+up|investigate|"
            + "find\\s+out\\s+(?:about|what|how|why|whether)|study|learn\\s+(?:about|how|what)|read\\s+up\\s+on|survey)\\s+", "").strip();
        return cut.isEmpty() ? first.strip() : cut;
    }

    /** A request that is her reading: the next part of a book, a part by number, a chapter. */
    public static boolean isReading(String description) {
        return description != null && READING.matcher(description.strip().toLowerCase(Locale.ROOT)).find();
    }

    public static Optional<Integer> thePart(String description) {
        if (description == null) return Optional.empty();
        var m = PART.matcher(description);
        if (!m.find()) return Optional.empty();
        try { return Optional.of(Integer.parseInt(m.group(1))); } catch (NumberFormatException e) { return Optional.empty(); }
    }

    /** The book the request names, when it names one ("of Tomczak's Deep Generative Modeling"). */
    public static Optional<String> theBook(String description) {
        if (description == null) return Optional.empty();
        var m = OF_BOOK.matcher(description);
        if (!m.find()) return Optional.empty();
        var name = m.group(1).strip();
        return name.length() < 3 ? Optional.empty() : Optional.of(name);
    }
}
