package org.wyrdsekai.server.http;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.server.http.OperatorToken;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `wyrd brain move` drafts her description of herself from her own record and, on the steward's
 * yes, writes it into her manifest as a new version through {@code POST /api/soul/{did}/identity}.
 * The launcher speaks with the node's operator token on loopback; a steward's session is the other
 * door; a member's is not, and nobody's at all is not.
 */
class HerDescriptionOfHerselfIsWrittenTest {

    private static final String DID = "did:key:z6MkTestMia";

    @TempDir
    Path dir;
    private AuthService auth;
    private SqlSoulStore souls;
    private Javalin app;
    private String base;
    private String operatorToken;
    private String stewardToken;
    private String memberToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        stewardToken = auth.register("steward", "test-pass", "Steward", "steward").orElseThrow().token();
        memberToken = auth.register("alice", "test-pass", "Alice", "member").orElseThrow().token();
        var pairing = new PairingService(jdbc, SqlDialect.fromJdbcUrl(jdbc), "hh", "Home", "did:key:zHome",
            "nats://127.0.0.1:4222", "http://127.0.0.1:7070");
        pairing.initSchema();
        OperatorToken.ensure(dir);
        operatorToken = Files.readString(dir.resolve("operator.token")).trim();
        souls = new SqlSoulStore(jdbc);
        souls.store(new SoulManifest(DID, null, null, null, 1, Instant.now(), null,
            null, null, null, 0, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null));
        app = Javalin.create(cfg -> {
            cfg.routes.beforeMatched(ApiAuth.filter(auth, pairing, null));
            new SoulRoutes(souls, auth).register(cfg.routes);
        }).start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + "/api/soul/" + DID + "/identity"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void the_launcher_writes_it_with_the_operator_token_as_a_new_version() throws Exception {
        var r = post(operatorToken, "{\"text\": \"You are mia. Your person is operator. You live with rose, a companion.\"}");
        assertEquals(200, r.statusCode(), r.body());
        var m = souls.latest(DID).orElseThrow();
        assertEquals(2, m.manifestVersion());
        assertTrue(m.residentIdentity().startsWith("You are mia."), m.residentIdentity());
        assertEquals(1, souls.load(DID, 1).orElseThrow().manifestVersion(), "the first version is kept");
    }

    @Test
    void a_steward_may_write_it_a_member_may_not_and_nobody_may_not() throws Exception {
        assertEquals(200, post(stewardToken, "{\"text\": \"You are mia.\"}").statusCode());
        assertEquals(403, post(memberToken, "{\"text\": \"You are someone else.\"}").statusCode());
        assertEquals(401, post(null, "{\"text\": \"You are nobody.\"}").statusCode());
        assertEquals("You are mia.", souls.latest(DID).orElseThrow().residentIdentity());
    }

    @Test
    void empty_or_overlong_text_is_refused() throws Exception {
        assertEquals(400, post(operatorToken, "{\"text\": \"   \"}").statusCode());
        assertEquals(400, post(operatorToken, "{\"text\": \"" + "x".repeat(2001) + "\"}").statusCode());
        assertEquals(1, souls.latest(DID).orElseThrow().manifestVersion());
    }
}
