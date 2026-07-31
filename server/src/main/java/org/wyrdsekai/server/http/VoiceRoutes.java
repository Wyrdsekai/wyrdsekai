package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.VoiceProfile;
import org.wyrdsekai.core.soul.VoiceProfileService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * HTTP endpoints for a companion's {@link VoiceProfile} (#414).
 *
 * <h3>Surface</h3>
 * <pre>
 *   GET    /api/voice/{did}                  — current profile
 *   PUT    /api/voice/{did}                  — replace entire clause map
 *   POST   /api/voice/{did}/clauses/{key}    — set one clause (body: {"value":"...","reason":"..."})
 *   DELETE /api/voice/{did}/clauses/{key}    — unset one clause (body: {"reason":"..."})
 *   POST   /api/voice/{did}/freeze           — freeze the profile
 *   POST   /api/voice/{did}/unfreeze         — unfreeze the profile
 *   POST   /api/voice/{did}/revert/{rev}     — revert to revision N
 * </pre>
 *
 * <p><b>Author tracking:</b> every write takes an {@code actor} query param or
 * {@code X-Wyrd-Actor} header identifying who made the change. Recorded in the
 * profile's history alongside the reason. M2a tightens this to session-derived
 * DIDs via AuthService; for now it's caller-asserted.
 *
 * <p>The heavy lifting lives in {@link VoiceProfileService}: these handlers
 * just parse + delegate + format. Errors map to standard HTTP statuses —
 * 404 when no manifest, 409 when frozen, 400 on bad input.
 */
public final class VoiceRoutes {

