package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.body.ImmuneMemory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.host.HookRules;
import org.wyrdsekai.core.host.HostDoors;
import org.wyrdsekai.core.host.ToolHooks;
import org.wyrdsekai.core.body.BodyMark;
import org.wyrdsekai.core.body.BodyPart;
import org.wyrdsekai.core.body.BodyWatch;
import org.wyrdsekai.core.body.Interoception;
import org.wyrdsekai.core.body.PartState;
import org.wyrdsekai.core.persistence.AuthService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The steward's view of the body: {@code wyrd body}.
 *
 * <p>Every attached part with its state, how long it has been quiet, when it last worked,
 * and the marks the body has left. The map names internal addresses, so it is the steward's,
 * like the mail log. Declaring a part gone is the authority the map waits for.</p>
 */
public final class BodyRoutes {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AuthService auth;

    public BodyRoutes(AuthService auth) {
        this.auth = auth;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/body", this::handleBody);
        app.post("/api/body/gone", this::handleGone);
        app.post("/api/body/vouch", this::handleVouch);
        app.get("/api/body/immune", this::handleImmune);
        app.post("/api/body/forget", this::handleForget);
        app.post("/api/body/door", this::handleDoor);
        app.get("/api/body/hooks", this::handleHooks);
        app.post("/api/body/hooks/replay", ctx -> handleHookRules(ctx, false));
        app.post("/api/body/hooks/arm", ctx -> handleHookRules(ctx, true));
    }

    record ErrorResponse(String error) {}

