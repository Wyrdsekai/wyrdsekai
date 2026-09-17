package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.wyrdsekai.core.body.BodyMap;
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
        ctx.json(Map.of("parts", parts, "marks", marks, "host", host));
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
