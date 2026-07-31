package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import org.wyrdsekai.common.util.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * OpenRouter OAuth PKCE helper. Mirrors the KMP {@code OpenRouterOAuth.kt}.
 *
 * <p>OpenRouter only accepts {@code https://*:443}, {@code https://*:3000},
 * or {@code http://localhost:3000} as callback URLs — no custom URI schemes.
 * The server-side flow registers an HTTPS callback on the steward's public
 * relay; the phone flows use {@code http://localhost:3000} with a WebView
 * URL-intercept (the redirect never actually leaves the device).
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #beginFlow(String)} — generate PKCE state + return auth URL</li>
 *   <li>User authorizes in browser → OR redirects to callback URL with
 *       {@code ?code=AUTH_CODE}</li>
 *   <li>{@link #exchangeCode(String, String)} — POST /api/v1/auth/keys to
 *       exchange the code for a user-controlled API key</li>
 * </ol>
 */
public final class OpenRouterOAuth {

    public static final String AUTH_URL = "https://openrouter.ai/auth";
    public static final String EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys";

    private static final SecureRandom RNG = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    /**
     * PKCE state for one in-flight OAuth attempt. Keyed by {@code state}
     * (random token round-tripped through OR) so concurrent flows don't
     * collide.
     */
    public record PkceState(String state, String codeVerifier, String codeChallenge) {}

    /** Result of code → API-key exchange. */
    public record OAuthResult(String apiKey, String error) {
        public boolean ok() { return apiKey != null && !apiKey.isEmpty(); }
    }

    // In-memory state map. Entries expire after 10 minutes (auto-cleaned on
    // each beginFlow). For multi-process servers we'd want Redis, but a
    // single-node Wyrdsekai zone is fine with this.
    private final ConcurrentMap<String, StateEntry> pending = new ConcurrentHashMap<>();
    private record StateEntry(PkceState state, long expiresAtMs) {}

    /**
     * Start a new OAuth flow. Returns the URL to send the user to, plus the
     * PKCE state token used to look up this attempt at callback time.
     *
     * @param callbackUrl Where OR redirects after auth (must be HTTPS:443/3000
     *                    or http://localhost:3000).
     */
    public PkceState beginFlow(String callbackUrl) {
        gc();
        var state = randomToken();
        var verifier = randomToken() + randomToken(); // 128 chars, well within RFC 7636 43-128 range
        var challenge = s256Challenge(verifier);
        var pkce = new PkceState(state, verifier, challenge);
        pending.put(state, new StateEntry(pkce, System.currentTimeMillis() + 10 * 60 * 1000));
        return pkce;
    }

    /** Build the authorization URL for a started flow. */
    public String buildAuthUrl(PkceState pkce, String callbackUrl) {
        var encodedCb = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);
        return AUTH_URL
            + "?callback_url=" + encodedCb
            + "&code_challenge=" + pkce.codeChallenge
            + "&code_challenge_method=S256"
            + "&state=" + pkce.state;
    }

    /**
     * Look up the in-flight PKCE state by its state token. Returns null if
     * unknown or expired.
     */
    public PkceState consume(String state) {
        var entry = pending.remove(state);
        if (entry == null || entry.expiresAtMs < System.currentTimeMillis()) return null;
        return entry.state;
    }

    /**
     * Exchange the authorization code for a user-controlled API key.
     *
     * @param code The {@code ?code=...} query param from OR's redirect.
     * @param codeVerifier The PKCE verifier from the matching {@link PkceState}.
     */
    public OAuthResult exchangeCode(String code, String codeVerifier) {
        var body = String.format(
            "{\"code\":\"%s\",\"code_verifier\":\"%s\",\"code_challenge_method\":\"S256\"}",
            jsonEscape(code), jsonEscape(codeVerifier));
        try {
            var req = HttpRequest.newBuilder(URI.create(EXCHANGE_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new OAuthResult(null,
                    "exchange failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            JsonNode node = Json.mapper().readTree(resp.body());
            var key = node.path("key").asText(null);
            if (key == null || key.isEmpty()) {
                return new OAuthResult(null, "exchange returned no key: " + resp.body());
            }
            return new OAuthResult(key, null);
        } catch (Exception e) {
            return new OAuthResult(null, "exchange error: " + e.getMessage());
        }
    }

    // ── PKCE helpers ─────────────────────────────────────────────────

    /** Cryptographically random 32-byte base64url string. */
    private static String randomToken() {
        var bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** S256 challenge = base64url(sha256(verifier)). */
    static String s256Challenge(String verifier) {
        try {
            var hash = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void gc() {
        var now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAtMs < now);
    }
}
