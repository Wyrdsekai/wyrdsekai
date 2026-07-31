package org.wyrdsekai.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.memory.ProbeClassifier.ProbeIntent;
import org.wyrdsekai.core.memory.ProbeClassifier.Temporal;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class EntityResolverTest {

    private MemoryEntityStore store;
    private EntityResolver resolver;
    private static final String DID = "did:wyrd:alice";

    @BeforeEach
    void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        store = new MemoryEntityStore(jdbcUrl);
        resolver = new EntityResolver(store);
    }

    @Test
    void resolve_pet_name_latest() {
        long t = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "pet", "name", "Mochi", t));

        var intent = new ProbeIntent("pet", "name", Temporal.ANY);
        var hit = resolver.resolve(DID, intent);

        assertThat(hit).isPresent();
        assertThat(hit.get().entityValue()).isEqualTo("Mochi");
    }

    @Test
    void resolve_contradiction_returns_latest() {
        long t1 = System.currentTimeMillis();
        long t2 = t1 + 10_000;
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "occupation", "current", "analyst", t1));
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-2", "occupation", "current", "data engineer", t2));

        var hit = resolver.resolve(DID,
                new ProbeIntent("occupation", "current", Temporal.LATEST));
        assertThat(hit).isPresent();
        assertThat(hit.get().entityValue()).isEqualTo("data engineer");
    }

    @Test
    void resolve_role_fallback_to_any_role() {
        long t = System.currentTimeMillis();
        // Planted with role=null, probe asks for role="current"
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "occupation", null, "data engineer", t));

        var hit = resolver.resolve(DID,
                new ProbeIntent("occupation", "current", Temporal.LATEST));
        assertThat(hit).isPresent();
        assertThat(hit.get().entityValue()).isEqualTo("data engineer");
    }

    @Test
    void resolve_miss_returns_empty() {
        var hit = resolver.resolve(DID,
                new ProbeIntent("pet", "name", Temporal.ANY));
        assertThat(hit).isEmpty();
    }

    @Test
    void resolve_null_intent_returns_empty() {
        assertThat(resolver.resolve(DID, null)).isEmpty();
    }

    @Test
    void resolve_scopes_by_did() {
        long t = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                "did:wyrd:bob", "mem-1", "pet", "name", "Rex", t));

        // Alice shouldn't see Bob's pet
        var hit = resolver.resolve(DID, new ProbeIntent("pet", "name", Temporal.ANY));
        assertThat(hit).isEmpty();
    }

    @Test
    void end_to_end_classify_then_resolve() {
        long t = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
                DID, "mem-1", "location", "hometown", "Portland", t));

        // Full round-trip: user question → ProbeClassifier → EntityResolver
        var intent = ProbeClassifier.classify("where did I grow up");
        assertThat(intent).isNotNull();

        var hit = resolver.resolve(DID, intent);
        assertThat(hit).isPresent();
        assertThat(hit.get().entityValue()).isEqualTo("Portland");
    }
}
