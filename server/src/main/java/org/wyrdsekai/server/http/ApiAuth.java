package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.server.TestModeConfig;

import java.net.InetAddress;
import java.util.Map;

/**
 * The login check in front of every {@code /api} route and {@code /metrics}.
 *
 * <p>Until 2026-09-28 each route checked on its own, and the security audit found more
 * than forty that did not: the household key, the pairing code, every Study, private
 * journals, the companion's memory search, federation, MCP vouching. Now one filter
 * resolves who is calling before any handler runs, and {@link ApiPolicy} says what each
 * route needs. A route missing from the policy needs the steward.
 *
 * <p>Who can call:
 * <ul>
 *   <li>a person: a session token, or a paired device's token that is linked to a person;</li>
 *   <li>a paired device that is linked to nobody (household-key pairing);</li>
 *   <li>the machine's operator: this node's {@link OperatorToken} from loopback, or the
 *       {@code WYRDSEKAI_ADMIN_TOKEN} header;</li>
 *   <li>the resident bridge: the configured resident token, on {@code /api/resident/*} only.</li>
 * </ul>
 */
public final class ApiAuth {

    private static final Logger log = LoggerFactory.getLogger(ApiAuth.class);

    public enum Level { PUBLIC, LOGIN, STEWARD, OPERATOR, RESIDENT }

    public sealed interface Principal permits Operator, Person, Device, Resident {}
    public record Operator(String via) implements Principal {}
    public record Person(AuthService.User user, String via) implements Principal {}
    public record Device(PairingService.PairedDevice device) implements Principal {}
    public record Resident() implements Principal {}

    static final String ATTR = "wyrd.principal";
    static final String ADMIN_HEADER = "X-Wyrdsekai-Admin-Token";

    private ApiAuth() {}

    public static Handler filter(AuthService auth, PairingService pairing, String residentToken) {
        return ctx -> {
            var endpoint = ctx.endpoints().matchedHttpEndpoint();
            var path = endpoint != null ? endpoint.path : ctx.path();
            if (!path.startsWith("/api/") && !path.equals("/metrics")) return;
            if (ctx.method() == HandlerType.OPTIONS) return;
            var level = ApiPolicy.levelFor(ctx.method().name(), path);
            var principal = resolve(ctx, auth, pairing, residentToken, path);
            if (principal != null) ctx.attribute(ATTR, principal);
            if (level == Level.PUBLIC) return;
            if (principal == null) {
                refuse(ctx, 401, "login_required",
                    "Log in first. On the home machine itself, run the command with sudo so it can "
                        + "read the operator token.");
                return;
            }
            boolean allowed = switch (level) {
                case LOGIN -> !(principal instanceof Resident);
                case STEWARD -> isSteward(principal);
                case OPERATOR -> principal instanceof Operator;
                case RESIDENT -> principal instanceof Resident || isSteward(principal);
                case PUBLIC -> true;
            };
            if (!allowed) {
                refuse(ctx, 403, "forbidden", switch (level) {
                    case OPERATOR -> "Only the home machine's operator can do this, from the machine itself.";
                    case STEWARD, RESIDENT -> "Only the steward can do this.";
                    default -> "Not allowed.";
                });
            }
        };
    }

    static Principal resolve(Context ctx, AuthService auth, PairingService pairing,
                             String residentToken, String path) {
        var admin = System.getenv("WYRDSEKAI_ADMIN_TOKEN");
        var adminHeader = ctx.header(ADMIN_HEADER);
        if (admin != null && !admin.isBlank() && admin.equals(adminHeader)) return new Operator("admin-token");

        var token = AuthRoutes.extractToken(ctx);
        if (token != null && !token.isBlank()) {
            if (OperatorToken.matches(token)) {
                return isLoopback(ctx) ? new Operator("operator-token") : null;
            }
            if (residentToken != null && !residentToken.isBlank() && path.startsWith("/api/resident/")
                    && residentToken.equals(token)) {
                return new Resident();
            }
            if (auth != null) {
                var user = auth.validateSession(token);
                if (user.isPresent()) return new Person(user.get(), "session");
            }
            if (pairing != null) {
                var device = pairing.validateDeviceToken(token);
                if (device.isPresent()) {
                    var d = device.get();
                    if (d.userId() != null && auth != null) {
                        var user = auth.findUser(d.userId());
                        if (user.isPresent()) return new Person(user.get(), "device");
                    }
                    return new Device(d);
                }
            }
            return null;
        }
        // The end-to-end suites drive the node from loopback without a login; only in test mode.
        if (TestModeConfig.isTestMode() && isLoopback(ctx)) return new Operator("test-mode");
        return null;
    }

    private static void refuse(Context ctx, int status, String error, String message) {
        ctx.status(status).json(Map.of("error", error, "message", message));
        ctx.skipRemainingHandlers();
    }

    public static Principal principal(Context ctx) {
        return ctx.attribute(ATTR);
    }

    public static boolean isOperator(Context ctx) {
        return principal(ctx) instanceof Operator;
    }

    public static boolean isSteward(Context ctx) {
        return isSteward(principal(ctx));
    }

    static boolean isSteward(Principal p) {
        return p instanceof Operator
            || (p instanceof Person person && "steward".equals(person.user().role()));
    }

    /** The person this request acts for, or null (and a 401 written) when there is none. */
    public static AuthService.User requirePerson(Context ctx) {
        if (principal(ctx) instanceof Person person) return person.user();
        ctx.status(401).json(Map.of("error", "person_required",
            "message", "This needs a person's login, not a device or the machine's operator token."));
        return null;
    }

    /** Steward or operator, else a 401/403 is written and false returned. */
    public static boolean requireSteward(Context ctx) {
        var p = principal(ctx);
        if (p == null) {
            ctx.status(401).json(Map.of("error", "login_required", "message", "Log in first."));
            return false;
        }
        if (!isSteward(p)) {
            ctx.status(403).json(Map.of("error", "forbidden", "message", "Only the steward can do this."));
            return false;
        }
        return true;
    }

    /** The remote address itself, never a forwarded header. */
    static boolean isLoopback(Context ctx) {
        try {
            var remote = ctx.req().getRemoteAddr();
            if (remote == null) return false;
            return InetAddress.ofLiteral(remote).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            log.debug("Not an address literal: {}", e.getMessage());
            return false;
        }
    }
}
