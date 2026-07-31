package org.wyrdsekai.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MemoryEntityForgeTest {

    private MemoryEntityStore store;
    private String jdbcUrl;
    private static final String DID = "did:wyrd:alice";

    @BeforeEach
    void setUp() {
        jdbcUrl = TestDb.createInMemory();
        store = new MemoryEntityStore(jdbcUrl);
    }

    @Test
    void consolidate_collapses_duplicate_entities_keeping_newest() {
        long t1 = System.currentTimeMillis();
        // Same (type, role, value) planted twice across turns — classic dupe
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", t1));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "pet", "name", "Mochi", t1 + 1000));

        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(result.entityDuplicatesDropped()).isEqualTo(1);
        var remaining = store.findAllByType(DID, "pet", 10);
        assertThat(remaining).hasSize(1);
        // Kept the newer one (mem-2)
        assertThat(remaining.getFirst().memoryId()).isEqualTo("mem-2");
    }

    @Test
    void consolidate_preserves_distinct_values_for_same_type_and_role() {
        long t1 = System.currentTimeMillis();
        // Changed occupation: analyst → engineer. Distinct values = distinct facts
        // with a temporal ordering; we keep both (append-only preserves history).
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "occupation", "current", "analyst", t1));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "occupation", "current", "data engineer", t1 + 1000));

        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(result.entityDuplicatesDropped()).isEqualTo(0);
        assertThat(store.findAllByType(DID, "occupation", 10)).hasSize(2);
    }

    @Test
    void consolidate_groups_null_roles_together() {
        long t1 = System.currentTimeMillis();
        // Both rows with role=null and same value — dedup
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "family", null, "sister", t1));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "family", null, "sister", t1 + 1000));

        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(result.entityDuplicatesDropped()).isEqualTo(1);
        assertThat(store.findAllByType(DID, "family", 10)).hasSize(1);
    }

    @Test
    void consolidate_scopes_by_did_no_cross_agent_deletion() {
        long t1 = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", t1));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                "did:wyrd:bob", "mem-2", "pet", "name", "Mochi", t1));

        // Alice has no dupes — Bob has a row with the same (type, role, value)
        // but it's a different DID and must not be touched.
        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(result.entityDuplicatesDropped()).isEqualTo(0);
        assertThat(store.findAllByType("did:wyrd:bob", "pet", 10)).hasSize(1);
    }

    @Test
    void consolidate_dedups_edges() {
        long t = System.currentTimeMillis();
        // Seed entity rows so edges aren't treated as dangling
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "occupation", "current", "data engineer", t));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "occupation", "current", "data engineer", t + 1000));
        store.insertEdge(new MemoryEntityStore.EdgeRow(
                DID, "I", "works_as", "data engineer", "mem-1", 1.0));
        store.insertEdge(new MemoryEntityStore.EdgeRow(
                DID, "I", "works_as", "data engineer", "mem-2", 1.0));

        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(result.edgeDuplicatesDropped()).isEqualTo(1);
        assertThat(store.findEdgesTouching(DID, "data engineer", 10)).hasSize(1);
    }

    @Test
    void consolidate_prunes_dangling_edges() {
        long t = System.currentTimeMillis();
        // Edge references mem-ghost — memory_entities has no row with that id,
        // so the edge is orphaned and pruneable.
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-real", "pet", "name", "Mochi", t));
        store.insertEdge(new MemoryEntityStore.EdgeRow(
                DID, "I", "once_owned", "Rex", "mem-ghost", 1.0));
        store.insertEdge(new MemoryEntityStore.EdgeRow(
                DID, "Mochi", "is_a", "cat", "mem-real", 1.0));

        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(result.danglingEdgesDropped()).isEqualTo(1);
        // The real edge survives
        var survivors = store.findEdgesTouching(DID, "Mochi", 10);
        assertThat(survivors).hasSize(1);
    }

    @Test
    void consolidate_idempotent_second_pass_is_noop() {
        long t = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", t));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "pet", "name", "Mochi", t + 1000));

        var r1 = MemoryEntityForge.consolidate(jdbcUrl, DID);
        var r2 = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(r1.totalDropped()).isEqualTo(1);
        assertThat(r2.totalDropped()).isEqualTo(0);
    }

    @Test
    void consolidate_on_empty_store_returns_zero() {
        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);
        assertThat(result.totalDropped()).isEqualTo(0);
    }

    @Test
    void consolidate_null_inputs_return_zero() {
        assertThat(MemoryEntityForge.consolidate(null, DID).totalDropped()).isEqualTo(0);
        assertThat(MemoryEntityForge.consolidate(jdbcUrl, null).totalDropped()).isEqualTo(0);
    }

    @Test
    void stale_prune_keeps_newest_per_type_role_even_if_ancient() {
        // Single row, very old. Must NOT be pruned — it's the sole remembered
        // value for (pet, name). Newest-per-group protection.
        long veryOld = System.currentTimeMillis() - (365L * 24 * 60 * 60 * 1000);
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", veryOld));

        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(result.staleEntitiesDropped()).isEqualTo(0);
        assertThat(store.findAllByType(DID, "pet", 10)).hasSize(1);
    }

    @Test
    void stale_prune_drops_superseded_old_values() {
        // Occupation changed: old analyst (ancient), new engineer (recent).
        // Stale prune should drop the analyst because a newer row exists for
        // the same (occupation, current) group. Engineer survives.
        long veryOld = System.currentTimeMillis() - (365L * 24 * 60 * 60 * 1000);
        long recent = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-old", "occupation", "current", "analyst", veryOld));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-new", "occupation", "current", "data engineer", recent));

        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        assertThat(result.staleEntitiesDropped()).isEqualTo(1);
        var remaining = store.findAllByType(DID, "occupation", 10);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().entityValue()).isEqualTo("data engineer");
    }

    @Test
    void stale_prune_leaves_recent_rows_alone() {
        long recent = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);  // 7 days
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "book", "reading", "Dune", recent));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "book", "reading", "Gravity's Rainbow",
                System.currentTimeMillis()));

        var result = MemoryEntityForge.consolidate(jdbcUrl, DID);

        // 7 days < 90-day TTL, so no stale prune
        assertThat(result.staleEntitiesDropped()).isEqualTo(0);
        assertThat(store.findAllByType(DID, "book", 10)).hasSize(2);
    }
}
