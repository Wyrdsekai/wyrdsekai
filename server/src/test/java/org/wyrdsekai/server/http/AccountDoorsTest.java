package org.wyrdsekai.server.http;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.security.LoginRateLimiter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The account doors over HTTP: the first registration is one steward however many arrive at
 * once, the bootstrap invite brings the recovery key, and {@code /api/mcp/login} is throttled
 * like {@code /api/auth/login}, sharing its count per account.
 */
@Tag("integration")
class AccountDoorsTest {

    private Javalin app;
    private String base;
    private AuthService auth;
    private InviteService invites;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        LoginRateLimiter.shared().clear();
        var url = SchemaInitializer.initialize(dir.resolve("world.db"));
        auth = new AuthService(url);
        invites = new InviteService(url);
        var authRoutes = new AuthRoutes(auth, invites, null, null);
        var mcpRoutes = new McpRoutes(auth, null);
        app = Javalin.create(cfg -> {
            authRoutes.register(cfg.routes);
            mcpRoutes.register(cfg.routes);
        }).start(0);
        base = "http://localhost:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        LoginRateLimiter.shared().clear();
    }

    @Test
    @DisplayName("eight registrations on a fresh zone at the same moment make one steward")
    void simultaneousRegistrations() throws Exception {
        int n = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(n);
        var results = new ArrayList<Future<HttpResponse<String>>>();
        try {
            for (int i = 0; i < n; i++) {
                var body = "{\"username\":\"founder" + i + "\",\"password\":\"password123\"}";
                results.add(pool.submit(() -> {
                    start.await();
                    return post("/api/auth/register", body);
                }));
            }
            start.countDown();
            int created = 0;
            String recoveryKey = null;
            for (var f : results) {
                var resp = f.get(60, TimeUnit.SECONDS);
                if (resp.statusCode() == 201) {
                    created++;
                    recoveryKey = Json.mapper().readTree(resp.body()).path("recoveryKey").asText(null);
                } else {
                    assertThat(resp.statusCode()).isIn(403, 409);
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(auth.verifyRecoveryKey(recoveryKey)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(auth.listUsers()).hasSize(1);
        assertThat(auth.listUsers().get(0).role()).isEqualTo("steward");
    }

    @Test
    @DisplayName("redeeming the bootstrap invite over HTTP returns the recovery key once")
    void bootstrapInviteOverHttp() throws Exception {
        var bootstrap = invites.createBootstrapInvite("steward", 3600);
        var resp = post("/api/auth/redeem",
            "{\"code\":\"" + bootstrap.code() + "\",\"username\":\"steward\",\"password\":\"password123\"}");
        assertThat(resp.statusCode()).isEqualTo(201);
        var body = Json.mapper().readTree(resp.body());
        assertThat(body.path("role").asText()).isEqualTo("steward");
        assertThat(auth.verifyRecoveryKey(body.path("recoveryKey").asText())).isTrue();

        var steward = auth.findUserByUsername("steward").orElseThrow();
        var member = invites.createInvite("kaz", "member", steward.id());
        var joined = post("/api/auth/redeem",
            "{\"code\":\"" + member.code() + "\",\"username\":\"kaz\",\"password\":\"password123\"}");
        assertThat(joined.statusCode()).isEqualTo(201);
        assertThat(Json.mapper().readTree(joined.body()).path("recoveryKey").asText(null)).isNull();
    }

    @Test
    @DisplayName("/api/mcp/login locks after five failures, and the lock holds on /api/auth/login too")
    void mcpLoginIsThrottled() throws Exception {
        auth.registerFirstSteward("ann", "password123", null);
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/mcp/login", "{\"username\":\"ann\",\"password\":\"wrong" + i + "\"}").statusCode())
                .isEqualTo(401);
        }
        assertThat(post("/api/mcp/login", "{\"username\":\"ann\",\"password\":\"password123\"}").statusCode())
            .as("refused before the password is checked").isEqualTo(429);
        assertThat(post("/api/auth/login", "{\"username\":\"ann\",\"password\":\"password123\"}").statusCode())
            .isEqualTo(429);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
