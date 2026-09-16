package org.wyrdsekai.core.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.persistence.MailStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One person has one mailbox, whichever door they came in through.
 *
 * <p>The web and the phone sign a person in under their DID; the ssh and telnet corridors
 * still present the legacy login id. The directory files mail under the DID. Without one key
 * at every door, a letter sent from the browser was "No mail." from ssh — the same seam that
 * split the bondholder in two on 2026-08-19, wearing mail (found in review, 2026-09-15).</p>
 */
class OnePersonOneMailboxTest {

    private static final String LOGIN_ID = "1f56a2d4-0000-4000-8000-000000000001";
    private static final String DID = "did:key:z6MkKazPerson";
    private static final String MIA = "companion-mia";

    private MailboxService mail;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("ALTER TABLE users ADD COLUMN did TEXT");
            st.execute("INSERT INTO users(id, username, password_hash, display_name, role, created_at, did) "
                + "VALUES('" + LOGIN_ID + "','kaz','x','Kazuo','steward',0,'" + DID + "')");
        }
        PersonIds.resetForTesting(new PersonIdentityResolver(jdbc));
        MailboxService.resetForTests();
        mail = MailboxService.getOrCreate().install(new MailStore(jdbc), MailDirectory.of(List.of(
            new MailDirectory.Recipient(DID, "Kazuo", "person"),
            new MailDirectory.Recipient(DID, "kaz", "person"),      // username, as the node lists it
            new MailDirectory.Recipient(MIA, "Mia", "companion"))), "neo");
    }

    @AfterEach
    void tearDown() {
        MailboxService.resetForTests();
        PersonIds.resetForTesting(null);
    }

    @Test
    @DisplayName("mail sent to the person is read back under their login id and their DID alike")
    void bothDoorsOpenOnTheSameMailbox() {
        assertEquals(true, mail.send(MIA, "kaz", "hello", "Are you there?", Map.of()).get("ok"));

        assertEquals(1, mail.inbox(DID, Map.of()).size(), "the web door");
        assertEquals(1, mail.inbox(LOGIN_ID, Map.of()).size(), "the ssh door");
        assertEquals(1, mail.unreadFor(LOGIN_ID));

        var id = String.valueOf(mail.inbox(LOGIN_ID, Map.of()).get(0).get("id"));
        assertEquals(true, mail.markRead(LOGIN_ID, id).get("ok"), "read from ssh");
        assertEquals(0, mail.unreadFor(DID), "and it is read from the web too");
    }

    @Test
    @DisplayName("a letter sent from ssh carries the person's address, not their login id")
    void senderIsOnePersonToo() {
        var sent = mail.send(LOGIN_ID, "mia", "", "Coming home.", Map.of());
        assertEquals(true, sent.get("ok"), sent.toString());
        assertEquals("Kazuo@neo", sent.get("from"));
        var inbox = mail.inbox(MIA, Map.of());
        assertEquals(DID, inbox.get(0).get("from"), "filed under the DID, whichever door sent it");
    }

    @Test
    @DisplayName("an id nobody here has is refused — a typo is not a person")
    void unknownIdIsRefused() {
        var sent = mail.send(MIA, "companion-mio", "", "hi", Map.of());
        assertEquals("unknown_recipient", sent.get("error"), sent.toString());
        var visitor = mail.send(MIA, "did:key:z6MkVisitorNobodyListed", "", "hi", Map.of());
        assertTrue(Boolean.TRUE.equals(visitor.get("ok")), "a DID is a real key even when unlisted");
    }
}
