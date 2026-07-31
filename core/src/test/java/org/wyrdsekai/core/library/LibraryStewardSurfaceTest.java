package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.BridgeDataProviderImpl;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Steward Library surfaces — the
 * BridgeDataProviderImpl methods behind the Study bookshelf and the
 * Library card catalog: available packs, install dispatch, proposal
 * list/approve/reject by id prefix, and top repeated misses.
 */
class LibraryStewardSurfaceTest {

    @TempDir
    Path tmp;

    private WyrdLuceneStore store;
    private BridgeDataProviderImpl provider;

    @BeforeEach
    void setUp() {
        LibraryServices.reset();
        LibraryServices.init(tmp.resolve("library"));
        store = new WyrdLuceneStore(tmp.resolve("lucene"), 384);
        provider = new BridgeDataProviderImpl(null, null, null);
        provider.setLuceneStore(store);
        provider.setPacksDir(tmp.resolve("packs"));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
        LibraryServices.reset();
    }

    @Test
    void availablePacksListsRegistryWithMarkers() {
        var out = provider.listAvailablePacks();
        assertTrue(out.contains("simple-wikipedia"), out);
        assertTrue(out.contains("[available]"), "fresh store should show available markers:\n" + out);
        assertTrue(out.contains("tier "), out);
        assertTrue(out.contains("Install with: install <pack-name>"), out);
    }

    @Test
    void installRejectsUnknownAndBlankPacks() {
        assertTrue(provider.installKnowledgePack("no-such-pack-xyz").startsWith("Unknown pack"));
        assertTrue(provider.installKnowledgePack("  ").contains("Which pack?"));
        assertTrue(provider.installKnowledgePack(null).contains("Which pack?"));
    }

    @Test
    void installUnavailableWithoutPacksDir() {
        var bare = new BridgeDataProviderImpl(null, null, null);
        bare.setLuceneStore(store);
        assertEquals("Pack install not available on this node",
            bare.installKnowledgePack("simple-wikipedia"));
    }

    @Test
    void proposalLifecycleByIdPrefix() {
        var table = LibraryServices.arrivalTable();
        var p1 = table.propose(ProposedPack.of(
            "precision rifle reloading", "load data and technique",
            Provenance.TrustTier.UNKNOWN, "gap_detection", "wyrd"));
        var p2 = table.propose(ProposedPack.of(
            "sourdough starters", "household baking asks",
            Provenance.TrustTier.UNKNOWN, "acquire", "vesna"));

        var listed = provider.listLibraryProposals();
        assertTrue(listed.contains("precision rifle reloading"), listed);
        assertTrue(listed.contains("sourdough starters"), listed);
        assertTrue(listed.contains(p1.id().substring(0, 8)), listed);
        assertTrue(listed.contains("proposed by vesna"), listed);

        // Approve by 8-char prefix; no sources attached → no ingest dispatched.
        var approved = provider.approveLibraryProposal(p1.id().substring(0, 8), "operator");
        assertTrue(approved.contains("Approved 'precision rifle reloading'"), approved);
        assertEquals(ProposedPack.Status.APPROVED, table.get(p1.id()).orElseThrow().status());

        // Reject the other with a reason.
        var rejected = provider.rejectLibraryProposal(p2.id().substring(0, 8), "operator", "not now");
        assertTrue(rejected.contains("Rejected 'sourdough starters'"), rejected);
        var p2After = table.get(p2.id()).orElseThrow();
        assertEquals(ProposedPack.Status.REJECTED, p2After.status());
        assertEquals("not now", p2After.rejectionReason());

        // Nothing pending now.
        assertEquals("No pending Library proposals.", provider.listLibraryProposals());
    }

    @Test
    void proposalPrefixMissReportsCleanly() {
        assertTrue(provider.approveLibraryProposal("zzzzzzzz", "operator")
            .contains("No pending proposal matching"));
        assertTrue(provider.rejectLibraryProposal("zzzzzzzz", "operator", "x")
            .contains("No pending proposal matching"));
    }

    @Test
    void topMissesSurfacesRepeatedTerms() {
        var rl = LibraryServices.readingLog();
        assertEquals("No repeated library-search misses recently — the Library is keeping up.",
            provider.libraryTopMisses());
        rl.recordMiss("precision rifle ballistics", "did:wyrd:test");
        rl.recordMiss("precision rifle scopes", "did:wyrd:test");
        rl.recordMiss("precision rifle ammunition", "did:wyrd:test");
        var out = provider.libraryTopMisses();
        assertTrue(out.contains("precision"), out);
        assertTrue(out.contains("missed 3x"), out);
    }

    @Test
    void surfacesDegradeWithoutWiring() {
        var bare = new BridgeDataProviderImpl(null, null, null);
        assertEquals("Pack registry not available", bare.listAvailablePacks());
        // LibraryServices is initialized in setUp, so proposals/misses still answer;
        // the degraded path for those is LibraryServices.reset() — covered implicitly
        // because every method null-checks the singleton.
        LibraryServices.reset();
        assertEquals("No pending Library proposals", bare.listLibraryProposals());
        assertEquals("Proposals not available", bare.approveLibraryProposal("ab", "x"));
        assertEquals("Proposals not available", bare.rejectLibraryProposal("ab", "x", "y"));
        assertEquals("No reading log available", bare.libraryTopMisses());
    }
}
