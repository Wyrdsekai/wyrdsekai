package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.issue.Issue;
import org.wyrdsekai.core.issue.IssueService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST surface for issue/feedback capture. Backs the
 * `wyrd issue` CLI and the phone clients; the in-world surfaces (SSH /
 * telnet / WS) call {@link IssueService} directly via the slash command.
 *
 *   POST /api/issues                 — file ({"kind","text","reporter","surface","companionDid","bondholderDid"})
 *   GET  /api/issues?status=all      — list (default: open only)
 *   GET  /api/issues/{id}            — one entry, full bundle (id or unique prefix)
 *   GET  /api/issues/{id}/export     — markdown bundle (text/markdown)
 *   POST /api/issues/{id}/close      — mark closed
 *
 * Local-only: these endpoints never transmit reports off the node.
 */
public final class IssueRoutes {

    private static final Logger log = LoggerFactory.getLogger(IssueRoutes.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_HEADER = "X-Wyrdsekai-Admin-Token";

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/issues", this::handleFile);
        app.get("/api/issues", this::handleList);
        app.get("/api/issues/{id}", this::handleGet);
        app.get("/api/issues/{id}/export", this::handleExport);
        app.post("/api/issues/{id}/close", this::handleClose);
    }

    /**
     * Steward-auth gate (matches RecipeAuthorRoutes / RecipeTuneRoutes):
     * loopback callers pass; anything else needs WYRDSEKAI_ADMIN_TOKEN.
     * Added 2026-07-31 — a `kind=issue` capture embeds the last ten
     * conversation turns verbatim, and these routes previously had NO auth,
     * so any LAN peer that could reach the HTTP port could read household
     * conversation via GET /api/issues/{id}/export. The CLI and in-session
     * surfaces all call over loopback, so nothing legitimate changes.
     */
    private static boolean authorised(Context ctx) {
        String expected = System.getenv("WYRDSEKAI_ADMIN_TOKEN");
        if (expected == null || expected.isBlank()) {
            String remote = ctx.ip();
            boolean local = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)
                || "::1".equals(remote);
            if (!local) {
                ctx.status(403).json(Map.of("error", "admin_token_required"));
                return false;
            }
            return true;
        }
        String got = ctx.header(ADMIN_HEADER);
        if (!expected.equals(got)) {
            ctx.status(403).json(Map.of("error", "invalid_admin_token"));
            return false;
        }
        return true;
    }

    private IssueService serviceOr503(Context ctx) {
        if (!authorised(ctx)) return null;
        var svc = IssueService.get();
        if (svc == null) {
            ctx.status(503).json(Map.of("error", "issue capture not initialized"));
        }
        return svc;
    }

    private void handleFile(Context ctx) {
        var svc = serviceOr503(ctx);
        if (svc == null) return;
        JsonNode body;
        try {
            body = MAPPER.readTree(ctx.body());
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid json body"));
            return;
        }
        var text = optText(body, "text");
        if (text == null || text.isBlank()) {
            ctx.status(400).json(Map.of("error", "text is required"));
            return;
        }
        var kind = Issue.KIND_FEEDBACK.equals(optText(body, "kind"))
            ? Issue.KIND_FEEDBACK : Issue.KIND_ISSUE;
        var issue = svc.file(kind, text,
            optText(body, "reporter"),
            optText(body, "surface") == null ? "rest" : optText(body, "surface"),
            optText(body, "companionDid"),
            optText(body, "bondholderDid"));
        ctx.status(201).json(Map.of("status", "filed", "issue", issue));
    }

    private void handleList(Context ctx) {
        var svc = serviceOr503(ctx);
        if (svc == null) return;
        boolean openOnly = !"all".equalsIgnoreCase(ctx.queryParam("status"));
        var list = svc.list(openOnly);
        // List view stays light: the heavy bundle fields are fetched per-id.
        var rows = list.stream().map(it -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", it.id());
            row.put("kind", it.kind());
            row.put("status", it.status());
            row.put("tsMs", it.tsMs());
            row.put("reporter", it.reporter());
            row.put("surface", it.surface());
            row.put("text", it.text());
            return row;
        }).toList();
        ctx.json(Map.of("count", rows.size(), "issues", rows));
    }

    private void handleGet(Context ctx) {
        var svc = serviceOr503(ctx);
        if (svc == null) return;
        svc.find(ctx.pathParam("id")).ifPresentOrElse(
            ctx::json,
            () -> ctx.status(404).json(Map.of("error", "no such issue")));
    }

    private void handleExport(Context ctx) {
        var svc = serviceOr503(ctx);
        if (svc == null) return;
        svc.exportMarkdown(ctx.pathParam("id")).ifPresentOrElse(
            md -> ctx.contentType("text/markdown").result(md),
            () -> ctx.status(404).json(Map.of("error", "no such issue")));
    }

    private void handleClose(Context ctx) {
        var svc = serviceOr503(ctx);
        if (svc == null) return;
        svc.close(ctx.pathParam("id")).ifPresentOrElse(
            it -> ctx.json(Map.of("status", "closed", "issue", it)),
            () -> ctx.status(404).json(Map.of("error", "no such issue")));
    }

    private static String optText(JsonNode body, String field) {
        var n = body.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
}
