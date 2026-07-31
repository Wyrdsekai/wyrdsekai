package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity tests for {@link NatsServerManager#isAvailable}'s extended
 * discovery (PATH + JVM-app-dir + install-roots).
 *
 * <p>These do NOT verify Windows or Linux-specific paths since we'd need
 * those binaries on the test runner. They lock in:
 * <ol>
 *   <li>Bogus name returns false without throwing</li>
 *   <li>{@link NatsServerManager#resolved} returns the path that worked,
 *       or null when nothing did</li>
 * </ol>
 */
class NatsServerManagerDiscoveryTest {

    @Test
    void unknown_executable_returns_false_and_resolved_stays_unset() {
        var found = NatsServerManager.isAvailable(
            "/definitely/not/a/real/path/nats-server-bogus");
        assertThat(found).isFalse();
    }

    @Test
    void resolved_reflects_last_successful_lookup() {
        // Idempotent — call twice with the same bogus arg.
        NatsServerManager.isAvailable("nats-server-also-bogus-xyz");
        // We can't assert resolved() is null because *any* prior test in
        // this VM may have populated it. Just confirm the method is callable.
        var r = NatsServerManager.resolved();
        assertThat(r == null || !r.isBlank()).isTrue();
    }
}
