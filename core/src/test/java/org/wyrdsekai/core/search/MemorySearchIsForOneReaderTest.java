package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.memory.MemoryOrigin;
import org.wyrdsekai.core.memory.MemoryReader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit W4 (2026-09-28): the companion's memory search was filtered by her DID only, so the
 * hop that fills "Retrieved Memories" read one person's private words into another's turn.
 */
class MemorySearchIsForOneReaderTest {

    private static final int DIM = 4;
    private static final String HER = "did:wyrd:mia";
    private static final String ALICE = "did:key:alice";
    private static final String BOB = "did:key:bob";

    @TempDir
    Path dir;
    private WyrdLuceneStore store;

    @BeforeEach
    void setUp() {
        PersonIds.resetForTesting(new PersonIdentityResolver("jdbc:sqlite::memory:") {
            @Override
            public Optional<String> resolve(String id) {
                return Optional.ofNullable(Map.of("Alice", ALICE, "Bob", BOB).get(id));
            }
        });
        store = new WyrdLuceneStore(dir, DIM);
        long t = System.currentTimeMillis();
        store.insertMemoryItem("wm-1", HER, "working_memory", "Alice told me her biopsy results are due Friday",
            null, t, "study-alice", MemoryOrigin.privateTo(ALICE));
        store.insertMemoryItem("wm-2", HER, "working_memory", "Everyone at the hearth talked about the biopsy of the old oak",
            null, t, "hearth", MemoryOrigin.openFrom(BOB));
        store.insertMemoryItem("wm-3", HER, "working_memory", "I walked to the biopsy garden alone",
            null, t, "garden", MemoryOrigin.OWN);
        store.commitAll();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
        PersonIds.resetForTesting(null);
    }

    private List<String> ids(MemoryReader reader) {
        return store.searchMemory(HER, "biopsy", null, 10, WyrdLuceneStore.SearchMode.SET_UNION, reader)
            .stream().map(WyrdLuceneStore.SearchResult::id).sorted().toList();
    }

    @Test
    @DisplayName("Bob's turn finds what was said openly and her own, never Alice's private words")
    void bobNeverReadsAlicesPrivateWords() {
        assertThat(ids(MemoryReader.of(BOB, true))).containsExactly("wm-2", "wm-3");
        assertThat(ids(MemoryReader.NO_ONE)).containsExactly("wm-2", "wm-3");
        assertThat(ids(MemoryReader.of(ALICE, false))).containsExactly("wm-1", "wm-2", "wm-3");
    }

    @Test
    @DisplayName("the audience travels with the item, so a hop by id can be checked too")
    void audienceIsStored() {
        var doc = store.getById(SearchCollections.MEMORY_ITEMS, "wm-1");
        assertThat(doc.metadata().get(WyrdLuceneStore.FIELD_AUDIENCE)).isEqualTo("person:" + ALICE);
        assertThat(MemoryReader.of(BOB, false).mayReadAudience(
            (String) doc.metadata().get(WyrdLuceneStore.FIELD_AUDIENCE))).isFalse();
    }

    @Test
    @DisplayName("earlier memories are found by no one until labelled; labelling names their teller")
    void labellingEarlierMemories() {
        long t = System.currentTimeMillis();
        store.insertMemoryItem("wm-old-1", HER, "working_memory", "08:01 [User fact] Alice: my biopsy is on Monday",
            null, t, "nexus");
        store.insertMemoryItem("wm-old-2", HER, "working_memory", "08:02 Moved to the biopsy lab",
            null, t, "nexus");
        store.commitAll();
        assertThat(ids(MemoryReader.of(ALICE, true))).doesNotContain("wm-old-1", "wm-old-2");

        var labels = store.labelUnmarkedMemory(HER, MemoryOrigin::inferLegacy, 1);
        assertThat(labels).extracting(WyrdLuceneStore.MemoryOriginLabel::id)
            .containsExactlyInAnyOrder("wm-old-1", "wm-old-2");
        assertThat(store.labelUnmarkedMemory(HER, MemoryOrigin::inferLegacy, 10)).as("idempotent").isEmpty();

        assertThat(ids(MemoryReader.of(ALICE, false))).contains("wm-old-1").doesNotContain("wm-old-2");
        assertThat(ids(MemoryReader.of(BOB, true))).contains("wm-old-2").doesNotContain("wm-old-1");
        assertThat(ids(MemoryReader.of(BOB, false))).doesNotContain("wm-old-1", "wm-old-2");
        assertThat(store.getById(SearchCollections.MEMORY_ITEMS, "wm-old-1").content())
            .as("the memory itself is not rewritten").isEqualTo("08:01 [User fact] Alice: my biopsy is on Monday");
    }
}