    private static final Logger log = LoggerFactory.getLogger(VoiceRoutes.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VoiceProfileService service;

    public VoiceRoutes(VoiceProfileService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/voice/{did}", this::handleGet);
        app.put("/api/voice/{did}", this::handleReplaceAll);
        app.post("/api/voice/{did}/clauses/{key}", this::handleSetClause);
        app.delete("/api/voice/{did}/clauses/{key}", this::handleUnsetClause);
        app.post("/api/voice/{did}/freeze", this::handleFreeze);
        app.post("/api/voice/{did}/unfreeze", this::handleUnfreeze);
        app.post("/api/voice/{did}/revert/{rev}", this::handleRevert);
    }

    // ─── Handlers ──────────────────────────────────────────────────

    private void handleGet(Context ctx) {
        var did = ctx.pathParam("did");
        var vp = service.get(did);
        if (vp.isEmpty()) {
            ctx.status(404).json(Map.of("error", "no manifest for " + did));
            return;
        }
        ctx.json(toJson(vp.get()));
    }

    private void handleReplaceAll(Context ctx) {
        var did = ctx.pathParam("did");
        var actor = requireActor(ctx);
        if (actor == null) return;
        try {
            var body = MAPPER.readTree(ctx.body());
            var clausesNode = body.get("clauses");
            if (clausesNode == null || !clausesNode.isObject()) {
                ctx.status(400).json(Map.of("error", "body must have clauses object"));
                return;
            }
            var clauses = new LinkedHashMap<String, String>();
            clausesNode.fields().forEachRemaining(e ->
                clauses.put(e.getKey(), e.getValue().asText("")));
            var reason = textOrDefault(body, "reason", "replace via PUT");
            var updated = service.replaceClauses(did, clauses, reason, actor);
            ctx.json(toJson(updated));
        } catch (NoSuchElementException nsee) {
            ctx.status(404).json(Map.of("error", nsee.getMessage()));
        } catch (IllegalStateException frozen) {
            ctx.status(409).json(Map.of("error", frozen.getMessage()));
        } catch (Exception e) {
            log.warn("PUT /api/voice failed: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleSetClause(Context ctx) {
        var did = ctx.pathParam("did");
        var key = ctx.pathParam("key");
        var actor = requireActor(ctx);
        if (actor == null) return;
        try {
            var body = MAPPER.readTree(ctx.body());
            var value = textOrNull(body, "value");
            if (value == null) {
                ctx.status(400).json(Map.of("error", "body.value is required"));
                return;
            }
            var reason = textOrDefault(body, "reason", "set " + key);
            var updated = service.setClause(did, key, value, reason, actor);
            ctx.json(toJson(updated));
        } catch (NoSuchElementException nsee) {
            ctx.status(404).json(Map.of("error", nsee.getMessage()));
        } catch (IllegalStateException frozen) {
            ctx.status(409).json(Map.of("error", frozen.getMessage()));
        } catch (IllegalArgumentException bad) {
            ctx.status(400).json(Map.of("error", bad.getMessage()));
        } catch (Exception e) {
            log.warn("POST /api/voice/clauses failed: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleUnsetClause(Context ctx) {
        var did = ctx.pathParam("did");
        var key = ctx.pathParam("key");
        var actor = requireActor(ctx);
        if (actor == null) return;
        String reason = "unset " + key;
        try {
            if (ctx.body() != null && !ctx.body().isBlank()) {
                var body = MAPPER.readTree(ctx.body());
                reason = textOrDefault(body, "reason", reason);
            }
            var updated = service.unsetClause(did, key, reason, actor);
            ctx.json(toJson(updated));
        } catch (NoSuchElementException nsee) {
            ctx.status(404).json(Map.of("error", nsee.getMessage()));
        } catch (IllegalStateException frozen) {
            ctx.status(409).json(Map.of("error", frozen.getMessage()));
        } catch (Exception e) {
            log.warn("DELETE /api/voice/clauses failed: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }

    private void handleFreeze(Context ctx) {
        var did = ctx.pathParam("did");
        var actor = requireActor(ctx);
        if (actor == null) return;
        try {
            ctx.json(toJson(service.freeze(did, actor)));
        } catch (NoSuchElementException nsee) {
            ctx.status(404).json(Map.of("error", nsee.getMessage()));
        }
    }

    private void handleUnfreeze(Context ctx) {
        var did = ctx.pathParam("did");
        var actor = requireActor(ctx);
        if (actor == null) return;
        try {
            ctx.json(toJson(service.unfreeze(did, actor)));
        } catch (NoSuchElementException nsee) {
            ctx.status(404).json(Map.of("error", nsee.getMessage()));
        }
    }

    private void handleRevert(Context ctx) {
        var did = ctx.pathParam("did");
        var revStr = ctx.pathParam("rev");
        var actor = requireActor(ctx);
        if (actor == null) return;
        int rev;
        try {
            rev = Integer.parseInt(revStr);
        } catch (NumberFormatException nfe) {
            ctx.status(400).json(Map.of("error", "revision must be an integer"));
            return;
        }
        try {
            ctx.json(toJson(service.revertTo(did, rev, actor)));
        } catch (NoSuchElementException nsee) {
            ctx.status(404).json(Map.of("error", nsee.getMessage()));
        } catch (IllegalStateException frozen) {
            ctx.status(409).json(Map.of("error", frozen.getMessage()));
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────

    /** Extract the acting DID from ?actor= or X-Wyrd-Actor. Writes 400 + returns null if missing. */
    private String requireActor(Context ctx) {
        var a = ctx.queryParam("actor");
        if (a == null || a.isBlank()) a = ctx.header("X-Wyrd-Actor");
        if (a == null || a.isBlank()) {
            ctx.status(400).json(Map.of("error",
                "actor is required (query param ?actor= or header X-Wyrd-Actor)"));
            return null;
        }
        return a;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        var v = node.get(field);
        if (v == null || v.isNull()) return null;
        var s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        var s = textOrNull(node, field);
        return s == null ? fallback : s;
    }

    /** Render a VoiceProfile as a plain map (serializable + client-friendly). */
    private static Map<String, Object> toJson(VoiceProfile vp) {
        var out = new LinkedHashMap<String, Object>();
        out.put("revision", vp.revision());
        out.put("frozen", vp.frozen());
        out.put("clauses", vp.clauses());
        var history = new ArrayList<Map<String, Object>>();
        for (var r : vp.history()) {
            var h = new LinkedHashMap<String, Object>();
            h.put("at", r.at() != null ? r.at().toString() : null);
            h.put("fromRevision", r.fromRevision());
            h.put("toRevision", r.toRevision());
            h.put("author", r.author());
            h.put("reason", r.reason());
            history.add(h);
        }
        out.put("history", history);
        return out;
    }
}
