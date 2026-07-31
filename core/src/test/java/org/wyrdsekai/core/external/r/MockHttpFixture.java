package org.wyrdsekai.core.external.r;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Tiny test helper around {@link com.sun.net.httpserver.HttpServer}. Used by
 * Phase R adapter tests to mock upstream services without hitting the
 * network — keeps unit tests deterministic and fast.
 */
final class MockHttpFixture implements AutoCloseable {

    static final class Recorded {
        final String method;
        final String path;
        final String body;
        final Map<String, String> headers;
        Recorded(String method, String path, String body, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
        }
    }

    private final HttpServer server;
    private final List<Recorded> recorded = new ArrayList<>();
    private final ConcurrentHashMap<String, BiFunction<HttpExchange, String, Reply>> routes = new ConcurrentHashMap<>();

    MockHttpFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new RootHandler());
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Map a path-prefix to a handler. First match wins. */
    void onPath(String prefix, BiFunction<HttpExchange, String, Reply> handler) {
        routes.put(prefix, handler);
    }

    List<Recorded> recorded() { return recorded; }

    @Override public void close() {
        server.stop(0);
    }

    record Reply(int status, String contentType, String body) {
        static Reply json(String body) { return new Reply(200, "application/json", body); }
        static Reply json(int status, String body) { return new Reply(status, "application/json", body); }
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var headers = new HashMap<String, String>();
            for (var e : exchange.getRequestHeaders().entrySet()) {
                if (!e.getValue().isEmpty()) headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
            }
            recorded.add(new Recorded(method, path + (exchange.getRequestURI().getQuery() != null
                ? "?" + exchange.getRequestURI().getQuery() : ""), body, headers));
            BiFunction<HttpExchange, String, Reply> match = null;
            String matchKey = "";
            for (var entry : routes.entrySet()) {
                if (path.startsWith(entry.getKey()) && entry.getKey().length() > matchKey.length()) {
                    match = entry.getValue();
                    matchKey = entry.getKey();
                }
            }
            Reply reply;
            if (match == null) {
                reply = new Reply(404, "application/json", "{\"error\":\"no route for " + path + "\"}");
            } else {
                reply = match.apply(exchange, body);
            }
            var bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
