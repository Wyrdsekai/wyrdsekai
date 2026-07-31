package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * world.library.ingest enactment + world.host.find — the capabilities that
 * let a companion be ASKED "find my ebooks folder and ingest it" and
 * actually do it, confined to the steward's open-roots.
 */
class AgentIngestServiceTest {

    @TempDir
    Path tmp;

    @AfterEach
    void resetSeams() {
        AgentIngestService.setRootsSupplier(null);
        AgentIngestService.init(null);
    }

    @Test
    void refuses_when_unwired_or_unrooted_or_outside_roots() throws Exception {
        // Not wired
        assertEquals("library.ingest not wired",
            AgentIngestService.ingest("did:a", "/tmp", null, null).get("error"));

        var store = new WyrdLuceneStore(tmp.resolve("search"), 384);
        AgentIngestService.init(new StudyService(store, null));

        // No granted roots
        AgentIngestService.setRootsSupplier(List::of);
        assertEquals("no_roots",
            AgentIngestService.ingest("did:a", tmp.toString(), null, null).get("error"));

        // Outside the granted root
        var granted = Files.createDirectories(tmp.resolve("granted"));
        var outside = Files.createDirectories(tmp.resolve("outside"));
        AgentIngestService.setRootsSupplier(() -> List.of(granted.toString()));
        assertEquals("outside_roots",
            AgentIngestService.ingest("did:a", outside.toString(), null, null).get("error"));
    }

    @Test
    void ingests_a_granted_directory_async_and_makes_it_searchable() throws Exception {
        var store = new WyrdLuceneStore(tmp.resolve("search"), 384);
        var study = new StudyService(store, null);
        AgentIngestService.init(study);

        var books = Files.createDirectories(tmp.resolve("books"));
        Files.writeString(books.resolve("hobbit.txt"),
            "In a hole in the ground there lived a hobbit.");
        AgentIngestService.setRootsSupplier(() -> List.of(tmp.toString()));

        var result = AgentIngestService.ingest("did:mia", books.toString(), null, null);
        assertEquals(true, result.get("ok"), () -> "ingest refused: " + result);
        assertEquals("full", result.get("mode"));
        assertEquals("books", result.get("collection"));

        // Ingest is async — poll until the commit lands (10s budget).
        var deadline = System.currentTimeMillis() + 10_000;
        while (study.searchDocuments("did:mia", "books", "hobbit", 5).isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(1, study.searchDocuments("did:mia", "books", "hobbit", 5).size());
    }

}
