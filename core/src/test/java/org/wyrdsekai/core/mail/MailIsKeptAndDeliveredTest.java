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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mail is kept. It lived in a map with a note promising persistence later, which made a
 * restart lose every message — so the test that matters is the one that reopens the database.
 */
class MailIsKeptAndDeliveredTest {

    private static final String MIA = "companion-mia";
    private static final String KAZ = "did:key:z6MkKaz";

    private String jdbc;
    private MailboxService mail;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        MailboxService.resetForTests();
        mail = MailboxService.getOrCreate().install(new MailStore(jdbc), directory(), "neo");
    }

    @AfterEach
    void tearDown() {
        MailboxService.resetForTests();
    }

    private static MailDirectory directory() {
        return MailDirectory.of(List.of(
            new MailDirectory.Recipient(MIA, "Mia", "companion"),
            new MailDirectory.Recipient(KAZ, "Kazuo", "person")));
    }

    @Test
    @DisplayName("a letter by name arrives, is read once, and survives a restart")
    void survivesRestart() {
        var sent = mail.send(KAZ, "mia", "the garden", "The seeds came up.", Map.of());
        assertEquals(true, sent.get("ok"), sent.toString());
        assertEquals("Mia@neo", sent.get("to"));
        assertEquals("Kazuo@neo", sent.get("from"), "the sender is shown as an address, not a key");

        MailboxService.resetForTests();
        var reopened = MailboxService.getOrCreate().install(new MailStore(jdbc), directory(), "neo");

        var inbox = reopened.inbox(MIA, Map.of());
        assertEquals(1, inbox.size(), "mail is still there after the restart");
        assertEquals("the garden", inbox.get(0).get("subject"));
        assertEquals("The seeds came up.", inbox.get(0).get("body"));
        assertEquals(false, inbox.get(0).get("read"));
        assertEquals(1, reopened.unreadFor(MIA));

        var id = String.valueOf(inbox.get(0).get("id"));
        assertEquals(true, reopened.markRead(MIA, id).get("ok"));
        assertEquals(0, reopened.unreadFor(MIA));
        assertEquals(true, reopened.markRead(MIA, id).get("already"));
        assertTrue(reopened.inbox(MIA, Map.of("unread", true)).isEmpty());
    }

    @Test
    @DisplayName("mia@neo is the same delivery as mia; another zone and the internet are refused by name")
    void scopes() {
        assertEquals(true, mail.send(KAZ, "mia@neo", "s", "b", Map.of()).get("ok"));
        assertEquals(1, mail.inbox(MIA, Map.of()).size());

        var federated = mail.send(KAZ, "mia@alpha", "s", "b", Map.of());
        assertEquals(false, federated.get("ok"));
        assertEquals("federated_mail_not_yet", federated.get("error"));
        assertEquals("mia@alpha", federated.get("address"));

        var external = mail.send(KAZ, "bob@example.org", "s", "b", Map.of());
        assertEquals(false, external.get("ok"));
        assertEquals("external_mail_not_configured", external.get("error"));

        assertEquals(1, mail.inbox(MIA, Map.of()).size(), "nothing undeliverable was filed");
    }

    @Test
    @DisplayName("a name nobody answers to is refused with the names that exist")
    void unknownRecipient() {
        var r = mail.send(KAZ, "nobody", "s", "b", Map.of());
        assertEquals(false, r.get("ok"));
        assertEquals("unknown_recipient", r.get("error"));
        assertTrue(String.valueOf(r.get("known")).contains("Mia"));
    }

    @Test
    @DisplayName("mail is filed under the identity, so a rename does not strand it")
    void filedUnderIdentity() {
        mail.send(KAZ, "Mia", "before", "b", Map.of());
        var renamed = MailDirectory.of(List.of(
            new MailDirectory.Recipient(MIA, "Wisp", "companion"),
            new MailDirectory.Recipient(KAZ, "Kazuo", "person")));
        mail.install(new MailStore(jdbc), renamed, "neo");
        assertEquals(1, mail.inbox(MIA, Map.of()).size(), "her old mail is still hers");
        assertEquals(true, mail.send(KAZ, "Wisp", "after", "b", Map.of()).get("ok"));
        assertEquals(2, mail.inbox(MIA, Map.of()).size());
    }

    @Test
    @DisplayName("archived mail leaves the inbox but is still there when asked for")
    void archiving() {
        var sent = mail.send(KAZ, "mia", "s", "b", Map.of());
        var id = String.valueOf(sent.get("id"));
        assertEquals(true, mail.archive(MIA, id).get("ok"));
        assertTrue(mail.inbox(MIA, Map.of()).isEmpty());
        assertEquals(1, mail.inbox(MIA, Map.of("archived", true)).size());
        assertEquals(0, mail.unreadFor(MIA));
    }

    @Test
    @DisplayName("the steward sees who wrote to whom, never the subject or the body")
    void stewardSeesMetadataOnly() {
        mail.send(KAZ, "mia", "a private subject", "a private body", Map.of());
        var headers = mail.headers(50);
        assertEquals(1, headers.size());
        var h = headers.get(0);
        assertEquals("Kazuo@neo", h.get("from"));
        assertEquals("Mia@neo", h.get("to"));
        assertFalse(h.containsKey("subject"), h.toString());
        assertFalse(h.containsKey("body"), h.toString());
        assertFalse(h.containsKey("content"), h.toString());
        assertEquals(14, h.get("bytes"));
    }

    @Test
    @DisplayName("one person's mail is not another's to read")
    void inboxesAreSeparate() {
        var sent = mail.send(KAZ, "mia", "s", "b", Map.of());
        var id = String.valueOf(sent.get("id"));
        assertEquals("not_found", mail.read(KAZ, id).get("error"));
        assertEquals(false, mail.markRead(KAZ, id).get("ok"));
        assertTrue(mail.inbox(KAZ, Map.of()).isEmpty());
    }

    @Test
    @DisplayName("expired mail stops being delivered")
    void expiry() {
        mail.send(KAZ, "mia", "s", "b", Map.of("expires", System.currentTimeMillis() - 1000));
        assertTrue(mail.inbox(MIA, Map.of()).isEmpty());
        assertEquals(1, new MailStore(jdbc).purgeExpired(System.currentTimeMillis()));
    }

    @Test
    @DisplayName("a body larger than the cap is refused whole")
    void bodyCap() {
        var big = "x".repeat(MailboxService.MAX_BODY_BYTES + 1);
        var r = mail.send(KAZ, "mia", "s", big, Map.of());
        assertEquals(false, r.get("ok"));
        assertEquals("body_too_large", r.get("error"));
        assertTrue(mail.inbox(MIA, Map.of()).isEmpty());
    }
}
