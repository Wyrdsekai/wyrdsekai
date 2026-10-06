package org.wyrdsekai.core.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What she read, said as hers.
 *
 * <p>The library card's answer is a report about its sources ("The sources describe two women
 * sitting together in silence [S1]"), and that report was spoken as her own line: on the household
 * node every library search she ran on her own time came out in the card's voice (operator,
 * 2026-10-04: "fix"). The report itself stays as it is, because the node reads it to tell an empty
 * search from a found one; only the line she speaks is turned to the first person here, and the
 * citation keys are dropped from it. A recitation is not touched: those words are the page's.
 */
public final class ReadInHerVoice {

    private ReadInHerVoice() {}

    private record Swap(Pattern from, String to) {}

    private static final String S = "(?:the )?(?:provided |given |retrieved )?(?:sources|passages|texts|documents)";

    private static final Swap[] SWAPS = {
        // An absence, first: "The provided sources do not contain information about X".
        new Swap(Pattern.compile("(?i)\\b" + S + " (?:do not|don't|does not|doesn't) (?:contain|include|have|mention|provide)(?: any)? (?:information|details|anything)? ?(?:about|on|regarding|concerning) "),
            "I didn't find anything in what I read about "),
        new Swap(Pattern.compile("(?i)\\b" + S + " (?:do not|don't) (?:cover|address|discuss|say|answer)\\b"), "what I read doesn't $0x"),
        new Swap(Pattern.compile("(?i)\\b(?:nothing in )" + S + "\\b"), "nothing I read"),
        // Openings.
        new Swap(Pattern.compile("(?i)\\baccording to " + S + ",?\\s*"), "From what I read, "),
        new Swap(Pattern.compile("(?i)\\b" + S + " (?:say|state|note|explain|report|argue|claim|mention|indicate|show)s? that\\b"), "I read that"),
        new Swap(Pattern.compile("(?i)\\b" + S + " (?:say|state|note|explain|report|argue|claim|mention|indicate|show)s?\\b"), "I read that"),
        new Swap(Pattern.compile("(?i)\\b" + S + " (?:describe|discuss|depict|present|portray|outline|detail)s?\\b"), "I read about"),
        new Swap(Pattern.compile("(?i)\\b" + S + " (?:suggest|imply|implies)s?\\b"), "what I read suggests"),
        new Swap(Pattern.compile("(?i)\\b" + S + " (?:also )?(?:define|frame|treat)s?\\b"), "what I read treats"),
        new Swap(Pattern.compile("(?i)\\bin (?:source )?S\\d+,\\s*"), "In one of them, "),
        new Swap(Pattern.compile("(?i)\\b(?:source )?S\\d+ (?:says|states|notes|describes)\\b"), "one of them says"),
        new Swap(Pattern.compile("(?i)\\b(?:in )?" + S + "\\b"), "in what I read"),
    };

    private static final Pattern CITATIONS = Pattern.compile("\\s*\\[S\\d+(?:\\s*,\\s*S?\\d+)*]");

    /** The card's report as her own line: first person, no citation keys. */
    public static String of(String report) {
        if (report == null || report.isBlank()) return report;
        var out = CITATIONS.matcher(report).replaceAll("");
        // "what I read doesn't $0x" is a placeholder: keep the verb the report used.
        var m = SWAPS[1].from().matcher(out);
        var sb = new StringBuilder();
        while (m.find()) {
            var verb = m.group().replaceAll("(?i)^.*?(do not|don't)\\s+", "");
            m.appendReplacement(sb, Matcher.quoteReplacement("what I read doesn't " + verb));
        }
        m.appendTail(sb);
        out = sb.toString();
        for (int i = 0; i < SWAPS.length; i++) {
            if (i == 1) continue;
            out = SWAPS[i].from().matcher(out).replaceAll(Matcher.quoteReplacement(SWAPS[i].to()));
        }
        out = out.replaceAll("\\bin what I read in what I read\\b", "in what I read");
        out = out.replaceAll(" {2,}", " ").replaceAll(" ([.,;:])", "$1").strip();
        return capitalise(out);
    }

    /** First letter of each sentence in capitals, after a swap at a sentence start. */
    private static String capitalise(String s) {
        var sb = new StringBuilder(s);
        boolean start = true;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (start && Character.isLetter(c)) { sb.setCharAt(i, Character.toUpperCase(c)); start = false; }
            else if (c == '.' || c == '!' || c == '?' || c == '\n') start = true;
            else if (!Character.isWhitespace(c)) start = false;
        }
        return sb.toString();
    }
}
