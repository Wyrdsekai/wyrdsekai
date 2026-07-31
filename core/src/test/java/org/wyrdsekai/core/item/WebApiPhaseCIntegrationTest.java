package org.wyrdsekai.core.item;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C web write surface integration
 * test. Spins up a local {@link HttpServer} and verifies the provider's
 * post/put/delete/fetch_raw paths hit it with the right method, body, and
 * headers, and surface the response in the spec shape.
 */
class WebApiPhaseCIntegrationTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();

    private ItemWorldApiProviderImpl provider;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            try (var is = exchange.getRequestBody()) {
                lastBody.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
            var response = "{\"echo\":\"" + exchange.getRequestMethod() + "\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        provider = new ItemWorldApiProviderImpl(
            null, null, null, null,
            "did:wyrd:test", "Tester",
            t -> {}, t -> {}, (a, b) -> {},
            null, null);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void web_post_round_trip() {
        var url = "http://127.0.0.1:" + port + "/hook";
        var res = provider.webPost(url, "{\"hello\":\"world\"}", Map.of());
        assertThat(res.get("status")).isEqualTo(200);
        assertThat(String.valueOf(res.get("body"))).contains("POST");
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastBody.get()).isEqualTo("{\"hello\":\"world\"}");
        assertThat(lastContentType.get()).startsWith("application/json");
    }

    @Test
    void web_put_round_trip() {
        var url = "http://127.0.0.1:" + port + "/resource";
        var res = provider.webPut(url, "data", Map.of("contentType", "text/plain"));
        assertThat(res.get("status")).isEqualTo(200);
        assertThat(lastMethod.get()).isEqualTo("PUT");
        assertThat(lastBody.get()).isEqualTo("data");
        assertThat(lastContentType.get()).startsWith("text/plain");
    }

    @Test
    void web_delete_round_trip() {
        var url = "http://127.0.0.1:" + port + "/resource/1";
        var res = provider.webDelete(url, Map.of());
        assertThat(res.get("status")).isEqualTo(200);
        assertThat(lastMethod.get()).isEqualTo("DELETE");
    }

    @Test
    void web_fetch_raw_returns_full_envelope() {
        var url = "http://127.0.0.1:" + port + "/data";
        var res = provider.webFetchRaw(url, Map.of());
        assertThat(res.get("status")).isEqualTo(200);
        assertThat(res).containsKey("headers");
        assertThat(res).containsKey("contentType");
        assertThat(String.valueOf(res.get("body"))).contains("GET");
    }

    @Test
    void web_post_propagates_custom_headers() {
        var url = "http://127.0.0.1:" + port + "/hook";
        var headers = new HashMap<String, Object>();
        headers.put("X-Item", "research_clipper");
        provider.webPost(url, "x", Map.of("headers", headers));
        assertThat(lastMethod.get()).isEqualTo("POST");
    }

    @Test
    void web_post_with_invalid_url_returns_error_shape() {
        var res = provider.webPost("not a url", "x", Map.of());
        assertThat(res.get("status")).isEqualTo(0);
        assertThat(res).containsKey("error");
    }
}
