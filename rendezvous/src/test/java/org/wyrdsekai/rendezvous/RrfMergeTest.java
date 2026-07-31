package org.wyrdsekai.rendezvous;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RrfMergeTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";
    private static final String DID_C =
        "did:wyrd:z6MkC1111111111111111111111111111111111111111";

    private static DirectoryStore.SearchHit hit(String did, int score) {
        var m = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, "z", "Z", null,
            "t", "d", List.of(), null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-20T00:00:00Z", null);
        return new DirectoryStore.SearchHit(m, score);
    }

    @Test void mergeEmptyLists_returnsEmpty() {
        assertTrue(RrfMerge.merge(List.of(), List.of(), 10).isEmpty());
        assertTrue(RrfMerge.merge(null, null, 10).isEmpty());
    }

    @Test void mergeOneList_preservesRankOrder() {
        var hits = List.of(hit(DID_A, 100), hit(DID_B, 50), hit(DID_C, 10));
        var merged = RrfMerge.merge(hits, List.of(), 10);
        assertEquals(3, merged.size());
        assertEquals(DID_A, merged.get(0).manifest().did());
        assertEquals(DID_B, merged.get(1).manifest().did());
        assertEquals(DID_C, merged.get(2).manifest().did());
    }

    @Test void mergeAgreement_boostsSharedResults() {
        // Both rankers put DID_A first and DID_B second — the merge
        // should reflect strong consensus.
        var keyword = List.of(hit(DID_A, 100), hit(DID_B, 50));
        var semantic = List.of(hit(DID_A, 90), hit(DID_B, 45));
        var merged = RrfMerge.merge(keyword, semantic, 10);
        assertEquals(2, merged.size());
        assertEquals(DID_A, merged.get(0).manifest().did());
        assertEquals(DID_B, merged.get(1).manifest().did());
        // A should score ~2/(k+1); B ~2/(k+2). Both present in both lists.
        assertTrue(merged.get(0).score() > merged.get(1).score(),
            "A (position 0 in both) must rank above B (position 1)");
    }

    @Test void mergeDisagreement_favorsTopInBothLists() {
        // Keyword ranks DID_A first; semantic ranks DID_B first.
        // Both appear in only one list, so the top of one list (rank 0)
        // should end up at position 0 or 1 of the merged result.
        var keyword = List.of(hit(DID_A, 100));
        var semantic = List.of(hit(DID_B, 100));
        var merged = RrfMerge.merge(keyword, semantic, 10);
        assertEquals(2, merged.size());
        // Same RRF score for both (1/(k+1) each). Order is insertion.
        var dids = Set.of(DID_A, DID_B);
        assertTrue(dids.contains(merged.get(0).manifest().did()));
        assertTrue(dids.contains(merged.get(1).manifest().did()));
    }

    @Test void mergeDedupes() {
        // Same DID in both lists → one output entry, summed score.
        var keyword = List.of(hit(DID_A, 100));
        var semantic = List.of(hit(DID_A, 100));
        var merged = RrfMerge.merge(keyword, semantic, 10);
        assertEquals(1, merged.size());
        assertEquals(DID_A, merged.get(0).manifest().did());
    }

    @Test void mergeRespectsLimit() {
        var many = new ArrayList<DirectoryStore.SearchHit>();
        for (int i = 0; i < 20; i++) {
            many.add(hit("did:wyrd:z6Mk" + String.format("%040d", i), 100 - i));
        }
        assertEquals(5, RrfMerge.merge(many, List.of(), 5).size());
        assertEquals(0, RrfMerge.merge(many, List.of(), 0).size());
    }

    @Test void mergeTunableK() {
        // k controls the shape of the rank-decay curve. Larger k flattens
        // the gap between adjacent ranks. The top result's relative
        // advantage should shrink as k grows.
        var keyword = List.of(hit(DID_A, 100), hit(DID_B, 50));
        var small = RrfMerge.merge(keyword, List.of(), 2, 1);  // k=1
        var large = RrfMerge.merge(keyword, List.of(), 2, 1000);  // k=1000

        var smallRatio = (double) small.get(0).score() / small.get(1).score();
        var largeRatio = (double) large.get(0).score() / large.get(1).score();
        assertTrue(smallRatio > largeRatio,
            "smaller k produces steeper rank-based decay");
    }

    @Test void mergeStableAcrossCalls() {
        var keyword = List.of(hit(DID_A, 100), hit(DID_B, 50));
        var semantic = List.of(hit(DID_B, 80), hit(DID_A, 40));
        var m1 = RrfMerge.merge(keyword, semantic, 10);
        var m2 = RrfMerge.merge(keyword, semantic, 10);
        assertEquals(m1.size(), m2.size());
        for (int i = 0; i < m1.size(); i++) {
            assertEquals(m1.get(i).manifest().did(), m2.get(i).manifest().did());
        }
    }
}
