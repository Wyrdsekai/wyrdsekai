package org.wyrdsekai.core.agent;

import java.util.regex.Pattern;

/**
 * A sentence that never ends. The larger model can fall into one: clause after clause with no full
 * stop, which after a few hundred words collapses into lists of near-synonyms, up to 6,000
 * characters. Read back in the next prompt it is copied, and learned at night it is learned.
 *
 * <p>The rule is mechanical: sentences end at {@code . ! ? …} or a new line, and a sentence runs on
 * when it has more than {@link #MAX_WORDS} words, counting words split on spaces and underscores
 * (a list written {@code shown_displayed_exhibited} is three words). The nightly trainer and the
 * morning check use the same rule.
 */
public final class RunOn {

    /** More words than this in one sentence is a run-on. */
    public static final int MAX_WORDS = 60;
    /** A first sentence that runs on is cut at a clause boundary no earlier than this many words in. */
    static final int MIN_KEPT_WORDS = 8;

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s_]+");
    private static final Pattern CLAUSE_END = Pattern.compile("[,;:—–]");

    private RunOn() {}

    /** Whether any sentence in the text runs on. */
    public static boolean hasRunOn(String text) {
        return text != null && runOnStart(text) >= 0;
    }

    /**
     * The text up to the end of the last sentence before one that runs on; unchanged when none does.
     * When the first sentence runs on, its first {@link #MAX_WORDS} words are kept, up to the last
     * comma or dash among them, and end with "…".
     */
    public static String cut(String text) {
        if (text == null) return null;
        int start = runOnStart(text);
        if (start < 0) return text;
        var before = text.substring(0, start).strip();
        if (!before.isEmpty()) return before;
        return trailOff(text.strip());
    }

    /** Where the first run-on sentence starts, or -1. */
    private static int runOnStart(String text) {
        int sentenceStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean end = i == text.length() || isSentenceEnd(text.charAt(i));
            if (!end) continue;
            if (words(text.substring(sentenceStart, i)) > MAX_WORDS) return sentenceStart;
            sentenceStart = i + 1;
        }
        return -1;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…' || c == '\n';
    }

    private static int words(String s) {
        int n = 0;
        for (var w : WORD_SPLIT.split(s.strip())) if (!w.isEmpty()) n++;
        return n;
    }

    /** The first sentence's opening, cut at a clause boundary, trailing off. */
    private static String trailOff(String sentence) {
        var parts = WORD_SPLIT.split(sentence);
        // The character offset where word MAX_WORDS ends.
        int seen = 0, offset = 0;
        var m = Pattern.compile("[^\\s_]+").matcher(sentence);
        while (m.find()) {
            if (++seen == Math.min(MAX_WORDS, parts.length)) { offset = m.end(); break; }
        }
        var head = sentence.substring(0, offset);
        int clause = -1;
        var c = CLAUSE_END.matcher(head);
        while (c.find()) {
            if (words(head.substring(0, c.start())) >= MIN_KEPT_WORDS) clause = c.start();
        }
        if (clause > 0) head = head.substring(0, clause);
        return head.strip().replaceAll("[\\s,;:—–-]+$", "") + "…";
    }
}
