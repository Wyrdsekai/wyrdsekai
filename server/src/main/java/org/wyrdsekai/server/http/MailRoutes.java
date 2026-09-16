package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.persistence.AuthService;

import java.util.List;
import java.util.Map;

/**
 * The steward's view of household mail: who wrote to whom, when, read or not, how long.
 *
 * <p>Never a subject and never a body. A steward keeps the household, not its
 * correspondence — the rule the household asked for (2026-09-15), enforced here by reading
 * the header columns only, not by trimming a full row on the way out.</p>
 */
public final class MailRoutes {

    private final AuthService auth;

    public MailRoutes(AuthService auth) {
        this.auth = auth;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/mail/headers", this::handleHeaders);
    }

    record ErrorResponse(String error) {}

    private void handleHeaders(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Authentication required"));
            return;
        }
        var user = auth.validateSession(token);
        if (user.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return;
        }
        if (!"steward".equals(user.get().role())) {
            ctx.status(403).json(new ErrorResponse("Only the steward sees the household's mail log"));
            return;
        }
        int limit = 100;
        var raw = ctx.queryParam("limit");
        if (raw != null && !raw.isBlank()) {
            try {
                limit = Math.clamp(Integer.parseInt(raw.trim()), 1, 1000);
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        var service = MailboxService.get();
        ctx.json(Map.of("headers", service == null ? List.of() : service.headers(limit)));
    }
}
