package org.wyrdsekai.core.external.t;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase T persistence + rate-limit unit tests. */
class InboundSubscriptionRegistryTest {

    @BeforeEach
    void setUp() {
        InboundSubscriptionRegistry.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        InboundSubscriptionRegistry.resetForTesting();
    }

    @Test
    void add_then_find_returns_subscription() {
        var reg = InboundSubscriptionRegistry.get(null);
        var id = reg.add("item-a", "did:wyrd:a", "webhook", "onWebhook",
            "/path", Map.of("foo", "bar"), "secret-1", null);
        var sub = reg.find(id).orElseThrow();
        assertThat(sub.itemId()).isEqualTo("item-a");
        assertThat(sub.kind()).isEqualTo("webhook");
        assertThat(sub.target()).isEqualTo("/path");
        assertThat(sub.secret()).isEqualTo("secret-1");
        assertThat(sub.capPerHour()).isEqualTo(InboundSubscriptionRegistry.DEFAULT_CAP_PER_HOUR);
    }

    @Test
    void cancel_owner_scoped_only() {
        var reg = InboundSubscriptionRegistry.get(null);
        var id = reg.add("x", "did:wyrd:owner", "webhook", "onW", "/p", Map.of(), "s", null);
        assertThat(reg.cancel("did:wyrd:other", id)).isFalse();
        assertThat(reg.find(id)).isPresent();
        assertThat(reg.cancel("did:wyrd:owner", id)).isTrue();
        assertThat(reg.find(id)).isNotPresent();
    }

    @Test
    void rate_limit_evaluate_blocks_after_cap() {
        var reg = InboundSubscriptionRegistry.get(null);
        var id = reg.add("x", "did:wyrd:a", "webhook", "onW", "/p", Map.of(), "s", 3);
        for (int i = 0; i < 3; i++) {
            assertThat(reg.evaluate(id))
                .isEqualTo(InboundSubscriptionRegistry.DeliveryDecision.DELIVER);
        }
        // Next one over the cap.
        assertThat(reg.evaluate(id))
            .isEqualTo(InboundSubscriptionRegistry.DeliveryDecision.RATE_LIMITED);
    }

    @Test
    void pause_returns_paused_decision() {
        var reg = InboundSubscriptionRegistry.get(null);
        var id = reg.add("x", "did:wyrd:a", "webhook", "onW", "/p", Map.of(), "s", null);
        reg.pause("did:wyrd:a", id);
        assertThat(reg.evaluate(id))
            .isEqualTo(InboundSubscriptionRegistry.DeliveryDecision.PAUSED);
    }

    @Test
    void list_filters_by_agent() {
        var reg = InboundSubscriptionRegistry.get(null);
        reg.add("x", "alice", "webhook", "onW", "/a", Map.of(), "s", null);
        reg.add("y", "bob", "webhook", "onW", "/b", Map.of(), "s", null);
        assertThat(reg.list("alice")).hasSize(1);
        assertThat(reg.list("bob")).hasSize(1);
        assertThat(reg.list(null)).hasSize(2);
    }

    @Test
    void persisted_subscriptions_survive_restart(@TempDir Path tmp) throws Exception {
        var jdbc = "jdbc:sqlite:" + tmp.resolve("phase-t.db");
        var reg = InboundSubscriptionRegistry.get(jdbc);
        var id = reg.add("pr_notifier", "did:wyrd:a", "webhook", "onWebhook",
            "/gh", Map.of("foo", "bar"), "secret-x", 100);

        // Simulate restart.
        InboundSubscriptionRegistry.resetForTesting();
        var reloaded = InboundSubscriptionRegistry.get(jdbc);
        var sub = reloaded.find(id).orElseThrow();
        assertThat(sub.itemId()).isEqualTo("pr_notifier");
        assertThat(sub.kind()).isEqualTo("webhook");
        assertThat(sub.secret()).isEqualTo("secret-x");
        assertThat(sub.capPerHour()).isEqualTo(100);
        assertThat(sub.opts()).containsEntry("foo", "bar");
    }
}
