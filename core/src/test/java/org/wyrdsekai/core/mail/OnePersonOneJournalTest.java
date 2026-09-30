package org.wyrdsekai.core.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A person's journal is theirs from every door.
 *
 * <p>A write resolves its owner to the person DID before it lands; a read under the legacy
 * login id — what the ssh corridor presents — found nothing, so the journal was full from
 * the browser and empty from ssh (found in review, 2026-09-15). And pages written before the
 * identity migration sit under the old id: the owner's read-back looks under both.</p>
 */
class OnePersonOneJournalTest {

    private static final String LOGIN_ID = "1f56a2d4-0000-4000-8000-000000000002";
    private static final String DID = "did:key:z6MkKazJournal";

    private final List<String> printed = new ArrayList<>();
    private WyrdLuceneStore lucene;
    private StudyService study;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("ALTER TABLE users ADD COLUMN did TEXT");
            st.execute("INSERT INTO users(id, username, password_hash, display_name, role, created_at, did) "
                + "VALUES('" + LOGIN_ID + "','kaz','x','Kazuo','steward',0,'" + DID + "')");
        }
        PersonIds.resetForTesting(new PersonIdentityResolver(jdbc));
        lucene = new WyrdLuceneStore(dir.resolve("search"), 384);
        lucene.ensureAllCollections();
        var zoneId = WyrdConfig.get().zoneId();
        if (!ZoneSecrets.service().has(zoneId)) ZoneSecrets.service().generate(zoneId);
        study = new StudyService(lucene);
        StudyService.install(study);
    }

    @AfterEach
    void tearDown() throws Exception {
        StudyService.resetForTests();
        PersonIds.resetForTesting(null);
        if (lucene != null) lucene.close();
    }

    @Test
    @DisplayName("pages written under the DID are read back under the login id, private ones included")
    void readBackFromTheOtherDoor() {
        study.writeJournalEntry(DID, "the garden came up");
        study.writePrivateJournalEntry(DID, "and I was glad");

        var fromSsh = study.recentAllJournal(LOGIN_ID, 10);
        assertEquals(2, fromSsh.size(), "both pages, from the ssh door");
        assertTrue(fromSsh.stream().anyMatch(r -> "and I was glad".equals(r.content())),
            "the private page is decrypted for its author whichever id they present");

        var found = study.searchAllJournal(LOGIN_ID, "garden", 10);
        assertEquals(1, found.size());

        JournalSurface.command("s", LOGIN_ID, "read", printed::add);
        assertTrue(printed.get(0).startsWith("Your last 2"), printed.toString());
    }

    @Test
    @DisplayName("the phone, which syncs and lists under the login id, gets what was written under the person")
    void thePhoneGetsWhatWasWrittenUnderThePerson() {
        study.writeJournalEntry(DID, "the garden came up");
        study.writePrivateJournalEntry(DID, "and I was glad");

        var delta = study.getDeltaForPeer(LOGIN_ID, Map.of());
        assertEquals(1, delta.size(), "the shared page goes to the phone; the private one stays home");
        assertEquals("the garden came up", delta.get(0).content());
        assertEquals(1, study.recentJournal(LOGIN_ID, 10).size(), "the phone's sealed journal list");

        // What the phone sends lands under the person, where the other doors read and write.
        var fromPhone = new StudyService.StudyMergeItem("journal:phone:1", LOGIN_ID, "journal", "from the phone",
            "from the phone", "journal", System.currentTimeMillis(), 1, Map.of("phone-1", 1), "phone-1", false);
        assertEquals(1, study.mergeFromPeer(LOGIN_ID, List.of(fromPhone)));
        assertTrue(lucene.listAllStudy(DID, 100).stream().anyMatch(r -> "journal:phone:1".equals(r.id())));
        assertEquals(2, study.recentJournal(DID, 10).size());
    }

    @Test
    @DisplayName("from the ssh door, a page written under the old id before the migration is still there")
    void oldPagesStayVisibleFromTheOldDoor() {
        study.writeJournalEntry(LOGIN_ID, "written before anyone had a DID");
        assertEquals(1, study.recentAllJournal(LOGIN_ID, 10).size());
    }
}
