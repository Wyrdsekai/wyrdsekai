package org.wyrdsekai.core.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit W4 (2026-09-28): memory_entities had no person column, the facts block listed every
 * person's facts, and recall answered "what am I allergic to?" by the companion's DID alone,
 * so one person got another's answer. Every read is now for one reader.
 */
@Tag("integration")
class EachPersonsFactsStayTheirsTest {

    private static final String HER = "did:wyrd:mia";
    private static final String ALICE = MemoryOriginTest.ALICE;
    private static final String BOB = MemoryOriginTest.BOB;

    private MemoryEntityStore store;

    @BeforeEach
    void setUp() {
        PersonIds.resetForTesting(MemoryOriginTest.household());
        store = new MemoryEntityStore(TestDb.createInMemory());
        long t = System.currentTimeMillis();
        store.insertEntity(new MemoryEntityStore.EntityRow(
            HER, "wm-a", "allergy", "food", "cashews", t, MemoryOrigin.privateTo(ALICE)));
        store.insertEntity(new MemoryEntityStore.EntityRow(
            HER, "wm-o", "pet", "name", "Mochi", t + 1, MemoryOrigin.openFrom(ALICE)));
        store.insertEdge(new MemoryEntityStore.EdgeRow(
            HER, "Alice", "allergic_to", "cashews", "wm-a", 1.0, MemoryOrigin.privateTo(ALICE)));
    }

    @AfterEach
    void forget() {
        PersonIds.resetForTesting(null);
    }

    @Test
    @DisplayName("recall: Bob asking what he is allergic to is never told Alice's answer")
    void recallIsTheAskersOwn() {
        var resolver = new EntityResolver(store);
        var intent = ProbeClassifier.classify("what am I allergic to?");
        assertThat(intent).isNotNull();
        assertThat(resolver.resolve(HER, intent, MemoryReader.of(BOB, true))).isEmpty();
        assertThat(resolver.resolve(HER, intent, MemoryReader.NO_ONE)).isEmpty();
        assertThat(resolver.resolve(HER, intent, MemoryReader.of(ALICE, false)))
            .get().extracting(EntityResolver.EntityHit::entityValue).isEqualTo("cashews");
    }

    @Test
    @DisplayName("the facts block for Bob holds nothing Alice told her, privately or openly")
    void factsBlockIsTheReadersOwn() {
        assertThat(store.findAllForDid(HER, 30, MemoryReader.of(BOB, false))).isEmpty();
        assertThat(store.findAllForDid(HER, 30, MemoryReader.of(ALICE, false)))
            .extracting(MemoryEntityStore.EntityRow::entityValue)
            .containsExactlyInAnyOrder("cashews", "Mochi");
    }

    @Test
    @DisplayName("the memory hop: Bob's turn reaches Alice's open words but never her private ones")
    void hopIsFilteredByReader() {
        var bob = MemoryReader.of(BOB, false);
        assertThat(store.findEntitiesByMemoryId(HER, "wm-a", 6, bob)).isEmpty();
        assertThat(store.findEdgesTouching(HER, "cashews", 6, bob)).isEmpty();
        assertThat(store.findByValue(HER, "Mochi", 6, bob)).hasSize(1);
        assertThat(store.findEdgesTouching(HER, "cashews", 6, MemoryReader.of(ALICE, false))).hasSize(1);
    }

    @Test
    @DisplayName("rows of unknown origin are read only with her bondholder, and a label moves them")
    void unknownRowsAndLabelling() {
        store.insertEntity(new MemoryEntityStore.EntityRow(
            HER, "wm-old", "location", "home", "Lisbon", System.currentTimeMillis()));
        assertThat(store.findByValue(HER, "Lisbon", 6, MemoryReader.of(BOB, false))).isEmpty();
        assertThat(store.findByValue(HER, "Lisbon", 6, MemoryReader.of(ALICE, true))).hasSize(1);
        assertThat(store.countUnknown(HER)).isEqualTo(1);

        assertThat(store.labelUnknown(HER, "wm-old", MemoryOrigin.privateTo(BOB))).isEqualTo(1);
        assertThat(store.countUnknown(HER)).isZero();
        assertThat(store.findByValue(HER, "Lisbon", 6, MemoryReader.of(ALICE, true))).isEmpty();
        assertThat(store.findByValue(HER, "Lisbon", 6, MemoryReader.of(BOB, false))).hasSize(1);
    }

    @Test
    @DisplayName("consolidation never folds one person's fact into another's")
    void forgeKeepsPeopleApart(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var s = new MemoryEntityStore(jdbc);
        long t = System.currentTimeMillis();
        s.insertEntity(new MemoryEntityStore.EntityRow(HER, "m1", "allergy", "food", "cashews", t,
            MemoryOrigin.privateTo(ALICE)));
        s.insertEntity(new MemoryEntityStore.EntityRow(HER, "m2", "allergy", "food", "cashews", t + 5,
            MemoryOrigin.privateTo(BOB)));
        MemoryEntityForge.consolidate(jdbc, HER);
        assertThat(s.findAllByType(HER, "allergy", 10, MemoryReader.of(ALICE, false))).hasSize(1);
        assertThat(s.findAllByType(HER, "allergy", 10, MemoryReader.of(BOB, false))).hasSize(1);
    }

    @Test
    @DisplayName("migration 12: a database from before origins gains the columns; its rows are of unknown origin")
    void migrationKeepsOldRows(@TempDir Path dir) throws Exception {
        var url = "jdbc:sqlite:" + dir.resolve("world.db").toAbsolutePath();
        try (var conn = DriverManager.getConnection(url); var st = conn.createStatement()) {
            st.execute("CREATE TABLE memory_entities(id INTEGER PRIMARY KEY AUTOINCREMENT, did TEXT NOT NULL, "
                + "memory_id TEXT NOT NULL, entity_type TEXT NOT NULL, entity_role TEXT, "
                + "entity_value TEXT NOT NULL, timestamp INTEGER NOT NULL, created_at INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE memory_edges(id INTEGER PRIMARY KEY AUTOINCREMENT, did TEXT NOT NULL, "
                + "subject TEXT NOT NULL, predicate TEXT NOT NULL, object TEXT NOT NULL, memory_id TEXT NOT NULL, "
                + "confidence REAL NOT NULL DEFAULT 1.0, created_at INTEGER NOT NULL DEFAULT 0)");
            st.execute("INSERT INTO memory_entities(did, memory_id, entity_type, entity_role, entity_value, timestamp) "
                + "VALUES ('" + HER + "', 'wm-1', 'allergy', 'food', 'cashews', 1)");
        }
        SchemaInitializer.initialize(dir.resolve("world.db"));
        var s = new MemoryEntityStore(url);
        assertThat(s.countUnknown(HER)).isEqualTo(1);
        assertThat(s.findAllByType(HER, "allergy", 10, MemoryReader.of(BOB, false))).isEmpty();
        assertThat(s.findAllByType(HER, "allergy", 10, MemoryReader.of(ALICE, true))).hasSize(1);
        // A fresh database has none.
        var fresh = new MemoryEntityStore(SchemaInitializer.initialize(dir.resolve("fresh.db")));
        assertThat(fresh.countUnknown(HER)).isZero();
    }
}
