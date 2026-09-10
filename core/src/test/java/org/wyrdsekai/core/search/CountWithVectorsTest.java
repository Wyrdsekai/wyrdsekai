package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The vector count comes from the vector index, so a text-only document is not counted. */
class CountWithVectorsTest {

    @Test
    void counts_only_documents_that_carry_a_vector(@TempDir Path dir) throws Exception {
        var store = new WyrdLuceneStore(dir, 384);
        try {
            store.ensureAllCollections();
            var vec = new ArrayList<Float>();
            for (int i = 0; i < 384; i++) vec.add(i == 0 ? 1f : 0f);
            store.insertKnowledge("p:1", "p", "with", "has a vector", "p", "s", vec);
            store.insertKnowledge("p:2", "p", "without", "text only", "p", "s", null);
            store.insertKnowledge("p:3", "p", "without too", "text only again", "p", "s", null);
            store.commitAll();
            assertEquals(1, store.countWithVectors(SearchCollections.KNOWLEDGE));
            assertEquals(0, store.countWithVectors(SearchCollections.STUDY));
        } finally {
            store.close();
        }
    }
}
