package org.wyrdsekai.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W16 — auth matrix for the production
 * {@code POST /api/recipes/run} route. The route is registered
 * unconditionally in {@link Main} and gated on a steward session token;
 * this test mounts the REAL handler ({@link Main#recipesRunHandler}) on a
 * bare Javalin app (mirrors RecipesRoutesIntegrationTest's harness) and
 * pins the three-token matrix:
 * <ul>
 *   <li>no token → 401</li>
 *   <li>valid member (non-steward) token → 403</li>
 *   <li>steward token → passes auth (reaches dispatch; a body-validation
 *       400 from dispatchForcedRecipeRun proves we got PAST the gate)</li>
 * </ul>
 * Session creation mirrors AuthRoutes/AuthService semantics: the FIRST
 * registered user becomes steward, subsequent users are members.
 */
@Tag("integration")
class RecipesRunAuthMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP =
        new TypeReference<>() {};

    private Javalin app;
    private HttpClient http;
    private String baseUrl;
    private AuthService auth;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        var jdbcUrl = SchemaInitializer.initialize(tmp.resolve("world.db"));
        auth = new AuthService(jdbcUrl);
        app = Javalin.create(cfg ->
            cfg.routes.post("/api/recipes/run",
                Main.recipesRunHandler(auth, jdbcUrl))).start(0);
        baseUrl = "http://localhost:" + app.port();
        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    void no_token_is_401() throws Exception {
        var resp = post(null, "{}");
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(body(resp)).containsEntry("error", "Authorization required");
    }

    @Test
    void garbage_token_is_401() throws Exception {
        var resp = post("not-a-real-session-token", "{}");
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(body(resp))
            .containsEntry("error", "Invalid or expired session");
    }

    @Test
    void member_token_is_403() throws Exception {
        // First user = steward; SECOND user = plain member.
        auth.register("alice", "steward-pass-123", "Alice").orElseThrow();
        var member = auth.register("bob", "member-pass-123", "Bob")
            .orElseThrow();
        assertThat(auth.validateSession(member.token())
            .orElseThrow().role()).isNotEqualTo("steward");

        var resp = post(member.token(), "{}");
        assertThat(resp.statusCode()).isEqualTo(403);
        assertThat(body(resp)).containsEntry("error", "Steward role required");
    }

    @Test
    void steward_token_passes_auth_gate() throws Exception {
        var steward = auth.register("alice", "steward-pass-123", "Alice")
            .orElseThrow();
        assertThat(auth.validateSession(steward.token())
            .orElseThrow().role()).isEqualTo("steward");

        // Empty body: dispatchForcedRecipeRun rejects with its OWN 400
        // ("entityId and recipe required") — distinct from every auth-gate
        // response, so a 400 with that message proves the steward cleared
        // the gate and reached the shared dispatch path.
        var resp = post(steward.token(), "{}");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(body(resp))
            .containsEntry("error", "entityId and recipe required");
    }

    private HttpResponse<String> post(String token, String json)
            throws Exception {
        var builder = HttpRequest
            .newBuilder(URI.create(baseUrl + "/api/recipes/run"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> body(HttpResponse<String> resp) {
        try {
            return MAPPER.readValue(resp.body(), MAP);
        } catch (Exception e) {
            throw new RuntimeException(
                "unparseable body: " + resp.body(), e);
        }
    }
}
