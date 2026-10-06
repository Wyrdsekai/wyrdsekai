package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session's seven days count from the last time it was seen, not from the login. A browser tab
 * kept open past a week was signed out at the first reconnect, which a restart forces (a
 * household's 0.5.4 report, 2026-10-05).
 */
@Tag("integration")
class SessionsSlideWhileInUseTest {

    private String jdbc;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        jdbc = TestDb.createInMemory();
        auth = new AuthService(jdbc);
    }

    private void expiresIn(String token, Duration d) throws Exception {
        try (var conn = DriverManager.getConnection(jdbc);
             var st = conn.prepareStatement("UPDATE sessions SET expires_at = ? WHERE token = ?")) {
            st.setLong(1, Instant.now().plus(d).getEpochSecond());
            st.setString(2, token);
            assertThat(st.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    void a_session_that_is_used_gets_its_full_life_back() throws Exception {
        var s = auth.register("sam", "password1", "Sam").orElseThrow();
        expiresIn(s.token(), Duration.ofHours(20));           // most of its week is gone
        assertThat(auth.validateSession(s.token())).isPresent();
        var after = auth.sessionExpiry(s.token()).orElseThrow();
        assertThat(Duration.between(Instant.now(), after)).isGreaterThan(Duration.ofDays(6).plusHours(23));
    }

    @Test
    void a_fresh_session_is_not_rewritten_on_every_look() throws Exception {
        var s = auth.register("sam", "password1", "Sam").orElseThrow();
        var fresh = Instant.now().plus(Duration.ofDays(6).plusHours(20));
        expiresIn(s.token(), Duration.ofDays(6).plusHours(20));
        assertThat(auth.validateSession(s.token())).isPresent();
        assertThat(auth.sessionExpiry(s.token()).orElseThrow()).isBetween(fresh.minusSeconds(2), fresh.plusSeconds(2));
    }

    @Test
    void a_session_that_ran_out_is_not_brought_back() throws Exception {
        var s = auth.register("sam", "password1", "Sam").orElseThrow();
        expiresIn(s.token(), Duration.ofSeconds(-10));
        auth.touchSession(s.token());
        assertThat(auth.validateSession(s.token())).isEmpty();
        assertThat(auth.sessionExpiry(s.token()).orElseThrow()).isBefore(Instant.now());
    }

    @Test
    void an_open_socket_touches_its_session_once_an_hour() throws Exception {
        var base = java.nio.file.Files.exists(java.nio.file.Path.of("..", "core")) ? java.nio.file.Path.of("..") : java.nio.file.Path.of(".");
        var ws = java.nio.file.Files.readString(base.resolve("server/src/main/java/org/wyrdsekai/server/ws/WyrdWebSocket.java"));
        int onMessage = ws.indexOf("ws.onMessage(ctx -> {");
        int touch = ws.indexOf("authService.touchSession(sessionToken);", onMessage);
        assertThat(touch).isGreaterThan(onMessage);
        assertThat(ws.substring(onMessage, touch)).contains("nowMs - last > 3_600_000L");
        assertThat(ws).contains("sessionTouchedAt.remove(sessionId);");
    }
}
