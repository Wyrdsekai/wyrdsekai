package org.wyrdsekai.core.external.p;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.wyrdsekai.core.external.CredentialResolver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tiny fixture for adapter tests — spins up a {@link HttpServer} on a random
 * port + lets the test register per-path response handlers and inspect the
 * last received request via {@link #lastRequest()}.
 *
 * <p>Also wires {@link CredentialResolver} with a fake reader so adapters
 * see populated slots.</p>
 */
final class MockServerSupport implements AutoCloseable {

    private final HttpServer server;
    private final int port;
    private final AtomicReference<RecordedRequest> lastRequest = new AtomicReference<>();
    private final ConcurrentHashMap<String, FixedResponse> responses = new ConcurrentHashMap<>();

    record FixedResponse(int status, String contentType, String body) {}
    record RecordedRequest(String method, String path, String body, String authorization,
                            String rawUri) {}

    static MockServerSupport start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var support = new MockServerSupport(server);
            server.createContext("/", support.handler());
            server.start();
            return support;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private MockServerSupport(HttpServer server) {
        this.server = server;
        this.port = server.getAddress().getPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    int port() {
        return port;
    }

    /** Register a {status,contentType,body} response for a path prefix. */
    void onPath(String pathPrefix, int status, String contentType, String body) {
        responses.put(pathPrefix, new FixedResponse(status, contentType, body));
    }

    void onJson(String pathPrefix, int status, String body) {
        onPath(pathPrefix, status, "application/json", body);
    }

    RecordedRequest lastRequest() {
        return lastRequest.get();
    }

    private HttpHandler handler() {
        return (HttpExchange ex) -> {
            var path = ex.getRequestURI().getPath();
            var query = ex.getRequestURI().getQuery();
            var fullPath = query == null ? path : path + "?" + query;
            String body;
            try (var is = ex.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            var auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null) auth = ex.getRequestHeaders().getFirst("PRIVATE-TOKEN");
            var rawUri = ex.getRequestURI().toString();
            lastRequest.set(new RecordedRequest(ex.getRequestMethod(), fullPath, body, auth, rawUri));

            var resp = pickResponse(path);
            var bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", resp.contentType());
            ex.sendResponseHeaders(resp.status(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        };
    }

    private FixedResponse pickResponse(String path) {
        FixedResponse best = null;
        int bestLen = -1;
        for (var e : responses.entrySet()) {
            if (path.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return best != null ? best : new FixedResponse(404, "text/plain", "no handler");
    }

    /** Set a CredentialResolver fake that satisfies a single slot with a token. */
    static void wireCredential(String slot, String token) {
        CredentialResolver.get().setSafeReader(s ->
            s.equals(slot) ? Optional.of(token) : Optional.empty());
    }

    static void clearCredentials() {
        CredentialResolver.get().resetForTests();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
