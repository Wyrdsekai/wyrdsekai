package org.wyrdsekai.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #12 (2026-07-19 OSS hardening) — brute-force throttle. */
class LoginRateLimiterTest {

    @Test
    void locks_out_after_max_failures() {
        var rl = new LoginRateLimiter(new LoginRateLimiter.Policy(3, 60_000L, 60_000L));
        var key = "acct:alice";
        assertThat(rl.isLocked(key)).isFalse();
        rl.recordFailure(key);
        rl.recordFailure(key);
        assertThat(rl.isLocked(key)).isFalse();   // 2 < 3
        rl.recordFailure(key);
        assertThat(rl.isLocked(key)).isTrue();     // 3rd trips lockout
    }

    @Test
    void success_clears_failures() {
        var rl = new LoginRateLimiter(new LoginRateLimiter.Policy(3, 60_000L, 60_000L));
        var key = "ip:192.0.2.1";
        rl.recordFailure(key);
        rl.recordFailure(key);
        rl.recordSuccess(key);
        rl.recordFailure(key);
        rl.recordFailure(key);
        assertThat(rl.isLocked(key)).isFalse();    // counter reset by success
    }

    @Test
    void anyLocked_checks_all_keys() {
        var rl = new LoginRateLimiter(new LoginRateLimiter.Policy(1, 60_000L, 60_000L));
        rl.recordFailure("ip:1.2.3.4");
        assertThat(rl.anyLocked("ip:1.2.3.4", "acct:bob")).isTrue();
        assertThat(rl.anyLocked("acct:bob", "ip:9.9.9.9")).isFalse();
    }

    @Test
    void lockout_expires_after_window() throws InterruptedException {
        var rl = new LoginRateLimiter(new LoginRateLimiter.Policy(1, 60_000L, 50L));
        var key = "acct:carol";
        rl.recordFailure(key);
        assertThat(rl.isLocked(key)).isTrue();
        Thread.sleep(80);
        assertThat(rl.isLocked(key)).isFalse();    // lockout elapsed
    }
}
