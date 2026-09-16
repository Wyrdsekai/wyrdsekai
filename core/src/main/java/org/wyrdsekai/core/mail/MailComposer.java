package org.wyrdsekai.core.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writing a letter one line at a time — subject, then body, ending with a single dot.
 *
 * <p>Every surface this world has takes one line per Enter: the browser's input, the ssh
 * shell, telnet. A letter written through any of them had to be one long line with a
 * separator in the middle, which is no way to write to someone. This is the shape mail has
 * used since before any of us: it needs nothing from the client, so it works the same
 * everywhere, and the browser's textarea is an improvement on top rather than the only way in.</p>
 *
 * <p>State is per session, not per person: two windows open at once are two letters, and
 * closing one does not eat the other.</p>
 */
public final class MailComposer {

    /** A line ending the letter. */
    public static final String TERMINATOR = ".";
    /** A line abandoning it. */
    public static final String ABORT = "~q";

    private static final int MAX_LINES = 2000;

    private static final MailComposer INSTANCE = new MailComposer();

    public static MailComposer get() { return INSTANCE; }

    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();

    /** What is being written — a letter to someone, or a page of one's own journal. */
    public enum Kind { MAIL, JOURNAL }

    private static final class Draft {
        final Kind kind;
        final String to;
        String subject;
        final List<String> body = new ArrayList<>();
        boolean haveSubject;
        Draft(Kind kind, String to) { this.kind = kind; this.to = to; }
    }

    /** What the surface should do with the line it just fed in. */
    public sealed interface Step {
        /** Ask the writer for more, showing this prompt. */
        record Prompt(String text) implements Step {}
        /** The writing is finished and should be filed or sent. */
        record Ready(Kind kind, String to, String subject, String body) implements Step {}
        /** Abandoned; nothing to send. */
        record Abandoned(String text) implements Step {}
        /** This session is not writing anything — hand the line back to the normal parser. */
        record NotComposing() implements Step {}
    }

    /** True when this session is in the middle of a letter. */
    public boolean isComposing(String sessionId) {
        return sessionId != null && drafts.containsKey(sessionId);
    }

    /** Start a letter. Returns the first prompt. */
    public Step begin(String sessionId, String to) {
        if (sessionId == null || to == null || to.isBlank()) {
            return new Step.Abandoned("To whom?");
        }
        drafts.put(sessionId, new Draft(Kind.MAIL, to.strip()));
        return new Step.Prompt("Subject: ");
    }

    /** Start a letter whose subject is already known — straight to the body. */
    public Step begin(String sessionId, String to, String subject) {
        if (sessionId == null || to == null || to.isBlank()) {
            return new Step.Abandoned("To whom?");
        }
        var d = new Draft(Kind.MAIL, to.strip());
        d.subject = subject == null ? "" : subject.strip();
        d.haveSubject = true;
        drafts.put(sessionId, d);
        return new Step.Prompt(bodyPrompt());
    }

    /** Feed the next line typed by the writer. */
    public Step feed(String sessionId, String line) {
        if (sessionId == null) return new Step.NotComposing();
        var d = drafts.get(sessionId);
        if (d == null) return new Step.NotComposing();
        var text = line == null ? "" : line;
        var trimmed = text.strip();

        if (trimmed.equals(ABORT)) {
            drafts.remove(sessionId);
            return new Step.Abandoned("Letter abandoned; nothing was sent.");
        }
        if (!d.haveSubject) {
            d.subject = trimmed;
            d.haveSubject = true;
            return new Step.Prompt(bodyPrompt());
        }
        if (trimmed.equals(TERMINATOR)) {
            drafts.remove(sessionId);
            var body = String.join("\n", d.body).strip();
            if (body.isEmpty()) return new Step.Abandoned("Nothing written; nothing sent.");
            return new Step.Ready(d.kind, d.to, d.subject, body);
        }
        if (d.body.size() >= MAX_LINES) {
            drafts.remove(sessionId);
            return new Step.Abandoned("That is longer than a letter; nothing was sent.");
        }
        d.body.add(text);
        return new Step.Prompt("");
    }

    /**
     * Start a journal entry: no recipient, no subject, straight to the page. A journal entry
     * is the one piece of writing in this world with no address on it.
     */
    public Step beginJournal(String sessionId, boolean isPrivate) {
        if (sessionId == null) return new Step.Abandoned("Nowhere to write.");
        var d = new Draft(Kind.JOURNAL, isPrivate ? "private" : "");
        d.haveSubject = true;
        drafts.put(sessionId, d);
        return new Step.Prompt("Write your entry. End with a single . on a line of its own ("
            + ABORT + " to abandon).");
    }

    /** Drop a letter in progress — the session went away. */
    public void cancel(String sessionId) {
        if (sessionId != null) drafts.remove(sessionId);
    }

    private static String bodyPrompt() {
        return "Write your letter. End with a single . on a line of its own (" + ABORT + " to abandon).";
    }
}
