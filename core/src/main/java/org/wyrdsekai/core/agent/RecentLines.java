package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Her own recent lines, so that a line said again word for word into silence is not said twice.
 *
 * <p>The exact-repeat guard in the speaking seam sees her last line for two minutes. After a tool
 * completed she said the whole of a line from nine minutes before ("The CMB work sits complete for
 * now…", household node 2026-10-02, 06:29 and 06:38; "The lens shows something I haven't seen
 * before…", 20:44 and 20:53), and other lines opened with an earlier one word for word before what
 * was new, against the turn's own instruction to add only what is hers to add. Measured over nine
 * days of two companions' lines: 71 whole repeats within half an hour and 11 lines that opened with
 * a recent one, of 3,399. A whole repeat is dropped; an opening that repeats keeps only what follows.
 * A line that answers a person is never touched (a repeated answer is conversation, a repeated line
 * into silence is the broken record).
 */
final class RecentLines {

    static final Duration WINDOW = Duration.ofMinutes(30);
    static final int KEEP = 12;
    /** An opening shorter than this is a turn of phrase, not a line said again. */
    static final int OPENING_MIN_CHARS = 40;

    enum Kind { PASS, DROP, CUT }

    /** {@code keep} is what remains to say after a CUT; {@code saidAt} is when the repeated line was said. */
    record Verdict(Kind kind, String keep, Instant saidAt) {
        static final Verdict PASS = new Verdict(Kind.PASS, null, null);
    }

    private record Said(String key, Instant at) {}

    private final Deque<Said> said = new ArrayDeque<>();

    static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    Verdict judge(String text, Instant now) {
        var key = normalize(text);
        if (key.isEmpty()) return Verdict.PASS;
        forget(now);
        for (var s : said) {
            if (s.key().equals(key)) return new Verdict(Kind.DROP, null, s.at());
        }
        for (var s : said) {
            if (s.key().length() >= OPENING_MIN_CHARS && key.startsWith(s.key()) && key.length() > s.key().length()) {
                var rest = key.substring(s.key().length()).strip();
                // Nothing said after the opening but marks ("...") is the line said again.
                if (!rest.matches("(?s).*[\\p{L}\\p{N}].*")) return new Verdict(Kind.DROP, null, s.at());
                return new Verdict(Kind.CUT, rest, s.at());
            }
        }
        return Verdict.PASS;
    }

    void remember(String text, Instant now) {
        var key = normalize(text);
        if (key.isEmpty()) return;
        forget(now);
        said.addLast(new Said(key, now));
        while (said.size() > KEEP) said.removeFirst();
    }

    private void forget(Instant now) {
        while (!said.isEmpty() && Duration.between(said.peekFirst().at(), now).compareTo(WINDOW) > 0) said.removeFirst();
    }

    int size() {
        return said.size();
    }
}
