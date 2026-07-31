package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Wave 5.3c — pure-logic verification for the
 * relay-pool query cadence scheduler. Bypasses the actual network by
 * driving an empty pool: {@code register} must not throw, the cache
 * starts empty, and direct {@code latestForAgent} reads round-trip
 * through the pool's subscribe path safely.
 *
 * <p>End-to-end network behaviour is exercised by higher-level
 * integration tests that stand up a real Nostr relay; those gate on
 * the existing {@code @EnabledIfEnvironmentVariable} pattern.
 */
class RelayPoolSchedulerTest {

    private RelayPoolScheduler sched;
    private NostrRelayPool pool;

    @BeforeEach
    void setup() {
        // Empty relay list = pool has no connections; subscribe is a no-op
        // and latestForAgent returns empty until an event is observed.
        pool = new NostrRelayPool(List.of());
        sched = new RelayPoolScheduler(pool, Duration.ofSeconds(60));
        // Don't start the scheduler — we exercise the register/cache
        // surface synchronously here so tests are deterministic.
    }

    @AfterEach
    void teardown() {
        sched.close();
        RelayPoolScheduler.resetForTests();
    }

    @Test
    void register_unknown_did_returns_empty_latest() {
        sched.register("did:wyrd:agent-alpha");
        var latest = sched.latestForAgent("did:wyrd:agent-alpha");
        assertThat(latest).isEmpty();
    }

    @Test
    void register_is_idempotent() {
        sched.register("did:wyrd:agent-alpha");
        sched.register("did:wyrd:agent-alpha");
        sched.register("did:wyrd:agent-alpha");
        assertThat(sched.registeredAgents()).containsExactly("did:wyrd:agent-alpha");
    }

    @Test
    void register_then_unregister_removes_from_watch_list() {
        sched.register("did:wyrd:agent-alpha");
        sched.register("did:wyrd:agent-beta");
        sched.unregister("did:wyrd:agent-alpha");
        assertThat(sched.registeredAgents()).containsExactly("did:wyrd:agent-beta");
        assertThat(sched.latestForAgent("did:wyrd:agent-alpha")).isEmpty();
    }

    @Test
    void null_or_blank_did_rejected_silently() {
        sched.register(null);
        sched.register("");
        sched.register("   ");
        assertThat(sched.registeredAgents()).isEmpty();
    }

    @Test
    void latestForAgent_handles_unregistered_did() {
        // Querying an agent that was never registered must return empty
        // rather than throwing — keeps the substrate-side read surface
        // tolerant.
        assertThat(sched.latestForAgent("did:wyrd:never-registered")).isEmpty();
        assertThat(sched.lastPolledAt("did:wyrd:never-registered")).isEmpty();
    }

    @Test
    void initFromEnv_disabled_when_env_off() {
        RelayPoolScheduler.resetForTests();
        // No env vars set in test process → disabled.
        var instance = RelayPoolScheduler.initFromEnv();
        assertThat(instance).isEmpty();
        assertThat(RelayPoolScheduler.get()).isEmpty();
    }

    @Test
    void initWithPool_installs_singleton_for_tests() {
        RelayPoolScheduler.resetForTests();
        var testPool = new NostrRelayPool(List.of());
        var testSched = new RelayPoolScheduler(testPool, Duration.ofSeconds(60));
        RelayPoolScheduler.initWithPool(testSched);
        assertThat(RelayPoolScheduler.get()).hasValue(testSched);
    }

    @Test
    void close_is_idempotent_and_blocks_further_register() {
        sched.register("did:wyrd:agent-alpha");
        sched.close();
        sched.close();  // second close should be a no-op
        // After close, register is a no-op (won't add new entries)
        sched.register("did:wyrd:agent-gamma");
        // alpha is still in the cache from before close — close
        // doesn't wipe state, it just stops the scheduler thread —
        // but gamma should NOT have been added post-close.
        assertThat(sched.registeredAgents()).doesNotContain("did:wyrd:agent-gamma");
    }
}
