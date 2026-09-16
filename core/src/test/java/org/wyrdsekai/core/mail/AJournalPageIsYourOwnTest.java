package org.wyrdsekai.core.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal a person keeps: written a line at a time or all at once, read back including
 * the entries they marked private, and searchable.
 *
 * <p>Three of these pin defects found on 2026-09-15: a private entry was write-only to its
 * author, an entry beginning with "read" was silently swallowed as a command, and the
 * advertised {@code journal} commands did not exist on the server at all.</p>
 */
class AJournalPageIsYourOwnTest {

    private static final String ME = "did:key:z6MkKaz";
    private static final String SESSION = "ssh-journal";

    private final List<String> printed = new ArrayList<>();
    private WyrdLuceneStore lucene;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        lucene = new WyrdLuceneStore(dir.resolve("search"), 384);
        lucene.ensureAllCollections();
        // Private entries are encrypted fail-closed, so the test JVM needs a zone master
        // the way a node originates one at first boot.
        var zoneId = WyrdConfig.get().zoneId();
        if (!ZoneSecrets.service().has(zoneId)) ZoneSecrets.service().generate(zoneId);
        StudyService.install(new StudyService(lucene));
        printed.clear();
        MailComposer.get().cancel(SESSION);
    }

    @AfterEach
    void tearDown() throws Exception {
        MailComposer.get().cancel(SESSION);
        StudyService.resetForTests();
        if (lucene != null) lucene.close();
    }

    private boolean journal(String args) {
        return JournalSurface.command(SESSION, ME, args, printed::add);
    }

    private String last() {
        return printed.isEmpty() ? "" : printed.get(printed.size() - 1);
    }

    @Test
    @DisplayName("write one, read it back")
    void writeAndRead() {
        assertFalse(journal("the room felt softer today"));
        assertEquals("Written down.", last());
        printed.clear();
        journal("read");
        assertTrue(printed.get(0).startsWith("Your last 1 entry"), printed.toString());
        assertTrue(last().contains("the room felt softer today"), printed.toString());
    }

    @Test
    @DisplayName("a private entry is readable by the person who wrote it, and marked")
    void privateIsReadableByItsAuthor() {
        journal("private the thing I am not ready to say");
        journal("something shared");
        printed.clear();
        journal("read");
        var all = String.join("\n", printed);
        assertTrue(all.contains("the thing I am not ready to say"),
            "a private entry was write-only to its author until 2026-09-15: " + all);
        assertTrue(all.contains("[private]"), all);
        assertTrue(all.contains("something shared"), all);
    }

    @Test
    @DisplayName("an entry that begins with a verb is an entry, not a command")
    void entriesThatLookLikeCommands() {
        journal("read that letter from mum again");
        assertEquals("Written down.", last(), "the sentence must not be swallowed as a read");
        journal("read 5 pages of that book");
        assertEquals("Written down.", last(), "a count must be the WHOLE rest, not the first word");
        journal("show me where I put the keys");
        assertEquals("Written down.", last());
        printed.clear();
        journal("read");
        var all = String.join("\n", printed);
        assertTrue(all.contains("read that letter from mum again"), all);
        assertTrue(all.contains("read 5 pages of that book"), all);
        assertTrue(all.contains("show me where I put the keys"), all);
    }

    @Test
    @DisplayName("read takes a count, and search finds shared and private alike")
    void countAndSearch() {
        for (int i = 1; i <= 6; i++) journal("entry number " + i);
        journal("private a quiet one about rain");
        printed.clear();
        journal("read 2");
        assertTrue(printed.get(0).startsWith("Your last 2 entries"), printed.toString());

        printed.clear();
        journal("search rain");
        assertTrue(printed.get(0).contains("matching \"rain\""), printed.toString());
        assertTrue(String.join("\n", printed).contains("a quiet one about rain"),
            "the owner's own search sees their private entries: " + printed);
    }

    @Test
    @DisplayName("a blank page collects lines until a single dot")
    void blankPage() {
        assertTrue(journal(""), "an empty journal command opens a page");
        assertTrue(printed.get(0).contains("single ."), printed.get(0));
        assertTrue(MailSurface.feedComposeLine(SESSION, ME, "First line.", printed::add));
        assertTrue(MailSurface.feedComposeLine(SESSION, ME, "", printed::add));
        assertTrue(MailSurface.feedComposeLine(SESSION, ME, "Second paragraph.", printed::add));
        assertTrue(MailSurface.feedComposeLine(SESSION, ME, ".", printed::add));
        assertEquals("Written down.", last());

        printed.clear();
        journal("read");
        var back = String.join("\n", printed);
        assertTrue(back.contains("First line."), back);
        assertTrue(back.contains("Second paragraph."), back);
    }

    @Test
    @DisplayName("a blank private page keeps what it collects private")
    void blankPrivatePage() {
        assertTrue(journal("private"));
        MailSurface.feedComposeLine(SESSION, ME, "for me only", printed::add);
        MailSurface.feedComposeLine(SESSION, ME, ".", printed::add);
        assertEquals("Written down, privately.", last());
        printed.clear();
        journal("read");
        assertTrue(String.join("\n", printed).contains("[private] for me only"), printed.toString());
    }

    @Test
    @DisplayName("an empty journal says so, and search that finds nothing says that")
    void emptyCases() {
        journal("read");
        assertEquals("Your journal is empty — nothing written down yet.", last());
        journal("search anything");
        assertTrue(last().contains("Nothing in your journal matches"), last());
        printed.clear();
        journal("search");
        assertTrue(last().contains("Search for what?"), last());
    }

    @Test
    @DisplayName("two entries written in the same millisecond both survive")
    void noCollision() {
        for (int i = 0; i < 8; i++) journal("same instant " + i);
        printed.clear();
        journal("read 20");
        var all = String.join("\n", printed);
        for (int i = 0; i < 8; i++) {
            assertTrue(all.contains("same instant " + i), "entry " + i + " was overwritten: " + all);
        }
    }
}
