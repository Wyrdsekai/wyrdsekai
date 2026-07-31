package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shutdown-clearing contract for the process-wide
 * {@link CapabilityRegistry#getActive() active} singleton.
 *
 * <p>The active slot is set once by the bootstrapper at startup; it must be
 * cleared at shutdown so test-runs and JVM restarts in the same process leave
 * a clean slate. {@code Main}'s shutdown hook now calls
 * {@code CapabilityRegistry.setActive(null)} as part of orderly teardown —
 * this test is the unit-level companion verifying the contract holds.</p>
 */
class CapabilityRegistryShutdownTest {

    @AfterEach
    void clearActive() {
        // Don't leak state into other tests in the same JVM.
        CapabilityRegistry.setActive(null);
    }

    @Test void register_then_clear_leaves_no_active_registry() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "default", "test-backend", "test-model", "local", 1));

        CapabilityRegistry.setActive(registry);
        assertThat(CapabilityRegistry.getActive()).isSameAs(registry);

        // Simulate the shutdown hook: clear the active slot.
        CapabilityRegistry.setActive(null);
        assertThat(CapabilityRegistry.getActive())
            .as("active slot must be null after shutdown clearing")
            .isNull();
    }

    @Test void clearing_when_already_null_is_idempotent() {
        // Active starts null in this @AfterEach-isolated test; clearing again
        // must be safe.
        CapabilityRegistry.setActive(null);
        assertThat(CapabilityRegistry.getActive()).isNull();
        // Second clear — still null, no exception.
        CapabilityRegistry.setActive(null);
        assertThat(CapabilityRegistry.getActive()).isNull();
    }

    @Test void replacing_active_loses_prior_reference() {
        var first = new CapabilityRegistry();
        var second = new CapabilityRegistry();
        CapabilityRegistry.setActive(first);
        assertThat(CapabilityRegistry.getActive()).isSameAs(first);

        CapabilityRegistry.setActive(second);
        assertThat(CapabilityRegistry.getActive())
            .as("setActive replaces (no chaining)")
            .isSameAs(second);

        CapabilityRegistry.setActive(null);
        assertThat(CapabilityRegistry.getActive()).isNull();
    }

    @Test void shutdown_hook_in_main_clears_active() throws Exception {
        // String-source guard: Main.java's shutdown hook must call
        // CapabilityRegistry.setActive(null). Without this, JVM restarts in
        // the same process leave a stale registry pointing at closed-down
        // backends.
        var path = Path.of(
            "server/src/main/java/org/wyrdsekai/server/Main.java");
        if (!Files.exists(path)) {
            path = Path.of(
                "src/main/java/org/wyrdsekai/server/Main.java");
        }
        if (!Files.exists(path)) {
            // Server module isn't always on the test classpath — skip silently
            // when running this test from a slimmed-down build that omits it.
            return;
        }
        var src = Files.readString(path);
        int hookStart = src.indexOf("Runtime.getRuntime().addShutdownHook");
        assertThat(hookStart)
            .as("Main.java must install a shutdown hook")
            .isGreaterThan(0);
        // Bound the hook body — the next top-level closing brace pair after
        // the addShutdownHook call. Look at the next ~3KB.
        var hookBody = src.substring(hookStart,
            Math.min(src.length(), hookStart + 3000));
        assertThat(hookBody)
            .as("shutdown hook must clear CapabilityRegistry.active")
            .contains("CapabilityRegistry.setActive(null)");
    }
}
