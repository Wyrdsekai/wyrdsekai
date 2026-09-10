package org.wyrdsekai.core.search;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A knowledge search with no query embedding used to collapse to BM25 silently, so the
 * dense leg never ran in production. Now the store embeds the query when a caller brings
 * none: a chunk that shares NO words with the query is found through its vector.
 */
class KnowledgeSearchUsesTheVectorsTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        System.clearProperty(EmbeddingService.SERVER_URL_PROP);
        EmbeddingService.resetForTests();
        if (server != null) server.stop(0);
    }

    /** Every text embeds to basis vector 0 — so any query is "about" any document, by vector. */
    private String stub(int dim) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = body.split("\"input\":\\[")[1].split("]")[0].split("\",\"").length;
            var sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? "," : "").append("{\"embedding\":[1.0");
                for (int d = 1; d < dim; d++) sb.append(",0.0");
                sb.append("]}");
            }
            sb.append("]}");
            var bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void a_query_with_no_shared_words_finds_the_chunk_through_its_vector(@TempDir Path dir) throws Exception {
        int dim = EmbeddingService.resolveActiveModel().dimension();
        System.setProperty(EmbeddingService.SERVER_URL_PROP, stub(dim));
        assertNotNull(EmbeddingService.init());
        var store = new WyrdLuceneStore(dir, dim);
        try {
            store.ensureAllCollections();
            var basis0 = new ArrayList<Float>();
            for (int d = 0; d < dim; d++) basis0.add(d == 0 ? 1f : 0f);
            store.insertKnowledge("p:1", "p", "Obsidian", "Volcanic glass formed when lava cools quickly.", "p", "geology", basis0);
            store.insertKnowledge("p:2", "p", "Bread", "Yeast makes dough rise by releasing carbon dioxide.", "p", "cooking", null);
            store.commitAll();

            var textOnly = store.searchKnowledgeText("zzz qqq", 5);
            assertTrue(textOnly.isEmpty(), "BM25 has nothing for nonsense words");

            var dense = store.searchKnowledge("zzz qqq", null, 5);
            assertEquals(List.of("p:1"), dense.stream().map(WyrdLuceneStore.SearchResult::id).toList(),
                "with no embedding passed, the store embeds the query and the vector leg answers");
        } finally {
            store.close();
        }
    }
}
