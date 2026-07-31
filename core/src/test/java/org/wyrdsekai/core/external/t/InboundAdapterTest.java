package org.wyrdsekai.core.external.t;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase T tests for the unified inbound adapter (subscribe/list/cancel/pause/resume). */
class InboundAdapterTest {

    private InboundSubscriptionRegistry registry;
    private InboundAdapter adapter;

    @BeforeEach
    void setUp() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
        registry = InboundSubscriptionRegistry.get(null);
        var stub = new HookCallbackInvoker(null,
            id -> "function onEvent(){return {ok:true};}",
            (a, b) -> null,
            id -> ItemCapabilitySet.UNRESTRICTED) {
            @Override
            public Map<String, Object> invoke(String itemId, String agentId,
                                                String hookName, InboundEvent event) {
                return Map.of("ok", true);
            }
        };
        var dispatch = InboundDispatchService.init(registry, stub);
        var webhook = new WebhookListener(registry, dispatch);
        adapter = new InboundAdapter(registry, webhook, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
    }

    @Test
    void webhook_subscribe_via_adapter_returns_subscription_id() {
        var args = Map.<String, Object>of(
            "agentId", "did:wyrd:a",
            "path", "/gh-prs",
            "hookName", "onWebhook");
        var req = new AdapterRequest(
            "inbound", "webhook", args,
            ItemCapabilitySet.UNRESTRICTED, "pr_notifier");
        var res = adapter.invoke(req);
        assertThat(res.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) res.data();
        assertThat(data.get("subscriptionId")).isNotNull();
        assertThat(String.valueOf(data.get("url"))).startsWith("/api/webhook/");
    }

    @Test
    void list_returns_subscriptions_for_agent() {
        registry.add("x", "alice", "webhook", "onW", "/a", Map.of(), "s", null);
        registry.add("y", "bob", "webhook", "onW", "/b", Map.of(), "s", null);
        var req = new AdapterRequest("inbound", "list",
            Map.of("agentId", "alice"),
            ItemCapabilitySet.UNRESTRICTED, "x");
        var res = adapter.invoke(req);
        assertThat(res.success()).isTrue();
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) res.data();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("kind")).isEqualTo("webhook");
    }

    @Test
    void cancel_removes_subscription() {
        var id = registry.add("x", "alice", "webhook", "onW", "/a", Map.of(), "s", null);
        var req = new AdapterRequest("inbound", "cancel",
            Map.of("agentId", "alice", "subscriptionId", id),
            ItemCapabilitySet.UNRESTRICTED, "x");
        var res = adapter.invoke(req);
        assertThat(res.success()).isTrue();
        assertThat(registry.find(id)).isNotPresent();
    }

    @Test
    void pause_then_resume_round_trip() {
        var id = registry.add("x", "alice", "webhook", "onW", "/a", Map.of(), "s", null);
        var pauseReq = new AdapterRequest("inbound", "pause",
            Map.of("agentId", "alice", "subscriptionId", id),
            ItemCapabilitySet.UNRESTRICTED, "x");
        assertThat(adapter.invoke(pauseReq).success()).isTrue();
        var resumeReq = new AdapterRequest("inbound", "resume",
            Map.of("agentId", "alice", "subscriptionId", id),
            ItemCapabilitySet.UNRESTRICTED, "x");
        assertThat(adapter.invoke(resumeReq).success()).isTrue();
    }

    @Test
    void unknown_method_returns_failure() {
        var req = new AdapterRequest("inbound", "telepathy",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, "x");
        var res = adapter.invoke(req);
        assertThat(res.success()).isFalse();
        assertThat(res.error().code()).isEqualTo("unknown_method");
    }

    @Test
    void unwired_email_listener_returns_subscribe_failed() {
        // No emailListener wired — adapter should refuse rather than NPE.
        var req = new AdapterRequest("inbound", "email_watch",
            Map.of("agentId", "did:wyrd:a", "filter", Map.of(), "hookName", "onEmail"),
            ItemCapabilitySet.UNRESTRICTED, "x");
        var res = adapter.invoke(req);
        assertThat(res.success()).isFalse();
        assertThat(res.error().message()).contains("email listener not wired");
    }
}
