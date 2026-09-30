package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /api/household/members} over HTTP, on the real accounts: anyone with an account reads
 * the list, only a steward promotes or removes, nobody removes themself, and every refusal is in
 * the audit log. Before 2026-09-28 these routes read an empty in-memory permission table and
 * answered 403 to everyone.
 */
@Tag("integration")
class HouseholdRoutesTest {

    private Javalin app;
    private String base;
    private AuthService auth;
    private StewardAuditLog auditLog;
    private final HttpClient http = HttpClient.newHttpClient();

    private String annId;
    private String annToken;
    private String maxId;
    private String maxToken;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        auth = new AuthService(SchemaInitializer.initialize(dir.resolve("world.db")));
        auditLog = new StewardAuditLog();
        var ann = auth.register("ann", "password123", "Ann").orElseThrow();   // first: steward
        var max = auth.register("max", "password123", "Max").orElseThrow();   // member
        annId = ann.userId();
        annToken = ann.token();
        maxId = max.userId();
        maxToken = max.token();
        var routes = new HouseholdRoutes(auditLog, auth);
        app = Javalin.create(cfg -> routes.register(cfg.routes)).start(0);
        base = "http://localhost:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    void anyone_with_an_account_lists_the_members() throws Exception {
        var resp = send("GET", "/api/household/members", maxToken);
        assertThat(resp.statusCode()).isEqualTo(200);
        var list = json(resp);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("username").asText()).isEqualTo("ann");
        assertThat(list.get(0).path("role").asText()).isEqualTo("steward");
        assertThat(list.get(1).path("role").asText()).isEqualTo("member");
        assertThat(send("GET", "/api/household/members", null).statusCode()).isEqualTo(401);
    }

    @Test
    void the_steward_promotes_a_member() throws Exception {
        var resp = send("POST", "/api/household/members/" + maxId + "/promote", annToken);
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(json(resp).path("role").asText()).isEqualTo("steward");
        assertThat(auth.findUser(maxId).orElseThrow().role()).isEqualTo("steward");
        assertThat(auditLog.recent(1).getFirst().approved()).isTrue();
    }

    @Test
    void a_member_cannot_promote_or_remove_and_the_attempt_is_logged() throws Exception {
        assertThat(send("POST", "/api/household/members/" + maxId + "/promote", maxToken).statusCode())
            .isEqualTo(403);
        assertThat(send("DELETE", "/api/household/members/" + annId, maxToken).statusCode()).isEqualTo(403);
        assertThat(auth.findUser(maxId).orElseThrow().role()).isEqualTo("member");
        assertThat(auth.findUser(annId)).isPresent();
        assertThat(auditLog.denied(10)).hasSize(2);
    }

    @Test
    void the_steward_removes_a_member_but_not_themself() throws Exception {
        var self = send("DELETE", "/api/household/members/" + annId, annToken);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(auth.findUser(annId)).isPresent();

        assertThat(send("DELETE", "/api/household/members/" + maxId, annToken).statusCode()).isEqualTo(204);
        assertThat(auth.findUser(maxId)).isEmpty();
        assertThat(send("DELETE", "/api/household/members/nobody", annToken).statusCode()).isEqualTo(404);
    }

    @Test
    void a_second_steward_may_remove_the_first_and_is_then_the_last() throws Exception {
        auth.setRole(annId, maxId, "steward");
        assertThat(send("DELETE", "/api/household/members/" + annId, maxToken).statusCode()).isEqualTo(204);
        assertThat(auth.listUsers()).extracting(AuthService.User::role).containsExactly("steward");
        assertThat(auth.changeRole(maxId, maxId, "member")).isEqualTo(AuthService.RoleChange.LAST_STEWARD);
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> resp) throws Exception {
        return Json.mapper().readTree(resp.body());
    }
}
