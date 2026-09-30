package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A password change or a steward recovery left every existing session token valid, so a stolen
 * token outlived the password it was stolen with (2026-09-28 audit).
 */
@Tag("integration")
class PasswordChangeEndsSessionsTest {

    private AuthService auth;

    @BeforeEach
    void setUp() {
        auth = new AuthService(TestDb.createInMemory());
    }

    @Test
    void the_changing_session_stays_and_the_others_end() {
        var here = auth.register("sam", "password1", "Sam").orElseThrow();
        var elsewhere = auth.login("sam", "password1").orElseThrow();
        var someoneElse = auth.register("kai", "password2", "Kai").orElseThrow();

        assertThat(auth.changePassword(here.userId(), "password1", "password9", here.token())).isTrue();
        assertThat(auth.validateSession(here.token())).isPresent();
        assertThat(auth.validateSession(elsewhere.token())).isEmpty();
        assertThat(auth.validateSession(someoneElse.token())).isPresent();
    }

    @Test
    void a_change_from_a_surface_without_a_session_ends_them_all() {
        var a = auth.register("sam", "password1", "Sam").orElseThrow();
        var b = auth.login("sam", "password1").orElseThrow();
        assertThat(auth.changePassword(a.userId(), "password1", "password9")).isTrue();
        assertThat(auth.validateSession(a.token())).isEmpty();
        assertThat(auth.validateSession(b.token())).isEmpty();
    }

    @Test
    void a_wrong_current_password_ends_nothing() {
        var a = auth.register("sam", "password1", "Sam").orElseThrow();
        assertThat(auth.changePassword(a.userId(), "nope", "password9")).isFalse();
        assertThat(auth.validateSession(a.token())).isPresent();
    }

    @Test
    void a_recovery_ends_every_steward_session() {
        var steward = auth.register("sam", "password1", "Sam").orElseThrow();
        var member = auth.register("kai", "password2", "Kai").orElseThrow();
        var key = auth.generateRecoveryKey();
        assertThat(auth.recoverSteward(key, "password9")).isTrue();
        assertThat(auth.validateSession(steward.token())).isEmpty();
        assertThat(auth.validateSession(member.token())).isPresent();
        assertThat(auth.login("sam", "password9")).isPresent();
    }

    @Test
    void the_minimum_is_four_characters() {
        assertThat(AuthService.passwordLongEnough("abc")).isFalse();
        assertThat(AuthService.passwordLongEnough("abcd")).isTrue();
        assertThat(AuthService.passwordLongEnough(null)).isFalse();
    }
}
