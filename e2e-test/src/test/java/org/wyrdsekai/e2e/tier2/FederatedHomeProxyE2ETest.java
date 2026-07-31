package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.core.home.FederatedHomeProxy;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeProxy;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.home.ZoneDirectory;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.server.http.HomeRoutes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 2 — {@link FederatedHomeProxy} talking to a real {@link HomeRoutes}
 * mounted on Javalin. Two embedded zones in one JVM. Exercises the REST
 * contract end-to-end: POST /api/home/grant-requests body shape, status
 * codes, and the returned id flowing back to the proxy.
 *
 * <p>Catches drift between the proxy's outbound JSON and what the server
 * REST endpoint expects on the wire.</p>
 */
@Tag("tier2")
class FederatedHomeProxyE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ActorTestKit kitAlpha;
    private static ActorTestKit kitBeta;
    private static Path tmpAlpha;
    private static Path tmpBeta;
    private static HomeClient alphaClient;
    private static HomeClient betaClient;
    private static Javalin alphaApp;
    private static Javalin betaApp;
    private static String alphaBaseUrl;
    private static String betaBaseUrl;

    @BeforeAll
    static void setUp() throws Exception {
        kitAlpha = ActorTestKit.create("fed-alpha",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        kitBeta = ActorTestKit.create("fed-beta",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));

        tmpAlpha = Files.createTempDirectory("fed-proxy-alpha");
        tmpBeta = Files.createTempDirectory("fed-proxy-beta");

        alphaClient = bootRegistry(kitAlpha, tmpAlpha, "home-alpha");
        betaClient = bootRegistry(kitBeta, tmpBeta, "home-beta");

        // Mount a minimal Javalin per zone with only HomeRoutes.
        int alphaPort = PortAllocator.allocate();
        int betaPort = PortAllocator.allocate();
        alphaApp = mountHome(alphaClient, kitAlpha.system(), alphaPort);
        betaApp = mountHome(betaClient, kitBeta.system(), betaPort);
        alphaBaseUrl = "http://127.0.0.1:" + alphaPort;
        betaBaseUrl = "http://127.0.0.1:" + betaPort;
    }

    @AfterAll
    static void tearDown() {
        if (alphaApp != null) alphaApp.stop();
        if (betaApp != null) betaApp.stop();
        if (kitAlpha != null) kitAlpha.shutdownTestKit();
        if (kitBeta != null) kitBeta.shutdownTestKit();
    }

    private static HomeClient bootRegistry(ActorTestKit kit, Path dir, String name)
            throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve(name + ".db"));
        var store = new HomeStore(jdbc);
        ActorRef<HomeRegistryActor.Command> ref = kit.spawn(
            HomeRegistryActor.create(store), name);
        return new HomeClient(ref, kit.system());
    }

    private static Javalin mountHome(HomeClient client, ActorSystem<?> system, int port) {
        var app = Javalin.create(cfg ->
            new HomeRoutes(client.registry(), system).register(cfg.routes));
        app.start(port);
        return app;
    }

    // --- Tests -----------------------------------------------------------

    @Test
    void remote_did_routed_via_proxy_creates_request_on_target_zone() throws Exception {
        // Alpha is local; beta hosts bob. Alice (on alpha) knocks remote.
        var dir = new ZoneDirectory.StaticZoneDirectory("alpha")
            .mapDid("bob-at-beta", "beta")
            .mapZoneHttp("beta", betaBaseUrl);

        var proxy = new FederatedHomeProxy(
            new HomeProxy.Local(alphaClient, "alpha"), "alpha", dir);

        var result = proxy.knock("alice-at-alpha", "bob-at-beta", "visiting");
        assertThat(result.ok()).isTrue();
        assertThat(result.remote()).isTrue();
        assertThat(result.homeZone()).isEqualTo("beta");

        // Verify on beta's registry — pending list contains it.
        var pending = betaClient.pendingForOwner("bob-at-beta");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).id()).isEqualTo(result.requestId());
        assertThat(pending.get(0).requester()).isEqualTo("alice-at-alpha");
        assertThat(pending.get(0).resource().toString())
            .isEqualTo("home://bob-at-beta/home-room");
    }

    @Test
    void local_did_bypasses_network_and_creates_on_alpha() throws Exception {
        var dir = new ZoneDirectory.StaticZoneDirectory("alpha")
            .mapZoneHttp("beta", betaBaseUrl);

        var proxy = new FederatedHomeProxy(
            new HomeProxy.Local(alphaClient, "alpha"), "alpha", dir);

        var result = proxy.knock("bob-at-alpha", "alice-at-alpha", "hi");
        assertThat(result.ok()).isTrue();
        assertThat(result.remote()).isFalse();
        assertThat(result.homeZone()).isEqualTo("alpha");

        assertThat(alphaClient.pendingForOwner("alice-at-alpha")).hasSize(1);
        // Beta should see nothing.
        assertThat(betaClient.pendingForOwner("alice-at-alpha")).isEmpty();
    }

    @Test
    void remote_rest_response_shape_matches_expected_json() throws Exception {
        // Direct POST to beta's endpoint to lock in the exact response
        // shape the FederatedHomeProxy parses.
        var body = """
            {"requester":"did:key:z6MkDirect","owner":"did:key:z6MkBob",
             "resource":"home://did:key:z6MkBob/home-room","capability":"use",
             "reason":"contract check"}
            """;
        var req = HttpRequest.newBuilder()
            .uri(URI.create(betaBaseUrl + "/api/home/grant-requests"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
            .send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.path("id").asText()).isNotBlank();
        assertThat(json.path("status").asText()).isEqualTo("pending");
        assertThat(json.path("requester").asText()).isEqualTo("did:key:z6MkDirect");
        assertThat(json.path("resource").asText())
            .isEqualTo("home://did:key:z6MkBob/home-room");
    }

    @Test
    void round_trip_approve_via_remote_rest() throws Exception {
        // Alpha knocks beta via proxy, then beta's owner approves over REST.
        var dir = new ZoneDirectory.StaticZoneDirectory("alpha")
            .mapDid("bob-at-beta2", "beta")
            .mapZoneHttp("beta", betaBaseUrl);
        var proxy = new FederatedHomeProxy(
            new HomeProxy.Local(alphaClient, "alpha"), "alpha", dir);
        var knockResult = proxy.knock("alice-at-alpha", "bob-at-beta2", "please");
        assertThat(knockResult.ok()).isTrue();

        // Bob (owner on beta) approves via the REST endpoint. This is what
        // a real UI on beta would do.
        var approveBody = """
            {"actor":"bob-at-beta2","note":"come in"}
            """;
        var req = HttpRequest.newBuilder()
            .uri(URI.create(betaBaseUrl + "/api/home/grant-requests/"
                + knockResult.requestId() + "/approve"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(approveBody))
            .build();
        HttpResponse<String> resp = HttpClient.newHttpClient()
            .send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.path("status").asText()).isEqualTo("approved");
        assertThat(json.path("issuedGrantId").asText()).isNotBlank();

        // Alice now holds a grant on beta's registry.
        var held = betaClient.listHeldBy("alice-at-alpha");
        assertThat(held).anySatisfy(g -> {
            assertThat(g.resource().toString())
                .isEqualTo("home://bob-at-beta2/home-room");
            assertThat(g.capability()).isEqualTo(
                Capability.use);
        });
    }
}
