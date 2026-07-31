package org.wyrdsekai.core.external.w;

import com.sun.net.httpserver.HttpServer;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tiny shared harness for Phase W adapter tests. Spins up a JDK
 * {@link HttpServer} on an ephemeral port and writes the captured request
 * path/headers + a fixed JSON response. Tests reset
 * {@link CredentialResolver} per-test to avoid state bleed.
 */
final class AdapterTestHarness {

    private AdapterTestHarness() {}

    /** Build an {@link AdapterRequest} with a method + args map. */
    static AdapterRequest req(String namespace, String method, Object... kv) {
        var args = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new AdapterRequest(namespace, method, args, ItemCapabilitySet.UNRESTRICTED, null);
    }

    /** Capture a single request and respond with a fixed body. */
    static MockServer startMock(int statusCode, String contentType, String body) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var captured = new AtomicReference<CapturedRequest>();
            server.createContext("/", exchange -> {
                var path = exchange.getRequestURI().toString();
                var method = exchange.getRequestMethod();
                var hdrs = exchange.getRequestHeaders();
                var reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                captured.set(new CapturedRequest(method, path, Map.copyOf(hdrs), reqBody));
                var bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                if (contentType != null) {
                    exchange.getResponseHeaders().add("content-type", contentType);
                }
                exchange.sendResponseHeaders(statusCode, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return new MockServer(server, captured);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Wire a fake credential reader for the current test. */
    static void setCredential(String slot, String value) {
        CredentialResolver.get().setSafeReader(s ->
            slot.equals(s) ? Optional.ofNullable(value) : Optional.empty());
    }

    /** Wire credentials from a map; resets between tests. */
    static void setCredentials(Map<String, String> creds) {
        CredentialResolver.get().setSafeReader(s ->
            Optional.ofNullable(creds.get(s)));
    }

    static void clearCredentials() {
        CredentialResolver.get().resetForTests();
    }

    record CapturedRequest(String method, String path, Map<String, List<String>> headers, String body) {}

    static final class MockServer implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<CapturedRequest> captured;

        MockServer(HttpServer server, AtomicReference<CapturedRequest> captured) {
            this.server = server;
            this.captured = captured;
        }

        String baseUrl() {
            var addr = server.getAddress();
            return "http://" + addr.getHostString() + ":" + addr.getPort();
        }

        CapturedRequest captured() { return captured.get(); }

        @Override public void close() { server.stop(0); }
    }
}
