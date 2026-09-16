package org.wyrdsekai.core.mail;

import org.wyrdsekai.core.item.MailboxService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@code mail} verb, written once for every surface that takes a line at a time.
 *
 * <p>ssh, telnet and the browser each had their own dispatch, and the map-occupants split
 * (2026-09-14) showed what happens when a feature is wired into one of them and not the
 * others. Mail is one implementation; a surface contributes a session id and somewhere to
 * print.</p>
 */
public final class MailSurface {

    private static final DateTimeFormatter WHEN =
        DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private MailSurface() {}

    /**
     * Offer a raw line to a letter in progress.
     *
     * @return true when the line was part of a letter and must not be parsed as a command —
     *         nor, on a chat-first surface, spoken aloud in the room
     */
    public static boolean feedComposeLine(String sessionId, String senderIdentity,
                                          String line, Consumer<String> out) {
        var composer = MailComposer.get();
        if (!composer.isComposing(sessionId)) return false;
        var step = composer.feed(sessionId, line);
        switch (step) {
            case MailComposer.Step.NotComposing ignored -> {
                return false;
            }
            case MailComposer.Step.Prompt p -> {
                if (!p.text().isEmpty()) out.accept(p.text());
            }
            case MailComposer.Step.Abandoned a -> out.accept(a.text());
            case MailComposer.Step.Ready r -> {
                if (r.kind() == MailComposer.Kind.JOURNAL) {
                    JournalSurface.write(senderIdentity, "private".equals(r.to()), r.body(), out);
                } else {
                    var sent = MailboxService.getOrCreate()
                        .send(senderIdentity, r.to(), r.subject(), r.body(), Map.of());
                    out.accept(describe(sent, r.to()));
                }
            }
        }
        return true;
    }

    /** {@code mail}, {@code mail <who> [subject]}, {@code mail read <n>}, {@code mail archive <n>}. */
    public static void command(String sessionId, String identity, String args, Consumer<String> out) {
        var mail = MailboxService.getOrCreate();
        var rest = args == null ? "" : args.strip();
        if (identity == null || identity.isBlank()) {
            out.accept("Mail needs someone to be addressed to — sign in first.");
            return;
        }
        if (rest.isEmpty()) {
            listInbox(mail, identity, out);
            return;
        }
        var words = rest.split("\\s+");
        var verb = words[0].toLowerCase();
        var tail = rest.substring(words[0].length()).strip();

        switch (verb) {
            case "list", "inbox" -> listInbox(mail, identity, out);
            case "read", "open" -> {
                var m = pick(mail, identity, tail);
                if (m == null) {
                    out.accept("Which one? — mail read <n>");
                    return;
                }
                var full = mail.read(identity, String.valueOf(m.get("id")));
                if (full.get("error") != null) {
                    out.accept("That one isn't here any more.");
                    return;
                }
                mail.markRead(identity, String.valueOf(m.get("id")));
                out.accept("From:    " + full.getOrDefault("fromAddress", full.get("from")));
                var subject = full.get("subject");
                out.accept("Subject: " + (subject == null || String.valueOf(subject).isBlank()
                    ? "(none)" : subject));
                out.accept("");
                out.accept(String.valueOf(full.getOrDefault("body", "")));
            }
            case "archive" -> {
                var m = pick(mail, identity, tail);
                if (m == null) {
                    out.accept("Which one? — mail archive <n>");
                    return;
                }
                var done = mail.archive(identity, String.valueOf(m.get("id")));
                out.accept(Boolean.TRUE.equals(done.get("ok"))
                    ? "Put away." : "That one isn't here any more.");
            }
            default -> {
                // One line, whole letter: "mail mia the garden | The seeds came up." Any client
                // can send this, including one with no composer and no way to collect lines.
                int bar = rest.indexOf('|');
                if (bar > 0) {
                    var head = splitRecipient(mail, rest.substring(0, bar).strip());
                    var body = rest.substring(bar + 1).strip();
                    var sent = mail.send(identity, head.to(), head.subject(), body, Map.of());
                    out.accept(describe(sent, head.to()));
                    return;
                }
                // Everything else is a person to write to. A subject given on the same line
                // skips the subject prompt, the way a mail client's -s flag does.
                var head = splitRecipient(mail, rest);
                var step = head.subject().isEmpty()
                    ? MailComposer.get().begin(sessionId, head.to())
                    : MailComposer.get().begin(sessionId, head.to(), head.subject());
                switch (step) {
                    case MailComposer.Step.Prompt p -> out.accept(p.text());
                    case MailComposer.Step.Abandoned a -> out.accept(a.text());
                    default -> { }
                }
            }
        }
    }

