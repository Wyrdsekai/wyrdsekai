package org.wyrdsekai.core.external.t;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * (Phase T) — runtime-side webhook receiver.
 *
 * <p>{@code world.inbound.webhook(path, hookName)} returns
 * {@code https://zone-host/api/webhook/{subscriptionId}}, and incoming HTTP
 * POSTs hit {@link #handle}. Per §4.34 the path is HMAC-signed; unsigned (or
 * mis-signed) requests are dropped without dispatching to the item.</p>
 *
 * <p>Two HMAC schemes are accepted out of the box:
 * <ul>
 *   <li>{@code X-Wyrdsekai-Signature: sha256=<hex>} — runtime-issued secret,
 *       signed body with the per-subscription secret. This is the default
 *       when an item calls {@code world.inbound.webhook} (no provider-specific
 *       header set).</li>
 *   <li>{@code X-Hub-Signature-256: sha256=<hex>} — GitHub's standard. Items
 *       that subscribe via {@code world.inbound.github_webhook} use this.</li>
 * </ul>
 *
 * <p>The HTTP route ({@code /api/webhook/{id}}) lives in the server module
 * ({@code WebhookRoutes}); this class is the transport-agnostic core. Tests
 * call {@link #handle} directly without spinning up a Javalin instance.</p>
 */
public final class WebhookListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookListener.class);

    /** Default signature header per §4.34 prose. */
    public static final String SIG_HEADER = "X-Wyrdsekai-Signature";
    /** GitHub-compatible header per §4.24+ provider-event prose. */
    public static final String GITHUB_SIG_HEADER = "X-Hub-Signature-256";

    private final InboundSubscriptionRegistry registry;
    private final InboundDispatchService dispatch;

    public WebhookListener(InboundSubscriptionRegistry registry,
                            InboundDispatchService dispatch) {
        this.registry = registry;
        this.dispatch = dispatch;
    }

    /** Subscribe — caller (provider) supplies item + agent ids. */
    public Map<String, Object> subscribe(String itemId, String agentId, String path,
                                           String hookName, Map<String, Object> opts) {
        if (path == null || path.isBlank()) path = "/";
        if (!path.startsWith("/")) path = "/" + path;
        var secret = generateSecret();
        Integer cap = null;
        if (opts != null && opts.get("capPerHour") instanceof Number n) {
            cap = n.intValue();
        }
        var combinedOpts = new LinkedHashMap<String, Object>();
        if (opts != null) combinedOpts.putAll(opts);
        combinedOpts.put("path", path);
        var id = registry.add(itemId, agentId, "webhook", hookName, path, combinedOpts, secret, cap);
        return Map.of(
            "ok", true,
            "subscriptionId", id,
            "url", "/api/webhook/" + id,
            "secret", secret
        );
    }

    /**
     * Handle an incoming HTTP POST. Validates HMAC, builds an
     * {@link InboundEvent}, and forwards to {@link InboundDispatchService}.
     *
     * @return outcome tagged with whether the request was authenticated +
     *         whether it was delivered (rate limit / paused / not found).
     */
    public Result handle(String subscriptionId, byte[] body, Map<String, String> headers) {
        var subOpt = registry.find(subscriptionId);
        if (subOpt.isEmpty() || !"webhook".equals(subOpt.get().kind())) {
            return Result.NOT_FOUND;
        }
        var sub = subOpt.get();
        if (!validateSignature(sub.secret(), body, headers)) {
            log.warn("webhook: invalid signature for subscription={} from path={}",
                subscriptionId, sub.target());
            return Result.UNAUTHORIZED;
        }
        var payload = buildPayload(body, headers);
        var event = InboundEvent.of("webhook", sub.target(), payload);
        var outcome = dispatch.dispatch(subscriptionId, event);
        return switch (outcome.decision()) {
            case DELIVER       -> Result.DELIVERED;
            case PAUSED        -> Result.PAUSED;
            case RATE_LIMITED  -> Result.RATE_LIMITED;
            case NOT_FOUND     -> Result.NOT_FOUND;
        };
    }

    /** Outcome of a single webhook delivery attempt. */
    public enum Result {
        DELIVERED, UNAUTHORIZED, NOT_FOUND, PAUSED, RATE_LIMITED
    }

    // ─── Internals ────────────────────────────────────────────

    private static String generateSecret() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Map<String, Object> buildPayload(byte[] body, Map<String, String> headers) {
        var bodyStr = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        Object parsed = bodyStr;
        if (!bodyStr.isEmpty() && (bodyStr.charAt(0) == '{' || bodyStr.charAt(0) == '[')) {
            try {
                parsed = new ObjectMapper().readValue(bodyStr, Object.class);
            } catch (Exception e) {
                // Fall back to raw string body
            }
        }
        var safeHeaders = headers == null ? Map.<String, String>of() : headers;
        return Map.of(
            "body", parsed,
            "rawBody", bodyStr,
            "headers", safeHeaders
        );
    }

    /**
     * Validate HMAC-SHA256 against the body. Accepts either
     * {@code X-Wyrdsekai-Signature: sha256=...} (default) or
     * {@code X-Hub-Signature-256: sha256=...} (GitHub).
     */
    static boolean validateSignature(String secret, byte[] body, Map<String, String> headers) {
        if (secret == null || secret.isBlank()) return false;
        if (headers == null) return false;
        var sig = headerCaseInsensitive(headers, SIG_HEADER);
        if (sig == null) sig = headerCaseInsensitive(headers, GITHUB_SIG_HEADER);
        if (sig == null) return false;
        if (sig.startsWith("sha256=")) sig = sig.substring("sha256=".length());
        var expected = computeHmac(secret, body == null ? new byte[0] : body);
        return constantTimeEquals(expected, sig);
    }

    private static String headerCaseInsensitive(Map<String, String> headers, String name) {
        var v = headers.get(name);
        if (v != null) return v;
        for (var e : headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    public static String computeHmac(String secret, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        var aa = a.getBytes(StandardCharsets.UTF_8);
        var bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aa, bb);
    }

    /** Look up the secret without exposing the registry to callers. */
    public Optional<String> secretFor(String subscriptionId) {
        return registry.find(subscriptionId).map(InboundSubscriptionRegistry.Subscription::secret);
    }
}
