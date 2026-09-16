package org.wyrdsekai.core.mail;

import org.wyrdsekai.core.library.StudyService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * The {@code journal} verb: write a page, read back, search, keep one to yourself.
 *
 * <p>The help text has advertised {@code journal &lt;text&gt;}, {@code journal private &lt;text&gt;}
 * and {@code journal search &lt;query&gt;} for a long time and the server never had them — the only
 * way in was {@code use journal &lt;text&gt;} in the Study, which a person had to know to look for
 * (2026-09-15). This makes the advertised commands real, on every surface, and puts a page of
 * one's own on the same composer a letter uses.</p>
 */
public final class JournalSurface {

    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private JournalSurface() {}

    /**
     * {@code journal} (a blank page), {@code journal &lt;text&gt;}, {@code journal private &lt;text&gt;},
     * {@code journal read [n]}, {@code journal search &lt;words&gt;}.
     *
     * @return true when a blank page was opened and the surface should now collect lines
     */
    public static boolean command(String sessionId, String identity, String args, Consumer<String> out) {
        var study = StudyService.get();
        if (identity == null || identity.isBlank()) {
            out.accept("A journal is someone's own — sign in first.");
            return false;
        }
        if (study == null) {
            out.accept("There is no journal on this node.");
            return false;
        }
        var rest = args == null ? "" : args.strip();

        if (rest.isEmpty()) {
            var step = MailComposer.get().beginJournal(sessionId, false);
            if (step instanceof MailComposer.Step.Prompt p) {
                out.accept(p.text());
                return true;
            }
            return false;
        }

        var words = rest.split("\\s+", 2);
        var verb = words[0].toLowerCase();
        var tail = words.length > 1 ? words[1].strip() : "";

        // A sub-verb only counts where it cannot be the start of a sentence someone meant to
        // keep. "read" and "recent" take a count or nothing — "read that letter from mum
        // again" is an ENTRY, and answering it with a read-back would throw the sentence away,
        // which is the one thing a journal must never do (2026-09-15).
        boolean readVerb = (verb.equals("read") || verb.equals("recent") || verb.equals("show"))
            && (tail.isEmpty() || tail.matches("\\d{1,3}"));
        if (readVerb) {
            int n = tail.isEmpty() ? 5 : Math.clamp(Integer.parseInt(tail), 1, 50);
            readBack(study, identity, n, out);
            return false;
        }

        switch (verb) {
            case "search", "find" -> {
                // "search" DOES take a query, so "journal search <words>" is the documented
                // command and wins. Nothing is lost silently: a search that finds nothing says
                // so, and says how to write the line down instead.
                if (tail.isEmpty()) {
                    out.accept("Search for what? — journal search <words>");
                    return false;
                }
                var hits = study.searchAllJournal(identity, tail, 10);
                if (hits.isEmpty()) {
                    out.accept("Nothing in your journal matches \"" + tail + "\"."
                        + " (To write that down instead, open a page with `journal`.)");
                    return false;
                }
                out.accept(hits.size() + (hits.size() == 1 ? " entry" : " entries")
                    + " matching \"" + tail + "\":");
                for (var h : hits) out.accept("  " + mark(h.id()) + oneLine(h.content()));
            }
            case "private" -> {
                if (tail.isEmpty()) {
                    var step = MailComposer.get().beginJournal(sessionId, true);
                    if (step instanceof MailComposer.Step.Prompt p) {
                        out.accept(p.text());
                        return true;
                    }
                    return false;
                }
                study.writePrivateJournalEntry(identity, tail);
                out.accept("Written down, privately.");
            }
            default -> {
                study.writeJournalEntry(identity, rest);
                out.accept("Written down.");
            }
        }
        return false;
    }

    /** File a page written on the composer. */
    public static void write(String identity, boolean isPrivate, String body, Consumer<String> out) {
        var study = StudyService.get();
        if (study == null || identity == null || identity.isBlank()) {
            out.accept("There is nowhere to write that down.");
            return;
        }
        var text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            out.accept("Nothing written; nothing kept.");
            return;
        }
        if (isPrivate) {
            study.writePrivateJournalEntry(identity, text);
            out.accept("Written down, privately.");
        } else {
            study.writeJournalEntry(identity, text);
            out.accept("Written down.");
        }
    }

    private static void readBack(StudyService study, String identity, int n, Consumer<String> out) {
        // The OWNER's own read-back: their private entries are theirs to see.
        var entries = study.recentAllJournal(identity, n);
        if (entries.isEmpty()) {
            out.accept("Your journal is empty — nothing written down yet.");
            return;
        }
        out.accept("Your last " + entries.size()
            + (entries.size() == 1 ? " entry:" : " entries:"));
        for (var e : entries) {
            var meta = e.metadata();
            var ts = meta == null ? null : meta.get("timestamp");
            var when = "";
            if (ts instanceof Number num) {
                when = WHEN.format(Instant.ofEpochMilli(num.longValue())) + "  ";
            }
            out.accept("  " + when + mark(e.id()) + oneLine(e.content()));
        }
    }

    private static String mark(String id) {
        return id != null && id.startsWith("journal_private:") ? "[private] " : "";
    }

    private static String oneLine(String content) {
        if (content == null) return "";
        var t = content.strip().replaceAll("\\s*\\n\\s*", " / ");
        return t.length() <= 160 ? t : t.substring(0, 157) + "...";
    }
}
