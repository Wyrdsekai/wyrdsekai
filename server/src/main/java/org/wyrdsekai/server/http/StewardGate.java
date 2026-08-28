package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.wyrdsekai.core.persistence.AuthService;

import java.util.Map;

/**
 * The single steward gate for operator routes.
 *
 * <p>Two ways in, and nothing else:
 * <ul>
 *   <li>a valid session whose role is {@code steward} — the remote/web path;</li>
 *   <li>this node's {@link OperatorToken}, presented from loopback — the local path,
 *       for the person who is already on the machine.</li>
 * </ul>
 *
 * <p>Exists as one class because it was previously copied per route, and a copied
 * authorisation check is a place for two rules to drift apart.
 */
public final class StewardGate {

    private StewardGate() {}

    @FunctionalInterface
    public interface GatedHandler {
        void handle(Context ctx) throws Exception;
    }

    public static Handler gated(AuthService authService, GatedHandler inner) {
        return ctx -> {
            var token = AuthRoutes.extractToken(ctx);
            if (token == null) {
                ctx.status(401).json(Map.of("error",
                    "Authorization required — log in with `wyrd login`, or run this on the "
                    + "node itself where the operator token is readable"));
                return;
            }
            if (OperatorToken.matches(token) && isLoopback(ctx)) {
                inner.handle(ctx);
                return;
            }
            var caller = authService.validateSession(token);
            if (caller.isEmpty()) {
                ctx.status(401).json(Map.of("error", "Invalid or expired session"));
                return;
            }
            if (!"steward".equals(caller.get().role())) {
                ctx.status(403).json(Map.of("error", "Steward role required"));
                return;
            }
            inner.handle(ctx);
        };
    }

    /** The operator token is only honoured from the machine itself. */
    static boolean isLoopback(Context ctx) {
        var remote = ctx.ip();
        return "127.0.0.1".equals(remote) || "::1".equals(remote)
            || "0:0:0:0:0:0:0:1".equals(remote);
    }
}
