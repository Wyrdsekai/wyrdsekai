package org.wyrdsekai.core.external.t;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase T HMAC + dispatch tests for the webhook listener. */
class WebhookListenerTest {

    private InboundSubscriptionRegistry registry;
    private InboundDispatchService dispatch;
    private WebhookListener listener;
    private List<InboundEvent> deliveredEvents;

    @BeforeEach
    void setUp() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
        registry = InboundSubscriptionRegistry.get(null);
        deliveredEvents = new ArrayList<>();
        var stub = new HookCallbackInvoker(null,
            id -> "function onWebhook(){return {ok:true};}",
            (a, b) -> null,
            id -> ItemCapabilitySet.UNRESTRICTED) {
            @Override
            public Map<String, Object> invoke(String itemId, String agentId,
                                                String hookName, InboundEvent event) {
                deliveredEvents.add(event);
                return Map.of("ok", true);
            }
        };
        dispatch = InboundDispatchService.init(registry, stub);
        listener = new WebhookListener(registry, dispatch);
    }

    @AfterEach
    void tearDown() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
    }

    @Test
    void subscribe_returns_url_and_secret() {
        var res = listener.subscribe("pr_notifier", "did:wyrd:a", "/gh", "onWebhook", null);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(String.valueOf(res.get("url"))).startsWith("/api/webhook/");
        assertThat(String.valueOf(res.get("secret"))).hasSizeGreaterThan(20);
    }

    @Test
    void valid_signature_dispatches_event() {
        var res = listener.subscribe("pr_notifier", "did:wyrd:a", "/gh", "onWebhook", null);
        var subId = String.valueOf(res.get("subscriptionId"));
        var secret = String.valueOf(res.get("secret"));

        var body = "{\"action\":\"opened\",\"number\":42}".getBytes();
        var sig = WebhookListener.computeHmac(secret, body);
        var headers = new HashMap<String, String>();
        headers.put(WebhookListener.SIG_HEADER, "sha256=" + sig);

        var outcome = listener.handle(subId, body, headers);
        assertThat(outcome).isEqualTo(WebhookListener.Result.DELIVERED);
        assertThat(deliveredEvents).hasSize(1);
        var event = deliveredEvents.get(0);
        assertThat(event.kind()).isEqualTo("webhook");
        @SuppressWarnings("unchecked")
        var bodyMap = (Map<String, Object>) event.payload().get("body");
        assertThat(bodyMap).containsEntry("action", "opened").containsEntry("number", 42);
    }

    @Test
    void invalid_signature_drops_event() {
        var res = listener.subscribe("pr_notifier", "did:wyrd:a", "/gh", "onWebhook", null);
        var subId = String.valueOf(res.get("subscriptionId"));
        var headers = new HashMap<String, String>();
        headers.put(WebhookListener.SIG_HEADER, "sha256=deadbeef");
        var outcome = listener.handle(subId, "{}".getBytes(), headers);
        assertThat(outcome).isEqualTo(WebhookListener.Result.UNAUTHORIZED);
        assertThat(deliveredEvents).isEmpty();
    }

    @Test
    void missing_signature_drops_event() {
        var res = listener.subscribe("pr_notifier", "did:wyrd:a", "/gh", "onWebhook", null);
        var subId = String.valueOf(res.get("subscriptionId"));
        var outcome = listener.handle(subId, "{}".getBytes(), Map.of());
        assertThat(outcome).isEqualTo(WebhookListener.Result.UNAUTHORIZED);
    }

    @Test
    void unknown_subscription_returns_not_found() {
        var outcome = listener.handle("does-not-exist", "{}".getBytes(), Map.of());
        assertThat(outcome).isEqualTo(WebhookListener.Result.NOT_FOUND);
    }

    @Test
    void github_signature_header_accepted() {
        var res = listener.subscribe("pr_notifier", "did:wyrd:a", "/gh", "onWebhook", null);
        var subId = String.valueOf(res.get("subscriptionId"));
        var secret = String.valueOf(res.get("secret"));
        var body = "{\"x\":1}".getBytes();
        var sig = WebhookListener.computeHmac(secret, body);
        var headers = Map.of(WebhookListener.GITHUB_SIG_HEADER, "sha256=" + sig);
        assertThat(listener.handle(subId, body, headers)).isEqualTo(WebhookListener.Result.DELIVERED);
    }

    @Test
    void rate_limit_returns_429() {
        var res = listener.subscribe("pr_notifier", "did:wyrd:a", "/gh", "onWebhook",
            Map.of("capPerHour", 1));
        var subId = String.valueOf(res.get("subscriptionId"));
        var secret = String.valueOf(res.get("secret"));
        var body = "{}".getBytes();
        var headers = Map.of(WebhookListener.SIG_HEADER,
            "sha256=" + WebhookListener.computeHmac(secret, body));
        assertThat(listener.handle(subId, body, headers))
            .isEqualTo(WebhookListener.Result.DELIVERED);
        assertThat(listener.handle(subId, body, headers))
            .isEqualTo(WebhookListener.Result.RATE_LIMITED);
    }

    @Test
    void case_insensitive_header_lookup() {
        var res = listener.subscribe("x", "did:wyrd:a", "/x", "onW", null);
        var subId = String.valueOf(res.get("subscriptionId"));
        var secret = String.valueOf(res.get("secret"));
        var body = "{}".getBytes();
        var sig = WebhookListener.computeHmac(secret, body);
        var headers = Map.of("x-wyrdsekai-signature", "sha256=" + sig);
        assertThat(listener.handle(subId, body, headers))
            .isEqualTo(WebhookListener.Result.DELIVERED);
    }
}