    private boolean steward(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Authentication required"));
            return false;
        }
        var user = auth.validateSession(token);
        if (user.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return false;
        }
        if (!"steward".equals(user.get().role())) {
            ctx.status(403).json(new ErrorResponse("Only the steward reads the body map"));
            return false;
        }
        return true;
    }

    private void handleBody(Context ctx) {
        if (!steward(ctx)) return;
        var map = BodyMap.get();
        if (map == null) {
            ctx.json(Map.of("parts", List.of(), "marks", List.of(), "host", ""));
            return;
        }
        var now = Instant.now();
        var parts = new ArrayList<Map<String, Object>>();
        for (var p : map.parts()) parts.add(partRow(p, now));
        var marks = new ArrayList<Map<String, Object>>();
        for (var m : map.recentMarks(30)) marks.add(markRow(m));
        var watch = BodyWatch.current();
        var host = watch == null || watch.host() == null ? "" : watch.host().gauges();
        ctx.json(Map.of("parts", parts, "marks", marks, "host", host, "hands", ToolHooks.handsLine()));
    }

    /** {@code {"part": "brain:peer-9b"}}: the steward says a quiet part is not coming back. */
    private void handleGone(Context ctx) {
        if (!steward(ctx)) return;
        var map = BodyMap.get();
        if (map == null) {
            ctx.status(404).json(new ErrorResponse("No body map on this node"));
            return;
        }
        String id = null;
        try {
            var node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
            if (node.hasNonNull("part")) id = node.get("part").asText();
        } catch (Exception ignored) {
            // fall through to the 400
        }
        if (id == null || id.isBlank()) {
            ctx.status(400).json(new ErrorResponse("Which part? {\"part\": \"<id>\"}"));
            return;
        }
        var done = map.declareGone(id, "the steward");
        if (done.isEmpty()) {
            ctx.status(404).json(new ErrorResponse("No part called " + id));
            return;
        }
        ctx.json(partRow(done.get(), Instant.now()));
    }

    /** {@code {"part": "node:abc"}}: the steward vouches for a part held at the door. */
    private void handleVouch(Context ctx) {
        if (!steward(ctx)) return;
        var map = BodyMap.get();
        if (map == null) { ctx.status(404).json(new ErrorResponse("No body map on this node")); return; }
        var id = field(ctx, "part");
        if (id == null) { ctx.status(400).json(new ErrorResponse("Which part? {\"part\": \"<id>\"}")); return; }
        var done = map.vouch(id, "the steward");
        if (done.isEmpty()) { ctx.status(404).json(new ErrorResponse("No part called " + id)); return; }
        ctx.json(partRow(done.get(), Instant.now()));
    }

    /** What the body remembers acting against, unexpired, most recent first. */
    private void handleImmune(Context ctx) {
        if (!steward(ctx)) return;
        var m = ImmuneMemory.get();
        var rows = new java.util.ArrayList<Map<String, Object>>();
        if (m != null) {
            for (var e : m.list()) {
                var r = new LinkedHashMap<String, Object>();
                r.put("id", e.id()); r.put("kind", e.kind()); r.put("subject", e.subject()); r.put("reason", e.reason());
                r.put("source", e.source()); r.put("firstSeen", e.firstSeen().toString()); r.put("lastSeen", e.lastSeen().toString());
                r.put("count", e.count()); r.put("expiresAt", e.expiresAt() == null ? null : e.expiresAt().toString());
                rows.add(r);
            }
        }
        var held = new java.util.ArrayList<Map<String, Object>>();
        var map = BodyMap.get();
        if (map != null) for (var p : map.quarantined()) held.add(partRow(p, Instant.now()));
        ctx.json(Map.of("remembered", rows, "held", held));
    }

    /** {@code {"id": "..."}}: the steward forgets one remembered thing. */
    private void handleForget(Context ctx) {
        if (!steward(ctx)) return;
        var m = ImmuneMemory.get();
        var id = field(ctx, "id");
        if (m == null || id == null) { ctx.status(400).json(new ErrorResponse("Which entry? {\"id\": \"<id>\"}")); return; }
        ctx.json(Map.of("forgotten", m.forget(id)));
    }

    private static String field(Context ctx, String name) {
        try {
            var node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
            return node.hasNonNull(name) ? node.get(name).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The hooks: mode, rules, how much of her history there is to try a rule against. */
    private void handleHooks(Context ctx) {
        if (!steward(ctx)) return;
        var h = ToolHooks.get();
        if (h == null) { ctx.status(404).json(new ErrorResponse("No hooks on this node")); return; }
        ctx.json(h.describe());
    }

    /**
     * {@code {"rules": {...}, "force": false}}. Replay tries a candidate rule set against what
     * her hands have been seen to do and changes nothing; arm does the same and installs the
     * rules only if the replay is clean and her history is long enough, unless forced.
     */
    private void handleHookRules(Context ctx, boolean arm) {
        if (!steward(ctx)) return;
        var h = ToolHooks.get();
        if (h == null) { ctx.status(404).json(new ErrorResponse("No hooks on this node")); return; }
        HookRules candidate = null;
        boolean force = false;
        try {
            var node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
            if (node.hasNonNull("rules")) candidate = HookRules.parse(node.get("rules").toString());
            force = node.path("force").asBoolean(false);
        } catch (Exception e) {
            ctx.status(400).json(new ErrorResponse("The rules are not valid JSON: " + e.getMessage()));
            return;
        }
        if (!arm) {
            ctx.json(h.replay(candidate).asMap());
            return;
        }
        if (candidate == null) { ctx.status(400).json(new ErrorResponse("arm needs {\"rules\": {...}}")); return; }
        var armed = h.arm(candidate, force, java.time.Duration.ofDays(WyrdConfig.get().hooksReplayDays()), "the steward", BodyMap.get());
        var out = new LinkedHashMap<String, Object>();
        out.put("armed", armed.ok());
        out.put("why", armed.why());
        out.put("replay", armed.replay().asMap());
        ctx.json(out);
    }

    /** {@code {"door": "door:relay", "action": "close|open|list"}}: the steward works a door's firewall set. */
    private void handleDoor(Context ctx) {
        if (!steward(ctx)) return;
        String door = null, action = "list";
        try {
            var node = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
            if (node.hasNonNull("door")) door = node.get("door").asText();
            if (node.hasNonNull("action")) action = node.get("action").asText();
        } catch (Exception ignored) {
            // fall through to the 400
        }
        var result = switch (action) {
            case "close" -> HostDoors.close(door, "the steward");
            case "open" -> HostDoors.open(door, "the steward");
            case "list" -> HostDoors.list();
            default -> null;
        };
        if (result == null) {
            ctx.status(400).json(new ErrorResponse("action is close, open or list"));
            return;
        }
        ctx.json(result);
    }

    static Map<String, Object> partRow(BodyPart p, Instant now) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", p.id());
        m.put("kind", p.kind().name().toLowerCase());
        m.put("name", p.name());
        m.put("state", p.state().name().toLowerCase());
        m.put("weight", p.descriptor().feltWeight().name().toLowerCase());
        m.put("owner", p.descriptor().owner());
        m.put("transport", p.descriptor().transport() == null ? "" : p.descriptor().transport());
        m.put("heartbeatSeconds", p.descriptor().heartbeatEvery().toSeconds());
        m.put("lastHeartbeat", p.lastHeartbeat() == null ? null : p.lastHeartbeat().toEpochMilli());
        m.put("lastUsed", p.lastUsed() == null ? null : p.lastUsed().toEpochMilli());
        m.put("quietFor", p.state() == PartState.ATTACHED ? "" : Interoception.roughly(p.numbFor(now)));
        m.put("detail", p.lastDetail() == null ? "" : p.lastDetail());
        m.put("attachedBy", p.descriptor().attachedBy() == null ? "household" : p.descriptor().attachedBy());
        m.put("vouchedBy", p.vouchedBy() == null ? "" : p.vouchedBy());
        m.put("goneBy", p.goneBy() == null ? "" : p.goneBy());
        return m;
    }

    static Map<String, Object> markRow(BodyMark m) {
        var row = new LinkedHashMap<String, Object>();
        row.put("at", m.at().toEpochMilli());
        row.put("kind", m.kind());
        row.put("subject", m.subject() == null ? "" : m.subject());
        row.put("audience", m.audience() == null ? "" : m.audience());
        row.put("text", m.text());
        row.put("detail", m.detail() == null ? "" : m.detail());
        row.put("read", !m.readBy().isEmpty());
        return row;
    }

    /** The same map as prose, for the boiler room's gauges and the engine room's panel. */
    public static String describeBody() {
        var map = BodyMap.get();
        if (map == null) return "No body map on this node.";
        var now = Instant.now();
        var sb = new StringBuilder();
        var watch = BodyWatch.current();
        if (watch != null && watch.host() != null) sb.append("Host: ").append(watch.host().gauges()).append("\n");
        sb.append("Hands: ").append(ToolHooks.handsLine()).append("\n");
        var parts = map.parts();
        if (parts.isEmpty()) sb.append("No parts attached yet.\n");
        for (var p : parts) {
            sb.append(String.format("%-9s %-28s %-8s", p.kind().name().toLowerCase(), p.name(), p.state().name().toLowerCase()));
            if (p.state() != PartState.ATTACHED) sb.append(" quiet ").append(Interoception.roughly(p.numbFor(now)));
            if (p.lastUsed() != null) sb.append("  last worked ").append(Interoception.roughly(java.time.Duration.between(p.lastUsed(), now))).append(" ago");
            if (p.lastDetail() != null && !p.lastDetail().isBlank()) sb.append("  (").append(p.lastDetail()).append(")");
            sb.append("\n");
        }
        var marks = map.recentMarks(8);
        if (!marks.isEmpty()) {
            sb.append("Marks:\n");
            for (var m : marks) {
                sb.append("  ").append(WHEN.format(m.at().atZone(ZoneId.systemDefault())))
                    .append("  ").append(m.text()).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }
}