    /** Who a line is addressed to, and what is left over for the subject. */
    public record Head(String to, String subject) {}

    /**
     * Split {@code "ada lovelace the garden"} into who and what.
     *
     * <p>Names have spaces. The directory says which words are the name: the longest run of
     * leading words that someone here answers to is the recipient, and the rest is the
     * subject. A quoted name is taken as written. When nobody matches, the first word is
     * the recipient — an address like {@code mia@alpha}, or a name the send will refuse with
     * the names that do exist.</p>
     */
    public static Head splitRecipient(MailboxService mail, String line) {
        var rest = line == null ? "" : line.strip();
        if (rest.isEmpty()) return new Head("", "");
        if (rest.startsWith("\"")) {
            int close = rest.indexOf('"', 1);
            if (close > 1) {
                return new Head(rest.substring(1, close).strip(), rest.substring(close + 1).strip());
            }
        }
        var words = rest.split("\\s+");
        var directory = mail.directory();
        for (int n = words.length; n >= 2; n--) {
            var candidate = String.join(" ", Arrays.copyOfRange(words, 0, n));
            if (directory.byName(candidate).isPresent()) {
                return new Head(candidate, String.join(" ", Arrays.copyOfRange(words, n, words.length)));
            }
        }
        return new Head(words[0], String.join(" ", Arrays.copyOfRange(words, 1, words.length)));
    }

    private static void listInbox(MailboxService mail, String identity, Consumer<String> out) {
        var inbox = mail.inbox(identity, Map.of());
        if (inbox.isEmpty()) {
            out.accept("No mail.");
            return;
        }
        int unread = 0;
        for (var m : inbox) if (!Boolean.TRUE.equals(m.get("read"))) unread++;
        out.accept(inbox.size() + " message" + (inbox.size() == 1 ? "" : "s")
            + (unread > 0 ? ", " + unread + " unread:" : ", all read:"));
        for (int i = 0; i < inbox.size(); i++) {
            var m = inbox.get(i);
            var subject = m.get("subject");
            out.accept(String.format("  %d. %s %-22s %s   %s",
                i + 1,
                Boolean.TRUE.equals(m.get("read")) ? " " : "*",
                String.valueOf(m.getOrDefault("fromAddress", m.get("from"))),
                subject == null || String.valueOf(subject).isBlank() ? "(no subject)" : subject,
                WHEN.format(Instant.ofEpochMilli(((Number) m.getOrDefault("ts", 0L)).longValue()))));
        }
        out.accept("Read one with: mail read <n>");
    }

    private static Map<String, Object> pick(MailboxService mail, String identity, String which) {
        var inbox = mail.inbox(identity, Map.of());
        if (inbox.isEmpty() || which == null || which.isBlank()) return null;
        var w = which.strip();
        try {
            int n = Integer.parseInt(w);
            if (n >= 1 && n <= inbox.size()) return inbox.get(n - 1);
        } catch (NumberFormatException ignored) {
            // an id, then
        }
        for (var m : inbox) {
            if (w.equals(String.valueOf(m.get("id")))) return m;
        }
        return null;
    }

    /** Turn a send result into one line a person can act on. */
    public static String describe(Map<String, Object> sent, String to) {
        if (Boolean.TRUE.equals(sent.get("ok"))) {
            return "Sent to " + sent.getOrDefault("to", to) + ".";
        }
        var why = String.valueOf(sent.get("error"));
        return switch (why) {
            case "unknown_recipient" -> {
                var known = sent.get("known");
                var names = known instanceof List<?> l && !l.isEmpty()
                    ? " Here: " + String.join(", ", l.stream().map(String::valueOf).toList()) + "."
                    : "";
                // "mail me the recipe later" is a sentence, and on a chat-first surface the
                // leading verb captured it. Say how to speak it instead of only refusing.
                yield "Nobody here is called '" + to + "'." + names
                    + " (To say that out loud instead, start the line with a quote.)";
            }
            case "federated_mail_not_yet" ->
                "Mail to another household isn't carried yet — that comes with federation.";
            case "external_mail_not_configured" ->
                "No mail account is configured for letters leaving the household.";
            case "body_too_large" -> "That is too long to send.";
            case "bad_address" -> "That is not an address I can read.";
            default -> "The letter would not go (" + why + ").";
        };
    }
}
