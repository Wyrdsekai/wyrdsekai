package org.wyrdsekai.core.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FederatedHomeProxy} routes local vs remote DIDs correctly and POSTs
 * well-formed grant-requests to remote zones' REST endpoints.
 */
class FederatedHomeProxyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("FederatedHomeProxyTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("fed-proxy.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void local_did_routes_to_local_proxy() {
        var dir = new ZoneDirectory.StaticZoneDirectory("alpha");
        var local = new HomeProxy.Local(homeClient, "alpha");
        var fed = new FederatedHomeProxy(local, "alpha", dir);

        var result = fed.knock("bob", "alice", "hello");
        assertThat(result.ok()).isTrue();
        assertThat(result.remote()).isFalse();
        assertThat(result.homeZone()).isEqualTo("alpha");
        assertThat(homeClient.pendingForOwner("alice")).hasSize(1);
    }

    @Test void unknown_zone_returns_error() {
        var dir = new ZoneDirectory.StaticZoneDirectory("alpha")
            .mapDid("bob-at-beta", "beta"); // no httpBase for beta
        var local = new HomeProxy.Local(homeClient, "alpha");
        var fed = new FederatedHomeProxy(local, "alpha", dir);

        var result = fed.knock("alice", "bob-at-beta", "visiting");
        assertThat(result.ok()).isFalse();
        assertThat(result.note()).contains("no http base");
    }

    @Test void remote_did_posts_to_remote_endpoint() throws Exception {
        // Start a tiny HTTP server that pretends to be beta's /api/home/grant-requests.
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/home/grant-requests", ex -> {
            var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(body);
            var resp = MAPPER.writeValueAsBytes(Map.of(
                "id", "req-remote-42",
                "status", "pending"));
            ex.getResponseHeaders().set("content-type", "application/json");
            ex.sendResponseHeaders(201, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        try {
            var port = server.getAddress().getPort();
            var dir = new ZoneDirectory.StaticZoneDirectory("alpha")
                .mapDid("bob-at-beta", "beta")
                .mapZoneHttp("beta", "http://127.0.0.1:" + port);
            var local = new HomeProxy.Local(homeClient, "alpha");
            var fed = new FederatedHomeProxy(local, "alpha", dir);

            var result = fed.knock("alice", "bob-at-beta", "college visit");
            assertThat(result.ok()).isTrue();
            assertThat(result.remote()).isTrue();
            assertThat(result.homeZone()).isEqualTo("beta");
            assertThat(result.requestId()).isEqualTo("req-remote-42");

            // Verify the body we posted contains the right shape.
            var posted = MAPPER.readTree(captured.get());
            assertThat(posted.path("requester").asText()).isEqualTo("alice");
            assertThat(posted.path("owner").asText()).isEqualTo("bob-at-beta");
            assertThat(posted.path("resource").asText()).isEqualTo("home://bob-at-beta/home-room");
            assertThat(posted.path("capability").asText()).isEqualTo("use");
            assertThat(posted.path("reason").asText()).isEqualTo("college visit");
        } finally {
            server.stop(0);
        }
    }

    @Test void remote_http_error_returns_failure() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/home/grant-requests", ex -> {
            var body = "{\"error\":\"storage down\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(503, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var port = server.getAddress().getPort();
            var dir = new ZoneDirectory.StaticZoneDirectory("alpha")
                .mapDid("bob-at-beta", "beta")
                .mapZoneHttp("beta", "http://127.0.0.1:" + port);
            var fed = new FederatedHomeProxy(
                new HomeProxy.Local(homeClient, "alpha"),
                "alpha", dir,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                Duration.ofSeconds(3));

            var result = fed.knock("alice", "bob-at-beta", "try");
            assertThat(result.ok()).isFalse();
            assertThat(result.note()).contains("503");
        } finally {
            server.stop(0);
        }
    }

    @Test void did_zone_convention_is_respected() {
        var dir = new ZoneDirectory.StaticZoneDirectory("alpha");
        assertThat(dir.zoneOf("did:zone:beta")).contains("beta");
        assertThat(dir.zoneOf("did:key:z6MkAlice")).contains("alpha"); // default
    }

    @Test void singleZone_factory_works() {
        var dir = ZoneDirectory.singleZone("alpha", "http://host:7070");
        assertThat(dir.zoneOf("anyone")).contains("alpha");
        assertThat(dir.httpBaseOf("alpha")).contains("http://host:7070");
        assertThat(dir.httpBaseOf("beta")).isEmpty();
    }

    // Silence IDE warnings about unused list.
    @SuppressWarnings("unused")
    private List<String> unused = List.of();
}
