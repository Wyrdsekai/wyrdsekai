package org.wyrdsekai.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MemoryEntityStoreTest {

    private MemoryEntityStore store;
    private static final String DID = "did:wyrd:alice";

    @BeforeEach
    void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        store = new MemoryEntityStore(jdbcUrl);
    }

    @Test
    void insert_and_find_latest_by_type() {
        long now = System.currentTimeMillis();
        assertThat(store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", now))).isTrue();

        var hit = store.findLatest(DID, "pet", "name");
        assertThat(hit).isPresent();
        assertThat(hit.get().entityValue()).isEqualTo("Mochi");
        assertThat(hit.get().memoryId()).isEqualTo("mem-1");
    }

    @Test
    void find_latest_returns_newest_on_contradiction() {
        long t1 = System.currentTimeMillis();
        long t2 = t1 + 1000;
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "occupation", "current", "data analyst", t1));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "occupation", "current", "data engineer", t2));

        var hit = store.findLatest(DID, "occupation", "current");
        assertThat(hit).isPresent();
        assertThat(hit.get().entityValue()).isEqualTo("data engineer");
    }

    @Test
    void find_latest_type_only_ignores_role() {
        long t1 = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "location", "hometown", "Portland", t1));

        var hit = store.findLatest(DID, "location", null);
        assertThat(hit).isPresent();
        assertThat(hit.get().entityValue()).isEqualTo("Portland");
    }

    @Test
    void findLatest_scopes_by_did() {
        long t1 = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", t1));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                "did:wyrd:bob", "mem-2", "pet", "name", "Rex", t1));

        var hit = store.findLatest(DID, "pet", "name");
        assertThat(hit).isPresent();
        assertThat(hit.get().entityValue()).isEqualTo("Mochi");
    }

    @Test
    void batch_insert_entities() {
        long now = System.currentTimeMillis();
        var rows = List.of(
                new MemoryEntityStore.EntityRow(DID, "mem-1", "pet", "name", "Mochi", now),
                new MemoryEntityStore.EntityRow(DID, "mem-1", "pet", "type", "cat", now),
                new MemoryEntityStore.EntityRow(DID, "mem-2", "allergy", "food", "cashews", now));

        int inserted = store.insertEntities(rows);
        assertThat(inserted).isEqualTo(3);
        assertThat(store.countEntities(DID)).isEqualTo(3);
    }

    @Test
    void findAllByType_ordered_newest_first() {
        long base = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "book", "reading", "Dune", base));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "book", "reading", "Gravity's Rainbow", base + 1000));

        var all = store.findAllByType(DID, "book", 10);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).entityValue()).isEqualTo("Gravity's Rainbow");
        assertThat(all.get(1).entityValue()).isEqualTo("Dune");
    }

    @Test
    void findByValue_returns_matches() {
        long now = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", now));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "type", "cat", now));

        var hits = store.findByValue(DID, "Mochi", 5);
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().entityType()).isEqualTo("pet");
    }

    @Test
    void findLatest_empty_returns_optional_empty() {
        assertThat(store.findLatest(DID, "pet", "name")).isEmpty();
    }

    @Test
    void insert_and_find_edges() {
        assertThat(store.insertEdge(new MemoryEntityStore.EdgeRow(
                DID, "I", "works_as", "data engineer", "mem-1", 1.0))).isTrue();

        var edges = store.findEdgesTouching(DID, "data engineer", 5);
        assertThat(edges).hasSize(1);
        assertThat(edges.getFirst().subject()).isEqualTo("I");
        assertThat(edges.getFirst().predicate()).isEqualTo("works_as");
    }

    @Test
    void findEntitiesByMemoryId_returns_all_entities_for_memory() {
        long now = System.currentTimeMillis();
        store.insertEntities(List.of(
                new MemoryEntityStore.EntityRow(DID, "mem-1", "pet", "name", "Mochi", now),
                new MemoryEntityStore.EntityRow(DID, "mem-1", "pet", "type", "cat", now),
                new MemoryEntityStore.EntityRow(DID, "mem-2", "book", "reading", "Dune", now)));

        var entities = store.findEntitiesByMemoryId(DID, "mem-1", 10);
        assertThat(entities).hasSize(2);
        assertThat(entities).extracting(MemoryEntityStore.EntityRow::entityValue)
                .containsExactlyInAnyOrder("Mochi", "cat");
    }

    @Test
    void findEntitiesByMemoryId_scopes_by_did() {
        long now = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                "did:wyrd:bob", "mem-1", "pet", "name", "Rex", now));

        var hits = store.findEntitiesByMemoryId(DID, "mem-1", 10);
        assertThat(hits).isEmpty();
    }

    @Test
    void edge_traversal_surfaces_linked_memory() {
        // Simulate Day 4-5 multi-hop: hop-1 finds mem-1 (sister fact),
        // entity "sister" has an edge to "next_weekend" via mem-2 (visit time).
        // Hop-2 uses the edge to surface mem-2 even though the probe was about sister.
        long now = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "family", null, "sister", now));
        store.insertEdge(new MemoryEntityStore.EdgeRow(
                DID, "sister", "visiting_at", "next_weekend", "mem-2", 1.0));

        // Seed from hop-1 (mem-1): entities = ["sister"]
        var entities = store.findEntitiesByMemoryId(DID, "mem-1", 10);
        assertThat(entities).hasSize(1);
        assertThat(entities.getFirst().entityValue()).isEqualTo("sister");

        // Traverse edges from "sister" → mem-2 surfaces
        var edges = store.findEdgesTouching(DID, "sister", 10);
        assertThat(edges).hasSize(1);
        assertThat(edges.getFirst().memoryId()).isEqualTo("mem-2");
    }

    @Test
    void findAllForDid_returns_latest_per_type_role() {
        long t1 = 1_000_000L;
        long t2 = 2_000_000L;
        long t3 = 3_000_000L;
        // Two facts for the same key — only the latest should be returned.
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "occupation", "current", "Mercari", t1));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "occupation", "current", "self-employed", t3));
        // Different key, keep alongside.
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-3", "location", "workplace", "San Francisco", t2));

        var rows = store.findAllForDid(DID, 20);
        assertThat(rows).hasSize(2);
        // Ordered newest-first.
        assertThat(rows.get(0).entityValue()).isEqualTo("self-employed");
        assertThat(rows.get(0).timestamp()).isEqualTo(t3);
        assertThat(rows.get(1).entityValue()).isEqualTo("San Francisco");
    }

    @Test
    void findAllForDid_handles_null_role() {
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-a", "allergy", null, "cashews", 1_000L));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-b", "allergy", null, "shellfish", 2_000L));
        var rows = store.findAllForDid(DID, 10);
        // Same (type, null role) → only newest.
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().entityValue()).isEqualTo("shellfish");
    }

    @Test
    void findAllForDid_scopes_by_did() {
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", 1_000L));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                "did:wyrd:bob", "mem-2", "pet", "name", "Rex", 2_000L));
        var rows = store.findAllForDid(DID, 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().entityValue()).isEqualTo("Mochi");
    }

    @Test
    void findAllForDid_caps_at_limit() {
        for (int i = 0; i < 5; i++) {
            store.insertEntity(new MemoryEntityStore.EntityRow(
                    DID, "mem-" + i, "type-" + i, "role", "value-" + i, 1_000L + i));
        }
        var rows = store.findAllForDid(DID, 3);
        assertThat(rows).hasSize(3);
    }

    @Test
    void findEdgesTouching_matches_subject_or_object() {
        store.insertEdge(new MemoryEntityStore.EdgeRow(
                DID, "Mochi", "is_a", "cat", "mem-1", 1.0));
        store.insertEdge(new MemoryEntityStore.EdgeRow(
                DID, "sister", "visiting_at", "next_weekend", "mem-2", 1.0));

        var hitMochi = store.findEdgesTouching(DID, "Mochi", 5);
        assertThat(hitMochi).hasSize(1);

        var hitCat = store.findEdgesTouching(DID, "cat", 5);
        assertThat(hitCat).hasSize(1);

        var hitWeekend = store.findEdgesTouching(DID, "next_weekend", 5);
        assertThat(hitWeekend).hasSize(1);
    }
}
