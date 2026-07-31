package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.t.WebhookListener;

import java.util.HashMap;
import java.util.Map;

/**
 * (Phase T) — HTTP endpoint that receives
 * inbound webhooks for scripted items.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code POST /api/webhook/{subscriptionId}} — accepts the upstream
 *       payload, validates the HMAC signature, dispatches to the item's hook
 *       (synchronously) and returns 200/401/404/429 depending on outcome.</li>
 * </ul>
 *
 * <p>Wired in {@code Main.java} after {@code WebhookListener} construction.
 * Tests call {@link WebhookListener#handle} directly without touching this
 * class.</p>
 */
public final class WebhookRoutes {

    private static final Logger log = LoggerFactory.getLogger(WebhookRoutes.class);

    private final WebhookListener listener;

    public WebhookRoutes(WebhookListener listener) {
        this.listener = listener;
    }

    public void register(JavalinDefaultRoutingApi app) {
        if (listener == null) return;
        app.post("/api/webhook/{subscriptionId}", this::handle);
    }

    private void handle(Context ctx) {
        var id = ctx.pathParam("subscriptionId");
        var headers = new HashMap<String, String>();
        for (var name : ctx.headerMap().keySet()) {
            headers.put(name, ctx.header(name));
        }
        var body = ctx.bodyAsBytes();
        var result = listener.handle(id, body, headers);
        switch (result) {
            case DELIVERED      -> ctx.status(200).json(Map.of("ok", true));
            case UNAUTHORIZED   -> ctx.status(401).json(Map.of("ok", false, "error", "invalid_signature"));
            case NOT_FOUND      -> ctx.status(404).json(Map.of("ok", false, "error", "not_found"));
            case PAUSED         -> ctx.status(202).json(Map.of("ok", false, "error", "paused"));
            case RATE_LIMITED   -> ctx.status(429).json(Map.of("ok", false, "error", "rate_limited"));
        }
    }
}
