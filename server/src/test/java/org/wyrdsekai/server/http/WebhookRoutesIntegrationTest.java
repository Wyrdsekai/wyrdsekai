package org.wyrdsekai.server.http;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.t.HookCallbackInvoker;
import org.wyrdsekai.core.external.t.InboundDispatchService;
import org.wyrdsekai.core.external.t.InboundEvent;
import org.wyrdsekai.core.external.t.InboundSubscriptionRegistry;
import org.wyrdsekai.core.external.t.WebhookListener;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Phase T smoke test: subscribes a webhook through the
 * {@link WebhookListener}, fires up Javalin with the {@code WebhookRoutes}
 * registered, sends a real HMAC-signed POST to the assigned subscription
 * URL, and asserts the hook was invoked with the right payload.
 *
 * <p>Counterpart to {@code PhaseTAdaptersBootstrapTest} which covers the
 * adapter-registry side; this one covers the HTTP side. Together they pin
 * down the full {@code script subscribe → POST → dispatch} chain.</p>
 */
class WebhookRoutesIntegrationTest {

    private InboundSubscriptionRegistry registry;
    private InboundDispatchService dispatch;
    private WebhookListener listener;
    private Javalin app;
    private HttpClient http;
    private String baseUrl;
    private AtomicReference<InboundEvent> deliveredEvent;
    private AtomicReference<String> deliveredItemId;
    private AtomicReference<String> deliveredHook;

    @BeforeEach
    void setUp() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
        registry = InboundSubscriptionRegistry.get(null);
        deliveredEvent = new AtomicReference<>();
        deliveredItemId = new AtomicReference<>();
        deliveredHook = new AtomicReference<>();
        var stub = new HookCallbackInvoker(null,
            id -> "function onWebhook(){return {ok:true};}",
            (a, b) -> null,
            id -> ItemCapabilitySet.UNRESTRICTED) {
            @Override
            public Map<String, Object> invoke(String itemId, String agentId,
                                                String hookName, InboundEvent event) {
                deliveredItemId.set(itemId);
                deliveredHook.set(hookName);
                deliveredEvent.set(event);
                return Map.of("ok", true, "echoed", event.source());
            }
        };
        dispatch = InboundDispatchService.init(registry, stub);
        listener = new WebhookListener(registry, dispatch);
        var routes = new WebhookRoutes(listener);
        app = Javalin.create(cfg -> routes.register(cfg.routes))
            .start("127.0.0.1", 0);
        baseUrl = "http://127.0.0.1:" + app.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
    }

    @Test
    void signed_post_dispatches_to_hook() throws Exception {
        var sub = listener.subscribe("github_pr_notifier", "did:wyrd:steward",
            "/gh/pr", "onWebhook", null);
        var subId = String.valueOf(sub.get("subscriptionId"));
        var secret = String.valueOf(sub.get("secret"));
        var url = String.valueOf(sub.get("url")); // /api/webhook/{id}

        var body = "{\"action\":\"opened\",\"number\":42}";
        var sig = "sha256=" + hmacSha256Hex(secret, body);

        var resp = http.send(HttpRequest.newBuilder(URI.create(baseUrl + url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("X-Wyrdsekai-Signature", sig)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(), "valid signature should return 200");
        assertNotNull(deliveredEvent.get(), "hook should have been invoked");
        assertEquals("github_pr_notifier", deliveredItemId.get());
        assertEquals("onWebhook", deliveredHook.get());
        assertEquals("webhook", deliveredEvent.get().kind());
        assertEquals("/gh/pr", deliveredEvent.get().source());
    }

    @Test
    void unsigned_post_is_rejected_401() throws Exception {
        var sub = listener.subscribe("noisy_thing", "did:wyrd:steward",
            "/x", "onWebhook", null);
        var subId = String.valueOf(sub.get("subscriptionId"));

        var resp = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/webhook/" + subId))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, resp.statusCode());
        assertNull(deliveredEvent.get(), "no signature → no dispatch");
    }

    @Test
    void post_to_unknown_subscription_returns_404() throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/webhook/does-not-exist"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("X-Wyrdsekai-Signature", "sha256=deadbeef")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }

    private static String hmacSha256Hex(String secret, String body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
