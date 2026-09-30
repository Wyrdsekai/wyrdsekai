package org.wyrdsekai.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The release bake regenerates the bootstrap corpus in the tree the installers are packaged from. When
 * the proven head is kept, the corpus it was trained from must be what ships — 0.5.0's first candidates
 * shipped the losing candidate's corpus (2026-09-27).
 */
class BakeCorpusRestoreTest {

    @Test
    void theCorpusPathIsTheOneTheRecipeRewrites(@TempDir Path project) {
        assertEquals(project.resolve("core/src/main/resources/classifier/bootstrap/task_present/expanded.jsonl"),
            RecipeBakeMain.corpusPath(project, "task_present"));
    }

    @Test
    void aRegeneratedCorpusGoesBackWhenTheProvenHeadShips(@TempDir Path project) throws Exception {
        Path corpus = RecipeBakeMain.corpusPath(project, "cleanliness");
        Files.createDirectories(corpus.getParent());
        byte[] tracked = "{\"label\": \"clean\", \"text\": \"Hi! Good to see you.\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(corpus, tracked);
        byte[] before = Files.readAllBytes(corpus);

        // The recipe's clean-corpus + expand-corpus steps: delete, then write the candidate's corpus.
        Files.delete(corpus);
        Files.writeString(corpus, "{\"label\": \"clean\", \"text\": \"{\\\"action\\\": \\\"finish_turn\\\"}\"}\n");

        assertTrue(RecipeBakeMain.restoreCorpus(corpus, before), "the file was changed back");
        assertArrayEquals(tracked, Files.readAllBytes(corpus));
        assertFalse(RecipeBakeMain.restoreCorpus(corpus, before), "already the tracked corpus");
    }

    @Test
    void aCorpusThatWasAbsentBeforeTheRunIsRemovedAgain(@TempDir Path project) throws Exception {
        Path corpus = RecipeBakeMain.corpusPath(project, "request_type");
        Files.createDirectories(corpus.getParent());
        Files.writeString(corpus, "scratch\n");
        assertTrue(RecipeBakeMain.restoreCorpus(corpus, null));
        assertFalse(Files.exists(corpus));
        assertFalse(RecipeBakeMain.restoreCorpus(corpus, null));
    }
}
