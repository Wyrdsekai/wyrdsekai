package org.wyrdsekai.core.search;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.item.ToolSearchIndex;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node that started before its embedding server tried once and ran without meaning-search
 * until it was restarted: eighteen readers ask get(), which never asked again, and the tool index
 * latched itself keyword-only (household node, 2026-11-11, after a reboot with the embedding
 * server stopped). The server is now tried again in the background, never on a caller's thread.
 */
class MeaningSearchComesBackWhenTheServerAnswersTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean down = true;

    @AfterEach
    void tearDown() {
        System.clearProperty(EmbeddingService.SERVER_URL_PROP);
        EmbeddingService.resetForTests();
        if (server != null) server.stop(0);
    }

    private String start(int dim) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            calls.incrementAndGet();
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (down) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            int n = body.split("\"input\":\\[")[1].split("]")[0].split("\",\"").length;
            var sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? "," : "").append("{\"embedding\":[");
                for (int d = 0; d < dim; d++) sb.append(d > 0 ? "," : "").append(d == i % dim ? "3.0" : "0.5");
                sb.append("]}");
            }
            sb.append("]}");
            var bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static EmbeddingService awaitService() throws InterruptedException {
        for (int i = 0; i < 200 && EmbeddingService.get() == null; i++) Thread.sleep(25);
        return EmbeddingService.get();
    }

    @Test
    void a_server_that_answers_later_is_found_in_the_background() throws Exception {
        EmbeddingService.strictRounds = 1;
        System.setProperty(EmbeddingService.SERVER_URL_PROP, start(EmbeddingService.resolveActiveModel().dimension()));

        assertThat(EmbeddingService.init()).as("the server is not answering yet").isNull();
        assertThat(EmbeddingService.waitingForServer()).isTrue();
        int probes = calls.get();
        assertThat(EmbeddingService.init()).as("a caller does not wait on a server that is down").isNull();
        assertThat(EmbeddingService.get()).isNull();
        Thread.sleep(100);
        assertThat(calls.get()).as("inside the minute nothing probes it again").isEqualTo(probes);

        down = false;
        EmbeddingService.serverRetry = Duration.ZERO;
        var svc = awaitService();
        assertThat(svc).as("a reader's ask started the try that found it").isNotNull();
        assertThat(svc.served()).isTrue();
        assertThat(EmbeddingService.waitingForServer()).isFalse();
    }

    @Test
    void the_tool_index_waits_for_the_server_instead_of_going_keyword_only_for_good() throws Exception {
        EmbeddingService.strictRounds = 1;
        System.setProperty(EmbeddingService.SERVER_URL_PROP, start(EmbeddingService.resolveActiveModel().dimension()));
        var index = new ToolSearchIndex();
        index.register(InferenceClient.ToolDefinition.function("house_backups", "See the house's own copies of itself", Map.of()));
        index.register(InferenceClient.ToolDefinition.function("read_book", "Read a part of a book on the shelves", Map.of()));

        index.embedAll();
        assertThat(index.hasVectorSearch()).as("no vectors while the server is down").isFalse();

        down = false;
        EmbeddingService.serverRetry = Duration.ZERO;
        assertThat(awaitService()).isNotNull();
        index.embedAll();
        assertThat(index.hasVectorSearch()).as("the same index has its vectors once the server answers").isTrue();
    }
}
