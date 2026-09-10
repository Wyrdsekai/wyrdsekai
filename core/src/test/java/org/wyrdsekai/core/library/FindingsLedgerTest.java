package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What she concludes from reading becomes a claim she can find again — with its sources,
 * its state, and an honest review that only mechanical evidence can pass.
 */
class FindingsLedgerTest {

    @TempDir Path dir;
    private WyrdLuceneStore store;
    private static final String HER = "did:key:z6MkTestCompanion";

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(dir, 384);
        store.ensureAllCollections();
        store.insertKnowledge("books:sc-9", "study-share-books", "Glass Tide — chapter 9",
            "The Librarian explained to Kestan that a vel-shara is a speech with magical force; "
                + "the vel-shara of Adrun was a counter-program.", "study-share-books", "fiction", null);
        store.commitAll();
    }

    @AfterEach
    void tearDown() throws Exception { store.close(); }

    @Test
    void the_header_round_trips_and_fails_closed() {
        var f = new FindingsLedger.Finding("finding:x:1", HER,
            "A vel-shara is a speech with magical force.", FindingsLedger.ClaimType.EXTRACTION,
            "high", FindingsLedger.State.DRAFT, "companion:test", "vel-shara", null,
            List.of(FindingsLedger.Source.parse("S1: Glass Tide — chapter 9 (study-share-books)")),
            null, null, 1, FindingsLedger.hash("A vel-shara is a speech with magical force."));
        var text = FindingsLedger.serialize(f);
        var back = FindingsLedger.parse("finding:x:1", HER, text, 1).orElseThrow();
        assertEquals(f.claim(), back.claim());
        assertEquals(FindingsLedger.ClaimType.EXTRACTION, back.claimType());
        assertEquals("Glass Tide — chapter 9", back.sources().getFirst().title());
        assertFalse(back.reviewStale());

        assertTrue(FindingsLedger.parse("n", HER, "just a note, not a finding", 1).isEmpty());
        assertTrue(FindingsLedger.parse("n", HER, "FINDING\nstate: draft\n---\n", 1).isEmpty(),
            "a finding without a claim is not a finding");
        assertTrue(FindingsLedger.parse("n", HER, "FINDING\nclaim_type: synthesis\n---\nclaim", 1).isEmpty(),
            "a finding without a state is not a finding");
    }

    @Test
    void a_source_line_with_a_chunk_id_keeps_it() {
        var s = FindingsLedger.Source.parse("S2: Altered Carbon (study-share-books) (doc:did:key:zOwner:books:7f)");
        assertEquals("doc:did:key:zOwner:books:7f", s.chunkId());
        assertEquals("Altered Carbon", s.title());
    }

    @Test
    void recorded_drafts_can_be_found_again_and_a_claim_without_sources_is_refused() {
        assertNull(FindingsLedger.recordDraft(store, HER, "q", "an opinion", List.of(),
            "companion:test", FindingsLedger.ClaimType.SYNTHESIS));

        var id = FindingsLedger.recordDraft(store, HER, "what is a vel-shara",
            "The Librarian told Kestan a vel-shara is a speech with magical force.",
            List.of("S1: Glass Tide — chapter 9 (study-share-books)"),
            "companion:test", FindingsLedger.ClaimType.EXTRACTION);
        assertNotNull(id);

        var found = FindingsLedger.established(store, HER, "vel-shara magical force", 3);
        assertEquals(1, found.size());
        assertEquals(FindingsLedger.State.DRAFT, found.getFirst().state());
        assertEquals("companion:test", found.getFirst().writer());
        assertTrue(FindingsLedger.render(found, 500).contains("(not yet reviewed)"));
        assertTrue(FindingsLedger.established(store, "did:key:someoneElse", "vel-shara", 3).isEmpty(),
            "findings are hers; another owner does not see them");
    }

    @Test
    void review_accepts_resolvable_drafts_keeps_unresolvable_ones_and_retires_restatements() {
        var good = FindingsLedger.recordDraft(store, HER, "vel-shara",
            "A vel-shara is a speech with magical force, per the Librarian.",
            List.of("S1: Glass Tide — chapter 9 (study-share-books) (books:sc-9)"),
            "companion:test", FindingsLedger.ClaimType.EXTRACTION);
        var ghost = FindingsLedger.recordDraft(store, HER, "kovacs",
            "Takeshi Kovacs was an Envoy before he was resleeved.",
            List.of("S1: Altered Carbon (study-share-books)"),
            "companion:test", FindingsLedger.ClaimType.EXTRACTION);
        var r1 = FindingsLedger.reviewDrafts(store, HER);
        assertEquals(2, r1.reviewed());
        assertEquals(1, r1.accepted());
        assertEquals(1, r1.keptDraft());
        assertEquals(FindingsLedger.State.ACCEPTED, FindingsLedger.get(store, good).orElseThrow().state());
        var kept = FindingsLedger.get(store, ghost).orElseThrow();
        assertEquals(FindingsLedger.State.DRAFT, kept.state());
        assertTrue(kept.reviewNote().startsWith("source not found"), kept.reviewNote());
        assertEquals("books:sc-9", FindingsLedger.get(store, good).orElseThrow().sources().getFirst().chunkId());

        // The same claim again, slightly reworded, restates the accepted one.
        var again = FindingsLedger.recordDraft(store, HER, "vel-shara",
            "Per the Librarian, a vel-shara is a speech with magical force.",
            List.of("S1: Glass Tide — chapter 9 (study-share-books) (books:sc-9)"),
            "companion:test", FindingsLedger.ClaimType.EXTRACTION);
        var r2 = FindingsLedger.reviewDrafts(store, HER);
        assertEquals(1, r2.retiredDuplicates());
        var retired = FindingsLedger.get(store, again).orElseThrow();
        assertEquals(FindingsLedger.State.RETIRED, retired.state());
        assertEquals(good, retired.supersededBy());

        // Accepted first in what she has established; retired never shown.
        var est = FindingsLedger.established(store, HER, "vel-shara speech", 5);
        assertEquals(FindingsLedger.State.ACCEPTED, est.getFirst().state());
        assertTrue(est.stream().noneMatch(f -> f.state() == FindingsLedger.State.RETIRED));
    }

    /**
     * A claim that shares its subject with an accepted one but differs on a date, a number,
     * a name, or a negation is a contradiction, not a restatement. Retiring it as a duplicate
     * would bury a sourced disagreement; both become disputed and point at each other.
     */
    @Test
    void review_marks_a_contradicting_claim_disputed_instead_of_retiring_it() {
        store.insertKnowledge("books:wp-3", "study-share-books", "The Thirty Years War — chapter 3",
            "The Peace of Westphalia was signed in 1648 at Osnabrück and Münster.", "study-share-books", "history", null);
        store.insertKnowledge("books:wp-9", "study-share-books", "A Later History — chapter 9",
            "The treaties were concluded in 1658, the author claims.", "study-share-books", "history", null);
        store.commitAll();
        var first = FindingsLedger.recordDraft(store, HER, "westphalia",
            "The Peace of Westphalia was signed in 1648.",
            List.of("S1: The Thirty Years War — chapter 3 (study-share-books) (books:wp-3)"),
            "companion:test", FindingsLedger.ClaimType.EXTRACTION);
        assertEquals(1, FindingsLedger.reviewDrafts(store, HER).accepted());

        var rival = FindingsLedger.recordDraft(store, HER, "westphalia",
            "The Peace of Westphalia was signed in 1658.",
            List.of("S1: A Later History — chapter 9 (study-share-books) (books:wp-9)"),
            "companion:test", FindingsLedger.ClaimType.EXTRACTION);
        var r = FindingsLedger.reviewDrafts(store, HER);
        assertEquals(1, r.disputed());
        assertEquals(0, r.retiredDuplicates());
        var a = FindingsLedger.get(store, first).orElseThrow();
        var b = FindingsLedger.get(store, rival).orElseThrow();
        assertEquals(FindingsLedger.State.DISPUTED, a.state());
        assertEquals(FindingsLedger.State.DISPUTED, b.state());
        assertEquals(rival, a.supersededBy(), "the accepted one points at what disputes it");
        assertEquals(first, b.supersededBy(), "the newcomer points at what it disputes");
        assertTrue(b.reviewNote().contains("1648") && b.reviewNote().contains("1658"), b.reviewNote());

        // A pure rewording of a disputed claim is neither accepted nor retired against it:
        // nothing accepted remains to restate, so it stands on its own sources.
        var reword = FindingsLedger.recordDraft(store, HER, "westphalia",
            "In 1648 the Peace of Westphalia was signed.",
            List.of("S1: The Thirty Years War — chapter 3 (study-share-books) (books:wp-3)"),
            "companion:test", FindingsLedger.ClaimType.EXTRACTION);
        var r3 = FindingsLedger.reviewDrafts(store, HER);
        assertEquals(1, r3.accepted());
        assertEquals(FindingsLedger.State.ACCEPTED, FindingsLedger.get(store, reword).orElseThrow().state());
    }

    @Test
    void load_bearing_difference_is_numbers_negations_and_names() {
        assertEquals(List.of("1648", "1658"), FindingsLedger.loadBearingDifference(
            "The Peace of Westphalia was signed in 1648.", "The Peace of Westphalia was signed in 1658."));
        assertEquals(List.of("not"), FindingsLedger.loadBearingDifference(
            "Adrun's vel-shara was a counter-program.", "Adrun's vel-shara was not a counter-program."));
        assertEquals(List.of("Kestan", "Raven"), FindingsLedger.loadBearingDifference(
            "The Librarian explained the vel-shara to Kestan.", "The Librarian explained the vel-shara to Raven."));
        assertTrue(FindingsLedger.loadBearingDifference(
            "A vel-shara is a speech with magical force, per the Librarian.",
            "Per the Librarian, a vel-shara is a speech with magical force.").isEmpty(),
            "a rewording differs on nothing that decides the claim");
    }

    @Test
    void state_changes_keep_the_id_and_bump_the_version() {
        var id = FindingsLedger.recordDraft(store, HER, "q", "Some claim about the Librarian.",
            List.of("S1: Glass Tide — chapter 9 (study-share-books) (books:sc-9)"), "steward", null);
        assertTrue(FindingsLedger.setState(store, id, FindingsLedger.State.DISPUTED, "rival reading", null));
        var f = FindingsLedger.get(store, id).orElseThrow();
        assertEquals(FindingsLedger.State.DISPUTED, f.state());
        assertEquals("rival reading", f.reviewNote());
        assertEquals(2, f.version());
        assertFalse(FindingsLedger.setState(store, "finding:nope", FindingsLedger.State.RETIRED, null, null));
        assertEquals(1, FindingsLedger.list(store, HER, FindingsLedger.State.DISPUTED, 10).size());
        assertEquals(0, FindingsLedger.list(store, HER, FindingsLedger.State.ACCEPTED, 10).size());
    }

    @Test
    void overlap_is_a_word_set_jaccard() {
        assertTrue(FindingsLedger.overlap("the cat sat on the mat", "the cat sat on the mat") > 0.99);
        assertTrue(FindingsLedger.overlap("Takeshi Kovacs was an Envoy", "vel-shara is a speech") < 0.1);
    }
}
