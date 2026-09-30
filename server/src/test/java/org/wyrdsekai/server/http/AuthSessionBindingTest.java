package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.server.auth.WebAuthnService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-09-28 audit: a passkey could be registered for any account by naming its id in the
 * body, a password change or recovery left every old session valid, and the recovery doors
 * had no throttle. Each binding is checked over real HTTP.
 */
@Tag("integration")
class AuthSessionBindingTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private Javalin app;
    private String base;
    private AuthService auth;
    private WebAuthnService webAuthn;
    private AuthService.Session steward;
    private AuthService.Session member;
    private String recoveryKey;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        auth = new AuthService(SchemaInitializer.initialize(tmp.resolve("world.db")));
        steward = auth.register("sam", "password1", "Sam").orElseThrow();
        member = auth.register("kai", "password2", "Kai").orElseThrow();
        recoveryKey = auth.generateRecoveryKey();
        webAuthn = new WebAuthnService("localhost", "Wyrdsekai");
        var routes = new AuthRoutes(auth, webAuthn);
        app = Javalin.create(cfg -> routes.register(cfg.routes)).start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void passkey_registration_needs_a_session() throws Exception {
        var begin = post("/api/auth/passkey/register/begin", null,
            "{\"user_id\":\"" + steward.userId() + "\",\"user_name\":\"sam\"}");
        assertThat(begin.statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/passkey/register/complete", null,
            "{\"challenge\":\"x\",\"credential_id\":\"c\",\"public_key\":\"k\"}").statusCode()).isEqualTo(401);
    }

    @Test
    void a_session_cannot_register_a_passkey_for_another_account() throws Exception {
        var begin = post("/api/auth/passkey/register/begin", member.token(),
            "{\"user_id\":\"" + steward.userId() + "\",\"user_name\":\"sam\"}");
        assertThat(begin.statusCode()).isEqualTo(403);
        assertThat(webAuthn.credentialsForUser(steward.userId())).isEmpty();
    }

    @Test
    void a_challenge_completes_only_for_the_session_it_was_issued_to() throws Exception {
        var challenge = webAuthn.beginRegistration(steward.userId(), "sam").challengeBase64();
        var stolen = post("/api/auth/passkey/register/complete", member.token(),
            "{\"challenge\":\"" + challenge + "\",\"credential_id\":\"c1\",\"public_key\":\"k\"}");
        assertThat(stolen.statusCode()).isEqualTo(403);
        assertThat(webAuthn.credentialsForUser(steward.userId())).isEmpty();
    }

    @Test
    void the_normal_flow_registers_to_the_session_user() throws Exception {
        var begin = post("/api/auth/passkey/register/begin", member.token(), "{}");
        assertThat(begin.statusCode()).isEqualTo(200);
        var issued = M.readTree(begin.body());
        assertThat(issued.path("userId").asText()).isEqualTo(member.userId());
        var complete = post("/api/auth/passkey/register/complete", member.token(),
            "{\"challenge\":\"" + issued.path("challengeBase64").asText()
                + "\",\"credential_id\":\"c2\",\"public_key\":\"k\",\"display_name\":\"phone\"}");
        assertThat(complete.statusCode()).isEqualTo(200);
        assertThat(webAuthn.credentialsForUser(member.userId())).hasSize(1);
    }

    @Test
    void a_password_change_ends_the_other_sessions_and_keeps_this_one() throws Exception {
        var other = auth.login("kai", "password2").orElseThrow();
        var changed = post("/api/auth/change-password", member.token(),
            "{\"oldPassword\":\"password2\",\"newPassword\":\"password3\"}");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(auth.validateSession(member.token())).isPresent();
        assertThat(auth.validateSession(other.token())).isEmpty();
        assertThat(auth.validateSession(steward.token())).as("another account is untouched").isPresent();
    }

    @Test
    void a_recovery_ends_every_steward_session() throws Exception {
        var recovered = post("/api/auth/recover", null,
            "{\"recoveryKey\":\"" + recoveryKey + "\",\"newPassword\":\"fresh-pass\"}");
        assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);
        assertThat(auth.validateSession(steward.token())).isEmpty();
        assertThat(auth.validateSession(member.token())).isPresent();
    }

    @Test
    void recovery_guesses_are_throttled_and_both_doors_share_the_count() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/auth/recover", null,
                "{\"recoveryKey\":\"wrong-" + i + "\",\"newPassword\":\"whatever\"}").statusCode()).isEqualTo(403);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(post("/api/auth/reset-zone", null,
                "{\"recoveryKey\":\"wrong-z" + i + "\"}").statusCode()).isEqualTo(403);
        }
        // Locked now, even for the right key: a guesser cannot tell a hit from the lock.
        assertThat(post("/api/auth/recover", null,
            "{\"recoveryKey\":\"" + recoveryKey + "\",\"newPassword\":\"fresh-pass\"}").statusCode()).isEqualTo(429);
        assertThat(post("/api/auth/reset-zone", null,
            "{\"recoveryKey\":\"" + recoveryKey + "\"}").statusCode()).isEqualTo(429);
        assertThat(auth.countUsers()).isEqualTo(2);
    }
}
