package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving a companion's memories from one identity to another.
 *
 * <p>Her memories live in Lucene under {@code agent_did}, and nothing re-indexes
 * them from SQL. Folding two identities in {@code world.db} alone would leave
 * every memory from the folded identity indexed under a DID that no longer
 * answers — present in the file, invisible to her. The same failure this whole
 * arc keeps producing: there, but unfindable.</p>
 *
 * <p>Two properties matter and both are easy to get wrong:</p>
 *
 * <ul>
 *   <li>A document cannot be round-tripped through the reader —
 *       {@code storedFields()} omits the analyzed content field, so a copied
 *       document is present but <b>unsearchable</b>. It must be searchable after.</li>
 *   <li>The vector is likewise absent from the stored document. Dropping it
 *       silently costs semantic recall, so it has to be recovered from the
 *       index and carried across.</li>
 * </ul>
 */
class RewriteAgentDidTest {

    @TempDir Path tmp;
    private WyrdLuceneStore store;

    private static final String OLD = "did:key:z6MkOldIdentity";
    private static final String NEW = "did:key:z6MkNewIdentity";
    private static final int DIM = 384;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tmp, DIM);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
    }

    /** A deterministic unit vector of the index's dimension. */
    private static List<Float> vec(int dim, float lead) {
        var v = new ArrayList<Float>(dim);
        v.add(lead);
        for (int i = 1; i < dim; i++) v.add(0.0f);
        return v;
    }

    private void seedMemory(String id, String did, String text, List<Float> v) {
        store.insertMemoryItem(id, did, "working_memory", text, v,
            System.currentTimeMillis(), "nexus");
    }

    // ─── THE case ─────────────────────────────────────────────────────

    /** Memories must move, and must still be findable afterwards. */
    @Test
    void memories_move_and_stay_searchable() {
        seedMemory("m1", OLD, "the librarian explained the vel-shara of Adrun", vec(DIM, 1.0f));
        seedMemory("m2", OLD, "we talked about glass tide on the roof", vec(DIM, 1.0f));

        long moved = store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null);

        assertThat(moved).isEqualTo(2);
        var found = store.searchMemory(NEW, "vel-shara", null, 10);
        assertThat(found)
            .as("a rebuilt document that is present but unsearchable is the bug, "
                + "not the fix")
            .isNotEmpty();
        assertThat(found.getFirst().content()).contains("vel-shara");
    }

    /** Nothing may remain under the old identity. */
    @Test
    void nothing_is_left_behind() {
        seedMemory("m1", OLD, "a memory", vec(DIM, 1.0f));

        store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null);

        assertThat(store.searchMemory(OLD, "memory", null, 10))
            .as("the folded identity must own nothing afterwards")
            .isEmpty();
    }

    /** Another agent's memories must not be touched. */
    @Test
    void other_agents_are_untouched() {
        var third = "did:key:z6MkSomeoneElseEntirely";
        seedMemory("m1", OLD, "mine", vec(DIM, 1.0f));
        seedMemory("m2", third, "theirs", vec(DIM, 1.0f));

        store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null);

        assertThat(store.searchMemory(third, "theirs", null, 10))
            .as("a rebind is between two identities, not across the household")
            .hasSize(1);
    }

    /** The embedding must survive the move — that is the whole point of the effort. */
    @Test
    void embeddings_are_carried_across() {
        var v = vec(DIM, 1.0f);
        seedMemory("m1", OLD, "something worth remembering", v);

        store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null);

        var byVector = store.searchMemory(NEW, null, v, 5);
        assertThat(byVector)
            .as("dense search finds nothing if the vector was dropped in the rewrite")
            .isNotEmpty();
        assertThat(byVector.getFirst().id()).isEqualTo("m1");
    }

    /** A document that never had a vector must still move, text-only. */
    @Test
    void documents_without_embeddings_still_move() {
        seedMemory("m1", OLD, "no vector on this one", null);

        long moved = store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null);

        assertThat(moved).isEqualTo(1);
        assertThat(store.searchMemory(NEW, "vector", null, 10)).isNotEmpty();
    }

    /** Stored metadata must survive, not just the text. */
    @Test
    void stored_fields_survive() {
        seedMemory("m1", OLD, "in the nexus", vec(DIM, 1.0f));

        store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null);

        var r = store.searchMemory(NEW, "nexus", null, 5).getFirst();
        assertThat(r.metadata()).containsEntry("room_id", "nexus");
        assertThat(r.metadata()).containsEntry("item_type", "working_memory");
    }

    /** Batching must not lose or duplicate anything. */
    @Test
    void batches_cover_everything_exactly_once() {
        for (int i = 0; i < 25; i++) {
            seedMemory("m" + i, OLD, "memory number " + i, vec(DIM, 1.0f));
        }
        var seen = new ArrayList<Long>();

        long moved = store.rewriteAgentDid(
            SearchCollections.MEMORY_ITEMS, OLD, NEW, 7, seen::add);

        assertThat(moved).isEqualTo(25);
        assertThat(seen.getLast()).isEqualTo(25L);
        assertThat(store.searchMemory(OLD, "memory", null, 50)).isEmpty();
        assertThat(store.searchMemory(NEW, "memory", null, 50)).hasSize(25);
    }

    /** Running it again is a no-op, not a duplication. */
    @Test
    void is_idempotent() {
        seedMemory("m1", OLD, "once", vec(DIM, 1.0f));

        store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null);
        long second = store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null);

        assertThat(second).isZero();
        assertThat(store.searchMemory(NEW, "once", null, 10)).hasSize(1);
    }

    /** Degenerate arguments do nothing rather than something surprising. */
    @Test
    void refuses_degenerate_arguments() {
        seedMemory("m1", OLD, "x", vec(DIM, 1.0f));

        assertThat(store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, OLD, 10, null))
            .isZero();
        assertThat(store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, null, NEW, 10, null))
            .isZero();
        assertThat(store.rewriteAgentDid(null, OLD, NEW, 10, null)).isZero();
        assertThat(store.searchMemory(OLD, "x", null, 10)).hasSize(1);
    }

    /** An empty collection is fine, not an error. */
    @Test
    void an_identity_with_no_memories_is_a_no_op() {
        assertThat(store.rewriteAgentDid(SearchCollections.MEMORY_ITEMS, OLD, NEW, 100, null))
            .isZero();
    }
}
