package org.wyrdsekai.server.http;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The login filter in front of /api (2026-09-28). A real account store, a real Study, a
 * live HTTP server on loopback: who gets through to which kind of route, and whose Study
 * a caller can reach.
 */
class ApiAuthTest {

    @TempDir Path dir;
    private Javalin app;
    private String base;
    private AuthService auth;
    private String stewardToken, aliceToken, bobToken, aliceId, bobId, operatorToken, deviceToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        stewardToken = auth.register("steward", "test-pass", "Steward", "steward").orElseThrow().token();
        var alice = auth.register("alice", "test-pass", "Alice", "member").orElseThrow();
        var bob = auth.register("bob", "test-pass", "Bob", "member").orElseThrow();
        aliceToken = alice.token(); aliceId = alice.userId();
        bobToken = bob.token(); bobId = bob.userId();
        var pairing = new PairingService(jdbc, SqlDialect.fromJdbcUrl(jdbc), "hh", "Home", "did:key:zHome",
            "nats://127.0.0.1:4222", "http://127.0.0.1:7070");
        pairing.initSchema();
        deviceToken = pairing.pairWithKey(pairing.generateHouseholdKey(), "kitchen tablet", "tablet", null)
            .orElseThrow().token();
        OperatorToken.ensure(dir);
        operatorToken = Files.readString(dir.resolve("operator.token")).trim();

        var study = new StudyService(new WyrdLuceneStore(dir.resolve("search"), 384));
        app = start("127.0.0.1", auth, pairing, study);
        base = "http://127.0.0.1:" + app.port();
    }

    private static Javalin start(String host, AuthService auth, PairingService pairing, StudyService study) {
        return Javalin.create(cfg -> {
            cfg.routes.beforeMatched(ApiAuth.filter(auth, pairing, null));
            cfg.routes.post("/api/auth/login", ctx -> ctx.result("public"));
            cfg.routes.get("/api/pair/household-key", ctx -> ctx.result("the key"));
            cfg.routes.get("/api/federation/status", ctx -> ctx.result("status"));
            cfg.routes.get("/api/shadow", ctx -> ctx.result("prompts"));
            cfg.routes.get("/api/not-in-the-policy", ctx -> ctx.result("unlisted"));
            new StudyRoutes(study, null).register(cfg.routes);
        }).start(host, 0);
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private int get(String path, String token) throws Exception {
        return get(base, path, token);
    }

    private int get(String baseUrl, String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(5)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void withoutALoginOnlyPublicRoutesAnswer() throws Exception {
        var login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(get("/api/pair/household-key", null)).isEqualTo(401);
        assertThat(get("/api/federation/status", null)).isEqualTo(401);
        assertThat(get("/api/study/status", null)).isEqualTo(401);
        assertThat(get("/api/shadow", null)).isEqualTo(401);
        assertThat(get("/api/federation/status", "not-a-token")).isEqualTo(401);
    }

    @Test
    void aMemberReachesLoginRoutesButNotTheStewards() throws Exception {
        assertThat(get("/api/federation/status", aliceToken)).isEqualTo(200);
        assertThat(get("/api/pair/household-key", aliceToken)).isEqualTo(403);
        assertThat(get("/api/shadow", aliceToken)).isEqualTo(403);
        assertThat(get("/api/not-in-the-policy", aliceToken)).isEqualTo(403);
    }

    @Test
    void theStewardReachesStewardRoutesButNotTheMachinesOwn() throws Exception {
        assertThat(get("/api/pair/household-key", stewardToken)).isEqualTo(200);
        assertThat(get("/api/not-in-the-policy", stewardToken)).isEqualTo(200);
        assertThat(get("/api/shadow", stewardToken)).isEqualTo(403);
    }

    @Test
    void theOperatorTokenFromThisMachineIsTheOperator() throws Exception {
        assertThat(get("/api/shadow", operatorToken)).isEqualTo(200);
        assertThat(get("/api/pair/household-key", operatorToken)).isEqualTo(200);
    }

    @Test
    void aDeviceLinkedToNobodyIsLoggedInButIsNotAPerson() throws Exception {
        assertThat(get("/api/federation/status", deviceToken)).isEqualTo(200);
        assertThat(get("/api/pair/household-key", deviceToken)).isEqualTo(403);
        assertThat(get("/api/study/status", deviceToken)).isEqualTo(403);
    }

    @Test
    void aPersonReachesOnlyTheirOwnStudy() throws Exception {
        assertThat(get("/api/study/status", aliceToken)).isEqualTo(200);
        assertThat(get("/api/study/status?user=" + aliceId, aliceToken)).isEqualTo(200);
        assertThat(get("/api/study/status?user=" + bobId, aliceToken)).isEqualTo(403);
        assertThat(get("/api/study/status?user=bob", aliceToken)).isEqualTo(403);
        assertThat(get("/api/study/journal?user=" + aliceId, bobToken)).isEqualTo(403);
        assertThat(get("/api/study/journal", bobToken)).isEqualTo(200);
    }

    @Test
    void theOperatorMayNameAPersonForStatusButNeverReadsTheirJournal() throws Exception {
        assertThat(get("/api/study/status?user=" + bobId, operatorToken)).isEqualTo(200);
        assertThat(get("/api/study/journal?user=" + bobId, operatorToken)).isEqualTo(403);
        assertThat(get("/api/study/search?q=x&user=" + bobId, operatorToken)).isEqualTo(403);
    }

    @Test
    void theOperatorTokenIsRefusedFromAnotherMachine() throws Exception {
        String lan = null;
        for (var nif : NetworkInterface.networkInterfaces().toList()) {
            if (!nif.isUp() || nif.isLoopback()) continue;
            for (var a : nif.getInterfaceAddresses()) {
                if (a.getAddress() instanceof Inet4Address v4 && !v4.isLinkLocalAddress()) lan = v4.getHostAddress();
            }
        }
        assumeTrue(lan != null, "no non-loopback address on this machine");
        var lanApp = start(lan, auth, null, new StudyService(new WyrdLuceneStore(dir.resolve("search2"), 384)));
        try {
            var lanBase = "http://" + lan + ":" + lanApp.port();
            assertThat(get(lanBase, "/api/shadow", operatorToken)).isEqualTo(401);
            assertThat(get(lanBase, "/api/pair/household-key", stewardToken)).isEqualTo(200);
        } finally {
            lanApp.stop();
        }
    }
}
