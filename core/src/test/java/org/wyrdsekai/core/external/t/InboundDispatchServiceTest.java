package org.wyrdsekai.core.external.t;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Phase T (§4.34) dispatch service. Uses a stub invoker so
 * the test exercises {@link InboundDispatchService} routing + counters without
 * pulling in GraalJS.
 */
class InboundDispatchServiceTest {

    private InboundSubscriptionRegistry registry;
    private List<String> invocations;
    private InboundDispatchService dispatch;

    @BeforeEach
    void setUp() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
        registry = InboundSubscriptionRegistry.get(null);
        invocations = new ArrayList<>();
        var stubInvoker = new HookCallbackInvoker(
            null,
            itemId -> "function onWebhook(e){return {ok:true};}",
            (itemId, agentId) -> null,
            itemId -> ItemCapabilitySet.UNRESTRICTED) {
            @Override
            public Map<String, Object> invoke(String itemId, String agentId, String hookName,
                                                InboundEvent event) {
                invocations.add(itemId + ":" + hookName + ":" + event.kind());
                return Map.of("ok", true);
            }
        };
        dispatch = InboundDispatchService.init(registry, stubInvoker);
    }

    @AfterEach
    void tearDown() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
    }

    @Test
    void dispatch_to_unknown_subscription_drops() {
        var outcome = dispatch.dispatch("missing", InboundEvent.of("webhook", "/x", Map.of()));
        assertThat(outcome.decision()).isEqualTo(InboundSubscriptionRegistry.DeliveryDecision.NOT_FOUND);
        assertThat(dispatch.droppedCount()).isEqualTo(1);
        assertThat(invocations).isEmpty();
    }

    @Test
    void dispatch_delivers_when_subscription_active() {
        var subId = registry.add("pr_notifier", "did:wyrd:agent",
            "webhook", "onWebhook", "/gh", Map.of(), "secret", null);
        var event = InboundEvent.of("webhook", "/gh", Map.of("body", Map.of("action", "opened")));
        var outcome = dispatch.dispatch(subId, event);
        assertThat(outcome.decision()).isEqualTo(InboundSubscriptionRegistry.DeliveryDecision.DELIVER);
        assertThat(invocations).containsExactly("pr_notifier:onWebhook:webhook");
        assertThat(dispatch.deliveredCount()).isEqualTo(1);
    }

    @Test
    void dispatch_skips_paused_subscriptions() {
        var subId = registry.add("pr_notifier", "did:wyrd:agent",
            "webhook", "onWebhook", "/gh", Map.of(), "s", null);
        registry.pause("did:wyrd:agent", subId);
        var outcome = dispatch.dispatch(subId, InboundEvent.of("webhook", "/gh", Map.of()));
        assertThat(outcome.decision()).isEqualTo(InboundSubscriptionRegistry.DeliveryDecision.PAUSED);
        assertThat(invocations).isEmpty();
        assertThat(dispatch.droppedCount()).isEqualTo(1);
    }

    @Test
    void dispatch_rate_limited_after_cap_exceeded() {
        // Cap of 2/hour — third event is rate-limited.
        var subId = registry.add("flood", "did:wyrd:agent",
            "webhook", "onWebhook", "/x", Map.of(), "s", 2);
        for (int i = 0; i < 3; i++) {
            dispatch.dispatch(subId, InboundEvent.of("webhook", "/x", Map.of("i", i)));
        }
        assertThat(dispatch.deliveredCount()).isEqualTo(2);
        assertThat(dispatch.rateLimitedCount()).isEqualTo(1);
        assertThat(invocations).hasSize(2);
    }

    @Test
    void audit_listener_observes_each_dispatch() {
        var observed = new AtomicReference<InboundDispatchService.DispatchOutcome>();
        dispatch.setAuditListener((event, outcome) -> observed.set(outcome));
        var subId = registry.add("x", "did:wyrd:a", "webhook", "onWebhook", "/x", Map.of(), "s", null);
        dispatch.dispatch(subId, InboundEvent.of("webhook", "/x", Map.of()));
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().decision()).isEqualTo(
            InboundSubscriptionRegistry.DeliveryDecision.DELIVER);
        assertThat(observed.get().itemId()).isEqualTo("x");
    }

    @Test
    void resume_reactivates_paused_subscription() {
        var subId = registry.add("x", "did:wyrd:a", "webhook", "onWebhook", "/x", Map.of(), "s", null);
        registry.pause("did:wyrd:a", subId);
        registry.resume("did:wyrd:a", subId);
        var outcome = dispatch.dispatch(subId, InboundEvent.of("webhook", "/x", Map.of()));
        assertThat(outcome.decision()).isEqualTo(InboundSubscriptionRegistry.DeliveryDecision.DELIVER);
    }

    @Test
    void stats_summarise_counters_and_active_size() {
        var subId = registry.add("x", "did:wyrd:a", "webhook", "onWebhook", "/x", Map.of(), "s", null);
        dispatch.dispatch(subId, InboundEvent.of("webhook", "/x", Map.of()));
        var stats = dispatch.stats();
        assertThat(stats.get("delivered")).isEqualTo(1L);
        assertThat(stats.get("dropped")).isEqualTo(0L);
        assertThat(stats.get("rateLimited")).isEqualTo(0L);
        assertThat(stats.get("active")).isEqualTo(1L);
    }
}
