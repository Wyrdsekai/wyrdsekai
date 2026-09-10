package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.library.LibraryProvider;
import org.wyrdsekai.core.library.LibraryReaders;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The household's library served as JSON routes (LIBRARY_PROTOCOL.md §6, the HTTP form):
 * {@code POST /v1/{ask|search|get|read|established|submit|subjects|status}} and
 * {@code GET /v1/status} — the door a peer librarian speaks (contract 1.5 peers ask over
 * {@code /v1/ask} with a bearer token, one hop). The same {@link LibraryProvider} that
 * answers the MCP door at {@code /mcp/library}; the license gate on what may leave lives
 * there, not here.
 *
 * <p>Identity: a bearer token that {@link LibraryReaders} knows names the patron; a body
 * that names a did without a token is refused rather than downgraded, as the reference
 * librarian does. Anonymous callers read what travels. {@code submit} needs a writer.
 */
public final class LibraryDoorRoutes {

    private static final Logger log = LoggerFactory.getLogger(LibraryDoorRoutes.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final LibraryProvider provider;
    private final LibraryReaders readers;

    public LibraryDoorRoutes(LibraryProvider provider, LibraryReaders readers) {
        this.provider = provider;
        this.readers = readers;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/v1/status", ctx -> answer(ctx, "library_status", Map.of()));
        for (var tool : LibraryProvider.TOOLS) {
            var route = tool.substring("library_".length());
            app.post("/v1/" + route, ctx -> {
                Map<String, Object> body = Map.of();
                var text = ctx.body();
                if (text != null && !text.isBlank()) {
                    try {
                        @SuppressWarnings("unchecked") Map<String, Object> parsed = M.readValue(text, Map.class);
                        body = parsed;
                    } catch (Exception e) {
                        error(ctx, "invalid_args", "The request body is not JSON.");
                        return;
                    }
                }
                answer(ctx, tool, body);
            });
        }
        log.info("Library door served as HTTP at /v1/ask|search|get|read|established|submit|subjects|status");
    }

    private void answer(Context ctx, String tool, Map<String, Object> body) {
        try {
            var args = new LinkedHashMap<String, Object>(body);
            var token = LibraryReaders.bearer(ctx.header("Authorization"));
            var reader = readers == null ? java.util.Optional.<LibraryReaders.Reader>empty() : readers.resolve(token);
            if (token != null && reader.isEmpty()) {
                error(ctx, "forbidden", "That bearer token proves no listed reader; the person who keeps this library adds one with `wyrd library reader add`.");
                return;
            }
            var claimed = args.get("patron") instanceof Map<?, ?> p ? p : null;
            var runtime = claimed != null && claimed.get("runtime") != null ? String.valueOf(claimed.get("runtime")) : "http";
            if (reader.isPresent()) {
                var r = reader.get();
                if (claimed != null && claimed.get("did") != null && !String.valueOf(claimed.get("did")).isBlank()
                        && !r.did().isBlank() && !r.did().equals(String.valueOf(claimed.get("did")))) {
                    error(ctx, "forbidden", "The token proves " + r.did() + ", not the did the request names.");
                    return;
                }
                var patron = new LinkedHashMap<String, Object>();
                if (!r.did().isBlank()) patron.put("did", r.did());
                patron.put("name", r.name());
                patron.put("runtime", runtime);
                args.put("patron", patron);
                if ("library_submit".equals(tool) && r.level() != LibraryReaders.Level.write) {
                    error(ctx, "forbidden", "Reader '" + r.name() + "' may read this library, not write to it.");
                    return;
                }
            } else {
                if (claimed != null && claimed.get("did") != null && !String.valueOf(claimed.get("did")).isBlank()) {
                    error(ctx, "forbidden", "A did over http must be proved with a bearer token (Authorization: Bearer …); omit the patron to call anonymously.");
                    return;
                }
                if ("library_submit".equals(tool)) {
                    error(ctx, "forbidden", "Submitting needs a reader with write access.");
                    return;
                }
                args.put("patron", Map.of("runtime", runtime));
            }
            ctx.json(provider.call(tool, args));
        } catch (LibraryProvider.ProtocolError e) {
            error(ctx, e.code, e.getMessage());
        } catch (Exception e) {
            log.warn("library door {} failed: {}", tool, e.toString());
            error(ctx, "unavailable", "The library could not answer just now.");
        }
    }

    static int status(String code) {
        return switch (code) {
            case "not_found" -> 404;
            case "forbidden" -> 403;
            case "no_sources" -> 422;
            case "invalid_args" -> 400;
            default -> 503;
        };
    }

    private static void error(Context ctx, String code, String message) {
        ctx.status(status(code)).json(Map.of("error", Map.of("code", code, "message", message)));
    }
}
