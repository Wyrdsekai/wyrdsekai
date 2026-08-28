package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.wyrdsekai.core.coding.ConsentBroker;
import org.wyrdsekai.core.persistence.AuthService;

import java.util.ArrayList;
import java.util.Map;

/**
 * Steward-consent surface (2026-08-16) — backs {@code wyrd consent}.
 *
 * <ul>
 *   <li>{@code GET  /api/consents} — unanswered permission asks, oldest first</li>
 *   <li>{@code POST /api/consents/{id}} — body {@code {"allow": true|false}}</li>
 * </ul>
 *
 * <p>Both steward-token gated (same matrix as {@code /api/recipes/run}:
 * no token → 401, member → 403). A late answer to an already-resolved ask
 * returns 410 — the refusal that silence meant has already happened, and a
 * grant must never resurrect it.</p>
 */
public final class ConsentRoutes {

    private ConsentRoutes() {}

    public static void register(JavalinConfig cfg, AuthService authService) {
        register(cfg, authService, ConsentBroker.get());
    }

    /** Test seam: any broker. */
    public static void register(JavalinConfig cfg, AuthService authService,
                                ConsentBroker broker) {
        cfg.routes.get("/api/consents", StewardGate.gated(authService, ctx -> {
            var rows = new ArrayList<Map<String, Object>>();
            for (var p : broker.pending()) {
                rows.add(Map.of(
                    "id", p.id(),
                    "backend", p.backend(),
                    "taskId", p.taskId(),
                    "summary", p.summary(),
                    "createdAt", p.createdAt().toString()));
            }
            ctx.json(Map.of("count", rows.size(), "rows", rows));
        }));

        cfg.routes.post("/api/consents/{id}", StewardGate.gated(authService, ctx -> {
            var id = ctx.pathParam("id");
            var body = new ObjectMapper().readTree(ctx.body());
            if (!body.has("allow") || !body.get("allow").isBoolean()) {
                ctx.status(400).json(Map.of("error", "body must carry boolean 'allow'"));
                return;
            }
            boolean allow = body.get("allow").asBoolean();
            if (broker.answer(id, allow)) {
                ctx.json(Map.of("id", id, "answered", allow ? "allow" : "deny"));
            } else {
                ctx.status(410).json(Map.of(
                    "error", "consent unknown or already resolved — "
                        + "silence already meant no"));
            }
        }));
    }
}
