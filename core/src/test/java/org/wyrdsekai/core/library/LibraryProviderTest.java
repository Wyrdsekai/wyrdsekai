package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Her library as a provider of LIBRARY_PROTOCOL.md: the license gate on the sending side is
 * the whole point — what is stamped private never answers an outside patron.
 */
class LibraryProviderTest {

    @TempDir Path dir;
    private WyrdLuceneStore store;
    private static final String HER = "did:key:z6MkHer";
    private LibraryProvider provider;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(dir, 384);
        store.ensureAllCollections();
        store.insertKnowledge("wiki:1", "simple-wikipedia", "Obsidian",
            "Obsidian is a naturally occurring volcanic glass formed when lava cools rapidly.",
            "simple-wikipedia", "geology", null);
        store.insertKnowledge("books:sc-9", "study-share-books", "Glass Tide — chapter 9",
            "The Librarian explained to Kestan that a vel-shara is a speech with magical force.",
            "study-share-books", "fiction", null);
        store.commitAll();
        // A finding on the open pack, and one that cites the private shelf.
        var open = FindingsLedger.recordDraft(store, HER, "obsidian", "Obsidian is volcanic glass that cooled fast.",
            List.of("S1: Obsidian (simple-wikipedia) (wiki:1)"), "companion:her", FindingsLedger.ClaimType.EXTRACTION);
        var closed = FindingsLedger.recordDraft(store, HER, "vel-shara", "A vel-shara is a speech with magical force.",
            List.of("S1: Glass Tide — chapter 9 (study-share-books) (books:sc-9)"), "companion:her", FindingsLedger.ClaimType.EXTRACTION);
        FindingsLedger.setState(store, open, FindingsLedger.State.ACCEPTED, "test", null);
        FindingsLedger.setState(store, closed, FindingsLedger.State.ACCEPTED, "test", null);
        provider = new LibraryProvider(store, "zone-abc", "Her Library", HER, pack -> "simple-wikipedia".equals(pack));
    }

    @AfterEach void tearDown() throws Exception { store.close(); }

    @Test
    void status_counts_only_what_may_travel() {
        var st = provider.status();
        assertEquals("zone-abc", st.get("library_id"));
        assertEquals("1.0", st.get("contract"));
        @SuppressWarnings("unchecked") var counts = (Map<String, Object>) st.get("counts");
        assertEquals(1, counts.get("packs"));
        assertEquals(1, counts.get("findings_accepted"), "the finding citing the private shelf stays home");
    }

    @Test
    void private_shelves_never_answer_an_outside_patron() {
        var ask = provider.ask(Map.of("question", "vel-shara magical force", "k", 5,
            "patron", Map.of("did", "did:key:zPeer", "runtime", "wyrdsekai")));
        assertEquals(Boolean.TRUE, ask.get("holds_nothing"), ask.toString());

        var open = provider.ask(Map.of("question", "obsidian volcanic glass", "k", 5));
        @SuppressWarnings("unchecked") var entries = (List<Map<String, Object>>) open.get("entries");
        assertFalse(entries.isEmpty());
        assertEquals("finding", entries.getFirst().get("kind"), "her accepted finding comes before the raw chunk");
        assertTrue(entries.stream().noneMatch(e -> String.valueOf(e.get("id")).startsWith("books:")));

        var forbidden = assertThrows(LibraryProvider.ProtocolError.class,
            () -> provider.read(Map.of("locator", "books:sc-9")));
        assertEquals("forbidden", forbidden.code);
        assertTrue(String.valueOf(provider.read(Map.of("locator", "wiki:1")).get("text")).contains("volcanic"));
        var missing = assertThrows(LibraryProvider.ProtocolError.class,
            () -> provider.get(Map.of("id", "books:sc-9")));
        assertEquals("not_found", missing.code);
    }

    @Test
    void established_and_search_speak_the_contract() {
        var est = provider.established(Map.of("claim", "obsidian is volcanic glass that cooled fast"));
        assertEquals("established", est.get("verdict"));
        var not = provider.established(Map.of("claim", "a vel-shara is a speech with magical force"));
        assertEquals("not_established", not.get("verdict"), "held privately is not established for outsiders");

        var sr = provider.search(Map.of("query", "obsidian", "k", 5));
        @SuppressWarnings("unchecked") var hits = (List<Map<String, Object>>) sr.get("hits");
        assertTrue(hits.stream().anyMatch(h -> "raw".equals(h.get("kind"))));
        assertTrue(hits.stream().allMatch(h -> !String.valueOf(h.get("id")).startsWith("books:")));
    }

    @Test
    void submissions_enter_as_drafts_attributed_to_the_patron_and_need_sources() {
        var err = assertThrows(LibraryProvider.ProtocolError.class,
            () -> provider.submit(Map.of("claim", "an opinion", "sources", List.of())));
        assertEquals("no_sources", err.code);

        var ok = provider.submit(Map.of("claim", "Obsidian was used for blades.", "claim_type", "extraction",
            "sources", List.of(Map.of("locator", "lib-9f2c:F-0007", "edition", "Bantam 1992")),
            "patron", Map.of("did", "did:key:zPeer")));
        assertEquals("draft", ok.get("state"));
        var f = FindingsLedger.get(store, String.valueOf(ok.get("id"))).orElseThrow();
        assertEquals("patron:did:key:zPeer", f.writer());
        assertEquals("lib-9f2c:F-0007", f.sources().getFirst().locator());
        assertEquals("Bantam 1992", f.sources().getFirst().edition());
    }

    @Test
    void findings_of_every_companion_on_the_roster_are_served_and_the_roster_is_read_live() {
        var other = "did:key:z6MkOther";
        var id = FindingsLedger.recordDraft(store, other, "obsidian", "Obsidian blades were traded widely.",
            List.of("S1: Obsidian (simple-wikipedia) (wiki:1)"), "companion:other", FindingsLedger.ClaimType.EXTRACTION);
        FindingsLedger.setState(store, id, FindingsLedger.State.ACCEPTED, "test", null);

        var roster = new java.util.ArrayList<>(List.of(HER));
        var multi = new LibraryProvider(store, "zone-abc", "Her Library", () -> roster,
            pack -> "simple-wikipedia".equals(pack));
        @SuppressWarnings("unchecked")
        var before = (Map<String, Object>) multi.status().get("counts");
        assertEquals(1, before.get("findings_accepted"));

        roster.add(other);   // a companion born after boot is served without a restart
        @SuppressWarnings("unchecked")
        var after = (Map<String, Object>) multi.status().get("counts");
        assertEquals(2, after.get("findings_accepted"));
        assertEquals("accepted", multi.get(Map.of("id", id)).get("entry") instanceof Map<?, ?> m ? m.get("state") : null);

        var none = new LibraryProvider(store, "zone-abc", "Her Library", List::of, pack -> true);
        assertEquals(0, ((Map<?, ?>) none.status().get("counts")).get("findings_accepted"));
        assertEquals("unavailable", assertThrows(LibraryProvider.ProtocolError.class,
            () -> none.submit(Map.of("claim", "x", "sources", List.of("s")))).code);
    }

    @Test
    void the_pack_json_gate_reads_rights_and_study_shares(@TempDir Path packs) throws Exception {
        Files.createDirectories(packs.resolve("open"));
        Files.writeString(packs.resolve("open/pack.json"), "{\"name\":\"open\",\"rights\":\"CC-BY-SA-4.0\"}");
        Files.createDirectories(packs.resolve("shelf"));
        Files.writeString(packs.resolve("shelf/pack.json"), "{\"name\":\"shelf\",\"rights\":\"private\",\"source\":\"study-share\"}");
        Files.createDirectories(packs.resolve("nofed"));
        Files.writeString(packs.resolve("nofed/pack.json"), "{\"name\":\"nofed\",\"rights\":\"CC-BY-NC\",\"noFederate\":true}");
        var gate = LibraryProvider.packJsonGate(packs);
        assertTrue(gate.mayTravel("open"));
        assertFalse(gate.mayTravel("shelf"));
        assertFalse(gate.mayTravel("nofed"));
        assertFalse(gate.mayTravel("absent"), "an unreadable pack stays home");
    }
}
