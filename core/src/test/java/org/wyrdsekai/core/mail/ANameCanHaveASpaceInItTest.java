package org.wyrdsekai.core.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.MailboxService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mail ada lovelace the garden} is a letter to Ada Lovelace about the garden — not a
 * letter to "ada" with the subject "lovelace the garden". The directory says where the name
 * ends (found in review, 2026-09-15: every surface took the first word).
 */
class ANameCanHaveASpaceInItTest {

    private MailboxService mail;

    @BeforeEach
    void setUp() {
        MailboxService.resetForTests();
        mail = MailboxService.getOrCreate().install(null, MailDirectory.of(List.of(
            new MailDirectory.Recipient("did:key:z6MkAda", "Ada Lovelace", "person"),
            new MailDirectory.Recipient("companion-mia", "Mia", "companion"))), "neo");
    }

    @AfterEach
    void tearDown() {
        MailboxService.resetForTests();
        MailComposer.get().cancel("s1");
    }

    @Test
    @DisplayName("the longest run of words someone answers to is the name; the rest is the subject")
    void splitByDirectory() {
        var h = MailSurface.splitRecipient(mail, "ada lovelace the garden");
        assertEquals("ada lovelace", h.to());
        assertEquals("the garden", h.subject());

        var bare = MailSurface.splitRecipient(mail, "Ada Lovelace");
        assertEquals("Ada Lovelace", bare.to());
        assertEquals("", bare.subject());

        var one = MailSurface.splitRecipient(mail, "mia the garden");
        assertEquals("mia", one.to());
        assertEquals("the garden", one.subject());
    }

    @Test
    @DisplayName("a quoted name is taken as written; an unknown name is the first word, as before")
    void quotedAndUnknown() {
        var q = MailSurface.splitRecipient(mail, "\"ada lovelace\" the garden");
        assertEquals("ada lovelace", q.to());
        assertEquals("the garden", q.subject());

        var u = MailSurface.splitRecipient(mail, "nobody here at all");
        assertEquals("nobody", u.to());
        assertEquals("here at all", u.subject());

        var addr = MailSurface.splitRecipient(mail, "mia@alpha about tea");
        assertEquals("mia@alpha", addr.to());
        assertEquals("about tea", addr.subject());
    }

    @Test
    @DisplayName("the one-line form and the composer both deliver to the two-word name")
    void deliveredWhole() {
        var out = new ArrayList<String>();
        MailSurface.command("s1", "companion-mia", "ada lovelace the garden | The seeds came up.", out::add);
        assertEquals("Sent to Ada Lovelace@neo.", out.get(out.size() - 1));
        assertEquals(1, mail.inbox("did:key:z6MkAda", Map.of()).size());
        assertEquals("the garden", mail.inbox("did:key:z6MkAda", Map.of()).get(0).get("subject"));

        out.clear();
        MailSurface.command("s1", "companion-mia", "ada lovelace", out::add);
        assertTrue(MailComposer.get().isComposing("s1"));
        assertEquals("Subject: ", out.get(0));
        MailSurface.feedComposeLine("s1", "companion-mia", "tea", out::add);
        MailSurface.feedComposeLine("s1", "companion-mia", "Four o'clock?", out::add);
        MailSurface.feedComposeLine("s1", "companion-mia", ".", out::add);
        var subjects = mail.inbox("did:key:z6MkAda", Map.of()).stream().map(m -> m.get("subject")).toList();
        assertEquals(2, subjects.size());
        assertTrue(subjects.contains("tea"), subjects.toString());
    }
}
