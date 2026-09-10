package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ingest-time embedding is on only when the embedder is served (or forced), and never stops an ingest. */
class IngestEmbeddingTest {

    @AfterEach void tearDown() {
        System.clearProperty("wyrdsekai.embed.at_ingest");
        System.clearProperty(EmbeddingService.SERVER_URL_PROP);
        EmbeddingService.resetForTests();
    }

    @Test
    void off_in_process_unless_forced_and_never_on_without_an_embedder() {
        assertFalse(IngestEmbedding.enabled(), "no embedder initialized: off");
        EmbeddingService.init();   // in-process
        assertFalse(IngestEmbedding.enabled(), "in-process CPU embedding is not an ingest speed");
        assertNull(IngestEmbedding.batch(List.of("a", "b")));
        System.setProperty("wyrdsekai.embed.at_ingest", "true");
        assertTrue(IngestEmbedding.enabled(), "forced on");
        var b = IngestEmbedding.batch(List.of("a", "b"));
        assertNotNull(b);
        assertEquals(2, b.size());
        System.setProperty("wyrdsekai.embed.at_ingest", "false");
        assertFalse(IngestEmbedding.enabled(), "forced off");
    }

    @Test
    void the_pending_accumulator_drains_items_with_their_vectors() {
        var p = new IngestEmbedding.Pending<String>();
        p.add("x", "text x"); p.add("y", "text y");
        assertFalse(p.full());
        var drained = p.drain();
        assertEquals(2, drained.size());
        assertEquals("x", drained.get(0).getKey());
        assertTrue(drained.get(0).getValue().isEmpty(), "embedding off: text-only rows, not a failure");
        assertTrue(p.isEmpty());
        assertEquals("T\nbody", IngestEmbedding.textOf("T", "body"));
        assertEquals("body", IngestEmbedding.textOf("", "body"));
    }
}
