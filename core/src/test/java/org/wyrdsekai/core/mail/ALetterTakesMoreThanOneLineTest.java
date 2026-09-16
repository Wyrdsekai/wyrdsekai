package org.wyrdsekai.core.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.persistence.MailStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing a letter a line at a time, the way mail has always been written: subject, body,
 * a single dot. Every surface takes one line per Enter, so this is the shape that works on
 * all of them without asking a client for anything.
 */
class ALetterTakesMoreThanOneLineTest {

    private static final String MIA = "companion-mia";
    private static final String KAZ = "did:key:z6MkKaz";
    private static final String SESSION = "ssh-1";

    private MailboxService mail;
    private final List<String> printed = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        MailboxService.resetForTests();
        mail = MailboxService.getOrCreate().install(
            new MailStore(SchemaInitializer.initialize(dir.resolve("world.db"))),
            MailDirectory.of(List.of(
                new MailDirectory.Recipient(MIA, "Mia", "companion"),
                new MailDirectory.Recipient(KAZ, "Kazuo", "person"))),
            "neo");
        printed.clear();
        MailComposer.get().cancel(SESSION);
    }

    @AfterEach
    void tearDown() {
        MailComposer.get().cancel(SESSION);
        MailboxService.resetForTests();
    }

    private boolean feed(String line) {
        return MailSurface.feedComposeLine(SESSION, KAZ, line, printed::add);
    }

    @Test
    @DisplayName("subject, then lines, then a dot — and the letter arrives whole")
    void writeALetter() {
        MailSurface.command(SESSION, KAZ, "mia", printed::add);
        assertEquals("Subject: ", printed.get(0));

        assertTrue(feed("the garden"));
        assertTrue(printed.get(1).contains("single ."), printed.get(1));
        assertTrue(feed("The seeds came up."));
        assertTrue(feed(""));
        assertTrue(feed("All of them, in the end."));
        assertTrue(feed("."));

        var inbox = mail.inbox(MIA, Map.of());
        assertEquals(1, inbox.size());
        assertEquals("the garden", inbox.get(0).get("subject"));
        assertEquals("The seeds came up.\n\nAll of them, in the end.", inbox.get(0).get("body"),
            "the blank line between paragraphs is part of the letter");
        assertTrue(printed.get(printed.size() - 1).startsWith("Sent to Mia@neo"),
            printed.toString());
        assertFalse(MailComposer.get().isComposing(SESSION));
    }

    @Test
    @DisplayName("a line that looks like a command is part of the letter, not a command")
    void linesAreNotCommands() {
        MailSurface.command(SESSION, KAZ, "mia hello", printed::add);
        assertTrue(feed("look, I have been thinking"));
        assertTrue(feed("go north when you can"));
        assertTrue(feed("read this twice"));
        assertTrue(feed("."));
        var body = String.valueOf(mail.inbox(MIA, Map.of()).get(0).get("body"));
        assertTrue(body.contains("look, I have been thinking"), body);
        assertTrue(body.contains("go north when you can"), body);
        assertTrue(body.contains("read this twice"), body);
    }

    @Test
    @DisplayName("a subject on the same line skips the prompt")
    void subjectOnTheCommandLine() {
        MailSurface.command(SESSION, KAZ, "mia about tuesday", printed::add);
        assertTrue(printed.get(0).contains("single ."), printed.get(0));
        feed("Can we move it?");
        feed(".");
        assertEquals("about tuesday", mail.inbox(MIA, Map.of()).get(0).get("subject"));
    }

    @Test
    @DisplayName("an abandoned letter sends nothing and lets the session go back to normal")
    void abandon() {
        MailSurface.command(SESSION, KAZ, "mia", printed::add);
        feed("a subject");
        feed("a line I thought better of");
        assertTrue(feed("~q"));
        assertTrue(printed.get(printed.size() - 1).contains("abandoned"), printed.toString());
        assertTrue(mail.inbox(MIA, Map.of()).isEmpty());
        assertFalse(feed("look"), "the next line belongs to the world again");
    }

    @Test
    @DisplayName("an empty letter is not sent")
    void emptyLetter() {
        MailSurface.command(SESSION, KAZ, "mia", printed::add);
        feed("subject only");
        feed(".");
        assertTrue(mail.inbox(MIA, Map.of()).isEmpty());
        assertTrue(printed.get(printed.size() - 1).contains("Nothing written"), printed.toString());
    }

    @Test
    @DisplayName("two sessions write two letters without touching each other")
    void twoSessions() {
        MailSurface.command(SESSION, KAZ, "mia", printed::add);
        MailSurface.command("web-2", KAZ, "mia", printed::add);
        feed("first");
        MailSurface.feedComposeLine("web-2", KAZ, "second", printed::add);
        feed("body one");
        MailSurface.feedComposeLine("web-2", KAZ, "body two", printed::add);
        feed(".");
        MailSurface.feedComposeLine("web-2", KAZ, ".", printed::add);

        var subjects = mail.inbox(MIA, Map.of()).stream().map(m -> String.valueOf(m.get("subject"))).toList();
        assertTrue(subjects.contains("first"), subjects.toString());
        assertTrue(subjects.contains("second"), subjects.toString());
    }

    @Test
    @DisplayName("nothing is intercepted when no letter is being written")
    void notComposing() {
        assertFalse(MailSurface.feedComposeLine("nobody", KAZ, "look", printed::add));
        assertTrue(printed.isEmpty());
    }

    @Test
    @DisplayName("mail with no arguments lists what has arrived, newest first")
    void listing() {
        mail.send(MIA, "Kazuo", "one", "first", Map.of());
        mail.send(MIA, "Kazuo", "two", "second", Map.of());
        MailSurface.command(SESSION, KAZ, "", printed::add);
        assertTrue(printed.get(0).startsWith("2 messages, 2 unread"), printed.get(0));
        assertTrue(printed.get(1).contains("two"), printed.get(1));
        assertTrue(printed.get(printed.size() - 1).contains("mail read <n>"));

        printed.clear();
        MailSurface.command(SESSION, KAZ, "read 1", printed::add);
        assertTrue(printed.get(0).contains("Mia@neo"), printed.toString());
        assertTrue(printed.contains("second"), printed.toString());
        assertEquals(1, mail.unreadFor(KAZ), "reading one leaves the other unread");
    }

    @Test
    @DisplayName("a refusal names the person, not an error code")
    void refusalsReadWell() {
        assertEquals("Mail to another household isn't carried yet — that comes with federation.",
            MailSurface.describe(Map.of("ok", false, "error", "federated_mail_not_yet"), "mia@alpha"));
        var unknown = MailSurface.describe(
            Map.of("ok", false, "error", "unknown_recipient", "known", List.of("Mia", "Kazuo")), "nobody");
        assertTrue(unknown.contains("Here: Mia, Kazuo."), unknown);
        assertTrue(unknown.contains("start the line with a quote"), unknown);
        assertEquals("Sent to Mia@neo.", MailSurface.describe(Map.of("ok", true, "to", "Mia@neo"), "mia"));
    }
}
