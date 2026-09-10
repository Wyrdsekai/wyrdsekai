package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** A share's vectors are copied from the projection onto the Study documents by id; the Study fields survive. */
class CopyVectorsToStudyTest {

    @Test
    void the_projection_vector_lands_on_the_study_document(@TempDir Path dir) throws Exception {
        var store = new WyrdLuceneStore(dir, 384);
        try {
            store.ensureAllCollections();
            var vec = new ArrayList<Float>();
            for (int i = 0; i < 384; i++) vec.add(i == 3 ? 1f : 0f);
            var studyId = "doc:did:key:zOwner:books:abcd1234";
            store.insertStudyItem(studyId, "did:key:zOwner", "document", "Glass Tide (part 9/387)",
                "The Librarian explained the vel-shara.", "books", 1700000000000L, 1, null);
            store.insertStudyItem("doc:did:key:zOwner:books:other", "did:key:zOwner", "document", "Other",
                "No projection for this one.", "books", 1700000000000L, 1, null);
            store.insertKnowledge("study-share-books:" + studyId, "study-share-books", "Glass Tide (part 9/387)",
                "The Librarian explained the vel-shara.", "study-share", "private", vec);
            store.commitAll();
            assertEquals(0, store.countWithVectors(SearchCollections.STUDY));

            long n = store.copyVectorsToStudy("study-share-books", 1000, null);
            assertEquals(1, n);
            assertEquals(1, store.countWithVectors(SearchCollections.STUDY));
            var doc = store.getById(SearchCollections.STUDY, studyId);
            assertNotNull(doc);
            assertEquals("did:key:zOwner", doc.metadata().get("user_did"));
            assertEquals("books", doc.metadata().get("collection"));
            assertEquals("document", doc.metadata().get("item_type"));
            assertEquals("The Librarian explained the vel-shara.", doc.content());
            assertEquals(2, store.listStudyByTypeRecent("did:key:zOwner", "document", 10).size(), "nothing lost, nothing duplicated");
        } finally {
            store.close();
        }
    }
}
