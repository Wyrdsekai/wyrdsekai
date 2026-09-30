package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.lifecycle.RecoverySeedService;
import org.wyrdsekai.core.persistence.AuthService;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Recovery Seed — backs {@code wyrd seed}.
 *
 * <ul>
 *   <li>{@code POST /api/seed/generate} — {@code {"companion": name?, "passphrase": ...}};
 *       answers with the sealed file ({@code "file"}, base64) once. The node keeps only its
 *       own sealed copy.</li>
 *   <li>{@code POST /api/seed/verify} — {@code {"file": base64, "passphrase": ...}}; says whose
 *       seed it is and how old. Changes nothing.</li>
 *   <li>{@code POST /api/seed/restore} — same body; brings her back on this node.</li>
 * </ul>
 *
 * <p>Steward only ({@link StewardGate}: a steward session, or the operator token from this
 * machine). The passphrase and the file can act as her, so they are refused over a plain
 * connection from another machine, never logged, and every answer is {@code no-store}.
 */
public final class RecoverySeedRoutes {

    private static final int MAX_BODY = 96 * 1024 * 1024;

    private RecoverySeedRoutes() {}

    public static void register(JavalinConfig cfg, AuthService authService, RecoverySeedService seeds) {
        cfg.routes.post("/api/seed/generate", StewardGate.gated(authService, ctx -> {
            if (!privateChannel(ctx)) return;
            var body = body(ctx);
            if (body == null) return;
            var passphrase = text(body, "passphrase").toCharArray();
            try {
                var made = seeds.generate(text(body, "companion"), passphrase);
                var out = summary(made.summary());
                out.put("file", Base64.getEncoder().encodeToString(made.file()));
                out.put("localCopy", made.localCopy().toString());
                ctx.json(out);
            } catch (RecoverySeedService.Refused r) {
                refuse(ctx, r);
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }));
        cfg.routes.post("/api/seed/verify", StewardGate.gated(authService, ctx -> {
            if (!privateChannel(ctx)) return;
            var body = body(ctx);
            if (body == null) return;
            var file = file(ctx, body);
            if (file == null) return;
            var passphrase = text(body, "passphrase").toCharArray();
            try {
                ctx.json(summary(seeds.verify(file, passphrase)));
            } catch (RecoverySeedService.Refused r) {
                refuse(ctx, r);
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }));
        cfg.routes.post("/api/seed/restore", StewardGate.gated(authService, ctx -> {
            if (!privateChannel(ctx)) return;
            var body = body(ctx);
            if (body == null) return;
            var file = file(ctx, body);
            if (file == null) return;
            var passphrase = text(body, "passphrase").toCharArray();
            try {
                var out = summary(seeds.restore(file, passphrase));
                out.put("restored", true);
                ctx.json(out);
            } catch (RecoverySeedService.Refused r) {
                refuse(ctx, r);
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }));
    }

    /** This machine, or an encrypted connection: never a passphrase over plain LAN HTTP. */
    private static boolean privateChannel(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        if (StewardGate.isLoopback(ctx) || "https".equalsIgnoreCase(ctx.scheme())) return true;
        ctx.status(403).json(Map.of("error", "insecure_channel",
            "message", "The Recovery Seed is only handled on this machine or over an encrypted "
                + "connection."));
        return false;
    }

    /**
     * The body, read here rather than through {@code ctx.body()}: Javalin caps that at 1 MB, and a
     * seed carries a whole soul manifest. Bounded by the codec's own ciphertext cap in base64.
     */
    private static JsonNode body(Context ctx) {
        try {
            var raw = ctx.req().getInputStream().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) {
                ctx.status(413).json(Map.of("error", "too_large",
                    "message", "The request is larger than any Recovery Seed can be."));
                return null;
            }
            var node = Json.mapper().readTree(raw);
            if (node != null && node.isObject()) return node;
        } catch (Exception ignored) {
            // fall through: never echo the body back
        }
        ctx.status(400).json(Map.of("error", "bad_request", "message", "Expected a JSON object."));
        return null;
    }

    private static byte[] file(Context ctx, JsonNode body) {
        try {
            var bytes = Base64.getDecoder().decode(text(body, "file").strip());
            if (bytes.length > 0) return bytes;
        } catch (IllegalArgumentException ignored) {
            // not base64
        }
        ctx.status(400).json(Map.of("error", "not_a_seed",
            "message", "No Recovery Seed file was sent."));
        return null;
    }

    private static String text(JsonNode body, String field) {
        var v = body.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }

    private static Map<String, Object> summary(RecoverySeedService.Summary s) {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", s.name());
        out.put("entityId", s.entityId());
        out.put("did", s.did());
        out.put("createdAt", s.createdAt().toString());
        out.put("bonds", s.bonds());
        out.put("carriesKey", s.carriesKey());
        out.put("hereAlready", s.hereAlready());
        return out;
    }

    private static void refuse(Context ctx, RecoverySeedService.Refused r) {
        int status = switch (r.reason()) {
            case NO_SUCH_COMPANION -> 404;
            case WHICH_COMPANION, ALREADY_HERE, NAME_TAKEN -> 409;
            case NO_KEY_HERE -> 503;
            case PASSPHRASE_TOO_SHORT, WRONG_PASSPHRASE, NOT_A_SEED -> 400;
        };
        var out = new LinkedHashMap<String, Object>();
        out.put("error", r.reason().name().toLowerCase(Locale.ROOT));
        out.put("message", r.getMessage());
        if (!r.names().isEmpty()) out.put("names", r.names());
        ctx.status(status).json(out);
    }
}
