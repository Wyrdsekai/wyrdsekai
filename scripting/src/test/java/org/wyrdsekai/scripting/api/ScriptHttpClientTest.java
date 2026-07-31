package org.wyrdsekai.scripting.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ScriptHttpClient using a local ephemeral HTTP server.
 */
class ScriptHttpClientTest {

    private static HttpServer server;
    private static int port;
    private static ScriptHttpClient client;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        // Simple GET endpoint
        server.createContext("/hello", exchange -> {
            String response = "Hello, World!";
            exchange.sendResponseHeaders(200, response.length());
            try (var os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Echo POST body
        server.createContext("/echo", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, body.length());
            try (var os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });

        // Echo method
        server.createContext("/method", exchange -> {
            String method = exchange.getRequestMethod();
            exchange.sendResponseHeaders(200, method.length());
            try (var os = exchange.getResponseBody()) {
                os.write(method.getBytes());
            }
        });

        // Echo headers
        server.createContext("/headers", exchange -> {
            String xCustom = exchange.getRequestHeaders().getFirst("X-Custom");
            String response = xCustom != null ? xCustom : "no-header";
            exchange.sendResponseHeaders(200, response.length());
            try (var os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // 301 → /hello (for redirect-following test)
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/hello");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });

        server.setExecutor(null);
        server.start();

        // Permissive policy: the test server is on loopback, which the strict
        // (untrusted) policy blocks. Trusted bundled items use this policy.
        client = new ScriptHttpClient(false);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void get_returns_body() {
        String body = client.get("http://localhost:" + port + "/hello");
        assertThat(body).isEqualTo("Hello, World!");
    }

    @Test
    void post_sends_and_returns_body() {
        String body = client.post("http://localhost:" + port + "/echo", "{\"x\":1}");
        assertThat(body).isEqualTo("{\"x\":1}");
    }

    @Test
    void invalid_url_throws() {
        assertThatThrownBy(() -> client.get("not-a-url"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http://");
    }

    @Test
    void null_url_throws() {
        assertThatThrownBy(() -> client.get(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void fetch_with_custom_headers() {
        String result = client.fetch("http://localhost:" + port + "/headers",
            Map.of("headers", Map.of("X-Custom", "test-value")));
        assertThat(result).isEqualTo("test-value");
    }

    @Test
    void fetch_with_put_method() {
        String result = client.fetch("http://localhost:" + port + "/method",
            Map.of("method", "PUT", "body", "data"));
        assertThat(result).isEqualTo("PUT");
    }

    @Test
    void fetch_with_null_options_delegates_to_get() {
        String body = client.fetch("http://localhost:" + port + "/hello", null);
        assertThat(body).isEqualTo("Hello, World!");
    }

    // ─── #3 (2026-07-19 OSS hardening) — SSRF guard ─────────────────────────

    @Test
    void strict_policy_blocks_loopback() {
        var strict = new ScriptHttpClient(true);   // untrusted-script policy
        assertThatThrownBy(() -> strict.get("http://localhost:" + port + "/hello"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("non-public");
    }

    @Test
    void strict_policy_blocks_rfc1918_literal() {
        var strict = new ScriptHttpClient(true);
        assertThatThrownBy(() -> strict.get("http://192.0.2.5/x"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("non-public");
    }

    @Test
    void strict_policy_blocks_cloud_metadata() {
        var strict = new ScriptHttpClient(true);
        assertThatThrownBy(() -> strict.get("http://169.254.169.254/latest/meta-data/"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("non-public");
    }

    @Test
    void permissive_policy_still_blocks_cloud_metadata() {
        // Trusted items may reach LAN/loopback, but never link-local/metadata.
        var permissive = new ScriptHttpClient(false);
        assertThatThrownBy(() -> permissive.get("http://169.254.169.254/latest/meta-data/"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("non-public");
    }

    @Test
    void follows_redirect_and_revalidates() {
        // Permissive client on loopback: a 301 to /hello is followed.
        String body = client.get("http://localhost:" + port + "/redirect");
        assertThat(body).isEqualTo("Hello, World!");
    }
}
