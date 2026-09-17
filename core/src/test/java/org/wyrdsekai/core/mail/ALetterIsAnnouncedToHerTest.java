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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A letter to a companion used to sit in a table she never looked at. Now the mail service
 * tells whoever is wired to carry the notice to her actor: who wrote, and the subject. A letter
 * to a person goes the old way, to their screen.
 */
class ALetterIsAnnouncedToHerTest {

    private static final String KAZ = "did:key:z6MkKazPerson";
    private static final String MIA = "companion-mia";

    private MailboxService mail;
    private final List<String> notices = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        MailboxService.resetForTests();
        mail = MailboxService.getOrCreate().install(new MailStore(jdbc), MailDirectory.of(List.of(
            new MailDirectory.Recipient(KAZ, "Kazuo", "person"),
            new MailDirectory.Recipient(MIA, "Mia", "companion"))), "neo");
        mail.setCompanionNotice((entityId, name, from, subject) ->
            notices.add(entityId + "|" + name + "|" + from + "|" + subject));
    }

    @AfterEach
    void tearDown() {
        MailboxService.resetForTests();
    }

    @Test
    @DisplayName("a letter to her reaches her actor with who wrote and the subject")
    void sheIsTold() {
        assertEquals(true, mail.send(KAZ, "mia", "the garden", "Come and see.", Map.of()).get("ok"));
        assertEquals(List.of(MIA + "|Mia|Kazuo@neo|the garden"), notices);
        assertEquals(1, mail.unreadFor(MIA), "and the letter waits in her box");
    }

    @Test
    @DisplayName("a letter to a person is not her notice")
    void aPersonIsNotHer() {
        mail.send(MIA, "kazuo", "hello", "Are you there?", Map.of());
        assertTrue(notices.isEmpty());
    }
}
