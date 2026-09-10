package org.wyrdsekai.core.search;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The served embedding mode: an OpenAI-style {@code /v1/embeddings} server stands in for the
 * in-process session. Batches go in one request, vectors come back normalized, blanks are
 * zero, a failed call is an error at index time and a zero vector at query time, and the
 * model version string names the server so its vectors never mix with in-process ones.
 */
class ServedEmbeddingTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean fail = false;

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
            if (fail) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            int n = body.split("\"input\":\\[")[1].split("]")[0].split("\",\"").length;
            var sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? "," : "").append("{\"embedding\":[");
                for (int d = 0; d < dim; d++) sb.append(d > 0 ? "," : "").append(d == i % dim ? "3.0" : "0.0");
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

    @Test
    void batches_go_in_one_call_and_come_back_normalized() throws Exception {
        int dim = EmbeddingService.resolveActiveModel().dimension();
        System.setProperty(EmbeddingService.SERVER_URL_PROP, start(dim));
        var svc = EmbeddingService.init();
        assertNotNull(svc, "served mode must initialize without any ONNX file");
        assertTrue(svc.served());
        assertTrue(EmbeddingService.currentModelVersion().endsWith("+server"));
        int before = calls.get();
        var out = svc.embedBatch(List.of("one", "two", "", "three"));
        assertEquals(before + 1, calls.get(), "one request for the whole batch");
        assertEquals(4, out.size());
        assertEquals(1.0f, out.get(0).get(0), 1e-6f, "3.0 normalizes to 1.0");
        assertEquals(dim, out.get(0).size());
        assertTrue(out.get(2).stream().allMatch(f -> f == 0f), "a blank text is a zero vector");
    }

    @Test
    void replicas_are_a_comma_list_and_each_call_finds_a_replica() throws Exception {
        assertEquals(List.of("http://a:1", "http://b:2"), EmbeddingService.serverUrlList(" http://a:1/ , http://b:2 ,"));
        int dim = EmbeddingService.resolveActiveModel().dimension();
        var url = start(dim);
        System.setProperty(EmbeddingService.SERVER_URL_PROP, url + "," + url);
        var svc = EmbeddingService.init();
        assertNotNull(svc);
        int before = calls.get();
        svc.embed("x"); svc.embed("y");
        assertEquals(before + 2, calls.get(), "two calls, each to a replica (same stub here)");
    }

    /**
     * Replicas of different speed: the fast one must take more of the work, not an equal
     * share. A strict round-robin lets the slowest card set the pace for all of them.
     */
    @Test
    void a_slow_replica_gets_less_of_the_work() throws Exception {
        int dim = EmbeddingService.resolveActiveModel().dimension();
        var fastCalls = new AtomicInteger();
        var slowCalls = new AtomicInteger();
        var fast = stub(dim, fastCalls, 0);
        var slow = stub(dim, slowCalls, 150);
        try {
            System.setProperty(EmbeddingService.SERVER_URL_PROP, fastUrl(fast) + "," + fastUrl(slow));
            var svc = EmbeddingService.init();   // its dimension probe is one call; count from here
            assertNotNull(svc);
            fastCalls.set(0); slowCalls.set(0);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 48; i++) futures.add(pool.submit(() -> svc.embedBatch(List.of("a", "b"))));
            for (var f : futures) f.get();
            pool.shutdown();
            assertEquals(48, fastCalls.get() + slowCalls.get());
            assertTrue(fastCalls.get() >= 3 * slowCalls.get(),
                "fast " + fastCalls.get() + " vs slow " + slowCalls.get());
            assertEquals(0, svc.inFlightByReplica().values().stream().mapToInt(Integer::intValue).sum(),
                "every call returned its in-flight slot");
        } finally {
            fast.stop(0); slow.stop(0);
        }
    }

    private static String fastUrl(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort(); }

    private static HttpServer stub(int dim, AtomicInteger counter, long delayMs) throws Exception {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        s.createContext("/v1/embeddings", ex -> {
            counter.incrementAndGet();
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = body.split("\"input\":\\[")[1].split("]")[0].split("\",\"").length;
            if (delayMs > 0) { try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { } }
            var sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? "," : "").append("{\"embedding\":[");
                for (int d = 0; d < dim; d++) sb.append(d > 0 ? "," : "").append(d == i % dim ? "3.0" : "0.0");
                sb.append("]}");
            }
            sb.append("]}");
            var bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    void a_failed_server_is_an_error_at_index_time_and_a_zero_vector_at_query_time() throws Exception {
        int dim = EmbeddingService.resolveActiveModel().dimension();
        System.setProperty(EmbeddingService.SERVER_URL_PROP, start(dim));
        var svc = EmbeddingService.init();
        assertNotNull(svc);
        fail = true;
        assertThrows(RuntimeException.class, () -> svc.embedBatch(List.of("a", "b")),
            "index-time callers must never receive a silent zero vector");
        var q = svc.embed("a query");
        assertEquals(dim, q.size());
        assertTrue(q.stream().allMatch(f -> f == 0f), "query-time degrades to BM25");
    }
}
