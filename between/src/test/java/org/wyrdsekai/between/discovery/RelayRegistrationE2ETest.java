package org.wyrdsekai.between.discovery;

import org.junit.jupiter.api.Tag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * E2E test for relay registration flow.
 * Starts a mock registration server in-process and tests:
 * 1. /status endpoint returns capacity info
 * 2. /register generates a token and returns credentials
 * 3. Rate limiting blocks rapid registrations
 * 4. Capacity limits block when full
 * 5. Full discovery cascade with registration
 */
@Tag("needs-nats")
@Tag("needs-network")
class RelayRegistrationE2ETest {

    private static HttpServer registrationServer;
    private static int port;
    private static final AtomicInteger registeredCount = new AtomicInteger(0);
    private static final int CAPACITY = 3;
    private static final ConcurrentHashMap<String, Long> rateLimits = new ConcurrentHashMap<>();
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void setUp() throws IOException {
        // Start a mock registration server
        registrationServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = registrationServer.getAddress().getPort();

        registrationServer.createContext("/status", exchange -> {
            var json = """
                {"capacity":%d,"registered":%d,"available":%d,"region":"test","public":true,"utilization_percent":%.1f}
                """.formatted(CAPACITY, registeredCount.get(),
                    CAPACITY - registeredCount.get(),
                    registeredCount.get() * 100.0 / CAPACITY);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length());
            exchange.getResponseBody().write(json.getBytes());
            exchange.close();
        });

        registrationServer.createContext("/health", exchange -> {
            var json = "{\"status\":\"ok\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length());
            exchange.getResponseBody().write(json.getBytes());
            exchange.close();
        });

        registrationServer.createContext("/register", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }

            var ip = exchange.getRemoteAddress().getAddress().getHostAddress();
            var now = System.currentTimeMillis();
            var last = rateLimits.getOrDefault(ip, 0L);

            // Rate limit: 1 per 2 seconds for testing (production: 1 per hour)
            if (now - last < 2000) {
                var json = "{\"error\":\"Rate limited. Try again later.\"}";
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(429, json.length());
                exchange.getResponseBody().write(json.getBytes());
                exchange.close();
                return;
            }

            if (registeredCount.get() >= CAPACITY) {
                var json = "{\"error\":\"Relay at capacity\",\"capacity\":%d,\"registered\":%d}"
                    .formatted(CAPACITY, registeredCount.get());
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, json.length());
                exchange.getResponseBody().write(json.getBytes());
                exchange.close();
                return;
            }

            rateLimits.put(ip, now);
            var count = registeredCount.incrementAndGet();
            var hhId = "hh-test-" + count;
            var token = "test-token-" + count + "-" + System.nanoTime();

            var json = """
                {"household_id":"%s","token":"%s","relay_url":"nats://localhost:4222","nats_user":"%s","nats_password":"%s"}
                """.formatted(hhId, token, hhId, token);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length());
            exchange.getResponseBody().write(json.getBytes());
            exchange.close();
        });

        registrationServer.createContext("/relays", exchange -> {
            var json = "{\"relays\":[]}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length());
            exchange.getResponseBody().write(json.getBytes());
            exchange.close();
        });

        registrationServer.start();
    }

    @AfterAll
    static void tearDown() {
        if (registrationServer != null) registrationServer.stop(0);
    }

    @BeforeEach
    void reset() {
        registeredCount.set(0);
        rateLimits.clear();
    }

    @Test
    void status_returns_capacity() throws Exception {
        var resp = get("/status");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"capacity\":" + CAPACITY);
        assertThat(resp.body()).contains("\"available\":" + CAPACITY);
    }

    @Test
    void register_returns_token() throws Exception {
        var resp = post("/register");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("household_id");
        assertThat(resp.body()).contains("token");
        assertThat(resp.body()).contains("nats_user");

        // Verify status updated
        var status = get("/status");
        assertThat(status.body()).contains("\"registered\":1");
    }

    @Test
    void rate_limit_blocks_rapid_registration() throws Exception {
        var first = post("/register");
        assertThat(first.statusCode()).isEqualTo(200);

        // Immediate second request — should be rate limited
        var second = post("/register");
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.body()).contains("Rate limited");
    }

    @Test
    void capacity_limit_blocks_when_full() throws Exception {
        // Fill up capacity
        for (int i = 0; i < CAPACITY; i++) {
            rateLimits.clear(); // bypass rate limit for this test
            var resp = post("/register");
            assertThat(resp.statusCode()).isEqualTo(200);
        }

        // Next should be rejected
        rateLimits.clear();
        var full = post("/register");
        assertThat(full.statusCode()).isEqualTo(503);
        assertThat(full.body()).contains("at capacity");
    }

    @Test
    void health_endpoint_responds() throws Exception {
        var resp = get("/health");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("ok");
    }

    @Test
    void relays_endpoint_responds() throws Exception {
        var resp = get("/relays");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("relays");
    }

    @Test
    void full_discovery_with_registration(@TempDir Path tempDir) throws Exception {
        // Set up discovery service pointing at our mock relay
        var dht = KademliaTable.create("test-discovery");
        var trust = new RelayTrustGraph("home");
        var consensus = RelayConsensus.singleAuthority("dummy");

        // Store the mock relay in DHT
        dht.storeRelay("nats://localhost:" + port, true, CAPACITY, 0,
            dht.localNodeId(), null);

        var service = new RelayDiscoveryService(tempDir, dht, trust, consensus);
        var result = service.discover();

        assertThat(result.relays()).isNotEmpty();
        assertThat(result.method()).isEqualTo("dht");

        // Now register with the discovered relay's registration endpoint
        var relayUrl = "http://localhost:" + port;
        var regResp = post("/register");
        assertThat(regResp.statusCode()).isEqualTo(200);
        assertThat(regResp.body()).contains("token");
    }

    // --- HTTP helpers ---

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
