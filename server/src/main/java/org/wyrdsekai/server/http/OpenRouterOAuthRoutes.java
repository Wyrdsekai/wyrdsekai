package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.inference.OpenRouterOAuth;
import org.wyrdsekai.core.inference.StaticApiKeyProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Server-side OpenRouter OAuth PKCE endpoints.
 *
 * <p>Flow from a steward's terminal:
 * <ol>
 *   <li>{@code GET /api/oauth/openrouter/start} returns the auth URL.
 *       The CLI ({@code wyrd inference openrouter-connect}) prints it; the
 *       steward opens it in their browser.</li>
 *   <li>User authorizes OpenRouter access.</li>
 *   <li>OR redirects the browser to {@code https://<this-server>/api/oauth/openrouter/callback?code=...&state=...}.</li>
 *   <li>{@link #handleCallback(Context)} exchanges the code, writes the API
 *       key to {@code $DATA_DIR/openrouter.key} (mode 0600), and returns a
 *       small HTML page telling the steward to restart wyrdsekai.</li>
 *   <li>The CLI polls {@code GET /api/oauth/openrouter/status} to detect
 *       completion and confirm to the operator.</li>
 * </ol>
 *
 * <p>The key is BOTH persisted to disk AND, when an {@link StaticApiKeyProvider}
 * is supplied, hot-installed into the running router so subsequent inference
 * calls pick it up without a restart. The persisted copy at {@code openrouter.key}
 * is what survives restarts — the steward still has to wire it into
 * {@code /etc/wyrdsekai/wyrdsekai.conf} (or let {@code wyrd inference
 * openrouter-connect --finish} do that) for durability.
 */
public final class OpenRouterOAuthRoutes {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterOAuthRoutes.class);

    /** Where the exchanged API key gets written. */
    public static final String KEY_FILE_NAME = "openrouter.key";

    /** Backend name used in {@code WYRDSEKAI_API_KEY_OPENROUTER} and provider lookups. */
    private static final String BACKEND_NAME = "openrouter";

    private final OpenRouterOAuth oauth = new OpenRouterOAuth();
    private final String publicCallbackUrl;
    private final StaticApiKeyProvider apiKeyProvider;  // nullable — hot-reload optional

    /**
     * @param publicCallbackUrl Where OpenRouter redirects back. Must be one of
     *   {@code https://*:443} / {@code https://*:3000} / {@code http://localhost:3000}.
     *   Typically {@code https://<your-public-host>/api/oauth/openrouter/callback}.
     *   If null, the start endpoint returns an error explaining the constraint.
     * @param apiKeyProvider Provider to receive the freshly-exchanged key for
     *   immediate use by the running router. Null disables hot-reload (key is
     *   still persisted; steward must restart).
     */
    public OpenRouterOAuthRoutes(String publicCallbackUrl, StaticApiKeyProvider apiKeyProvider) {
        this.publicCallbackUrl = publicCallbackUrl;
        this.apiKeyProvider = apiKeyProvider;
    }

    /** Back-compat constructor — no hot-reload provider. */
    public OpenRouterOAuthRoutes(String publicCallbackUrl) {
        this(publicCallbackUrl, null);
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/oauth/openrouter/start", this::handleStart);
        app.get("/api/oauth/openrouter/callback", this::handleCallback);
        app.get("/api/oauth/openrouter/status", this::handleStatus);
    }

    private void handleStart(Context ctx) {
        if (publicCallbackUrl == null || publicCallbackUrl.isBlank()) {
            ctx.status(400).json(Map.of(
                "error", "no_callback_url",
                "hint", "Set WYRDSEKAI_PUBLIC_HOST so the server knows its own HTTPS URL"));
            return;
        }
        var pkce = oauth.beginFlow(publicCallbackUrl);
        ctx.json(Map.of(
            "auth_url", oauth.buildAuthUrl(pkce, publicCallbackUrl),
            "state", pkce.state(),
            "callback_url", publicCallbackUrl));
    }

    private void handleCallback(Context ctx) {
        var code = ctx.queryParam("code");
        var state = ctx.queryParam("state");
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            ctx.status(400).html(htmlMessage(
                "Missing parameters",
                "OpenRouter's redirect should include both <code>code</code> and <code>state</code>."));
            return;
        }
        var pkce = oauth.consume(state);
        if (pkce == null) {
            ctx.status(400).html(htmlMessage(
                "Unknown or expired state",
                "Start the flow again with <code>wyrd inference openrouter-connect</code>."));
            return;
        }
        var result = oauth.exchangeCode(code, pkce.codeVerifier());
        if (!result.ok()) {
            log.warn("OpenRouter exchange failed: {}", result.error());
            ctx.status(502).html(htmlMessage(
                "Exchange failed",
                result.error() != null ? escape(result.error()) : "Unknown error talking to OpenRouter."));
            return;
        }
        // Persist key. Mode 0600 on POSIX.
        var keyFile = SystemPaths.dataDir().resolve(KEY_FILE_NAME);
        try {
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, result.apiKey());
            trySetReadablePermissions(keyFile);
            log.info("OpenRouter key persisted to {} (length {})",
                keyFile, result.apiKey().length());
        } catch (Exception e) {
            log.error("Failed to write OpenRouter key", e);
            ctx.status(500).html(htmlMessage(
                "Could not save key",
                "Got the key from OpenRouter but failed to write " + escape(keyFile.toString())
                    + ": " + escape(e.getMessage())));
            return;
        }
        // Hot-install into the running router so the next inference call uses
        // the key — no restart needed for the current process. The persisted
        // file is what survives a restart, so the steward still needs to wire
        // it into wyrdsekai.conf for durability (the message below explains).
        boolean hotInstalled = false;
        if (apiKeyProvider != null) {
            try {
                apiKeyProvider.setKey(BACKEND_NAME, result.apiKey());
                hotInstalled = true;
                log.info("OpenRouter key hot-installed into running router");
            } catch (Exception e) {
                log.warn("Hot-install of OpenRouter key failed (persisted copy is still valid): {}",
                    e.getMessage());
            }
        }
        var hotMsg = hotInstalled
            ? "The running server now has the key in memory — no restart needed for this session.<br><br>"
                + "For the key to survive a restart, "
            : "";
        ctx.html(htmlMessage(
            "OpenRouter connected",
            "Key saved to <code>" + escape(keyFile.toString()) + "</code>.<br><br>"
                + hotMsg
                + "add this line to <code>/etc/wyrdsekai/wyrdsekai.conf</code>:<br>"
                + "<pre>WYRDSEKAI_API_KEY_OPENROUTER=$(cat " + escape(keyFile.toString()) + ")</pre>"
                + "Or run <code>wyrd inference openrouter-connect --finish</code> "
                + "which will do this for you."));
    }

    private void handleStatus(Context ctx) {
        var keyFile = SystemPaths.dataDir().resolve(KEY_FILE_NAME);
        var exists = Files.exists(keyFile);
        long mtime = 0L;
        if (exists) {
            try {
                mtime = Files.getLastModifiedTime(keyFile).toMillis();
            } catch (Exception ignored) {}
        }
        ctx.json(Map.of(
            "has_key", exists,
            "key_file", keyFile.toString(),
            "key_mtime_ms", mtime));
    }

    private static void trySetReadablePermissions(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows): rely on directory ACLs.
        }
    }

    private static String htmlMessage(String title, String bodyHtml) {
        return "<!doctype html><html><head><meta charset='utf-8'><title>" + escape(title) + "</title>"
            + "<style>body{font-family:system-ui,sans-serif;max-width:42em;margin:4em auto;padding:0 1em;line-height:1.5}"
            + "pre{background:#f4f4f4;padding:.6em;overflow:auto;border-radius:4px}"
            + "code{background:#f4f4f4;padding:.1em .3em;border-radius:3px}</style></head><body>"
            + "<h1>" + escape(title) + "</h1><p>" + bodyHtml + "</p></body></html>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
