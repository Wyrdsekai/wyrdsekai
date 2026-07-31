package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.naming.ZoneDirectory;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Zone directory REST routes.
 *
 * <p>Extracted from {@code Main.java} so the same registration path is
 * used in production and test. Parameterised by {@link Supplier}s so
 * the backing directory + local manifest can be swapped in tests
 * without spinning up the full server.</p>
 *
 * <p>Routes registered:</p>
 * <ul>
 *   <li>{@code GET /.well-known/wyrd-zone} — authoritative self-publish</li>
 *   <li>{@code GET /.well-known/webfinger} — RFC 7033 handle resolution</li>
 *   <li>{@code GET /api/directory/recent}</li>
 *   <li>{@code GET /api/directory/tag/{tag}}</li>
 *   <li>{@code GET /api/directory/capability/{capability}}</li>
 *   <li>{@code GET /api/directory/known-manifests} — federated pull endpoint</li>
 *   <li>{@code GET /api/directory/{did}} — placed last so it doesn't shadow the
 *       sub-paths above (Javalin matches most-specific first but we keep the
 *       order deliberate for readability)</li>
 * </ul>
 */
public final class DirectoryRoutes {

    private static final Logger log = LoggerFactory.getLogger(DirectoryRoutes.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Supplier<ZoneDirectory> directorySupplier;
    private final Supplier<ZoneManifestV1> localManifestSupplier;

    public DirectoryRoutes(Supplier<ZoneDirectory> directorySupplier,
                            Supplier<ZoneManifestV1> localManifestSupplier) {
        this.directorySupplier = directorySupplier;
        this.localManifestSupplier = localManifestSupplier;
    }

    /** Register all directory + .well-known routes on the given Javalin config. */
    public void register(JavalinConfig cfg) {
        cfg.routes.get("/.well-known/wyrd-zone", this::serveLocalManifest);
        cfg.routes.get("/.well-known/webfinger", this::serveWebFinger);

        cfg.routes.get("/api/directory/recent", this::serveRecent);
        cfg.routes.get("/api/directory/tag/{tag}", this::serveTag);
        cfg.routes.get("/api/directory/capability/{capability}", this::serveCapability);
        cfg.routes.get("/api/directory/known-manifests", this::serveKnownManifests);
        cfg.routes.get("/api/directory/{did}", this::serveLookup);
    }

    // ── handlers ──────────────────────────────────────────────────────

    private void serveLocalManifest(Context ctx) {
        var m = localManifestSupplier.get();
        if (m == null) {
            ctx.status(404).json(Map.of(
                "error", "no manifest for this zone — is the local registry set up? "
                    + "Run `wyrd zones create <label>`."));
            return;
        }
        ctx.contentType("application/json");
        ctx.result(m.toJsonBytes());
    }

    private void serveWebFinger(Context ctx) {
        var m = localManifestSupplier.get();
        var resource = ctx.queryParam("resource");
        if (resource == null || !resource.startsWith("acct:")) {
            ctx.status(400).json(Map.of(
                "error", "resource query param required, must start with acct:"));
            return;
        }
        if (m == null) {
            ctx.status(404).json(Map.of("error", "no manifest for this zone"));
            return;
        }
        var at = resource.indexOf('@');
        if (at < 0) {
            ctx.status(400).json(Map.of("error", "malformed acct resource"));
            return;
        }
        var requestedLabel = resource.substring("acct:".length(), at);
        if (!requestedLabel.equalsIgnoreCase(m.zoneLabel())) {
            ctx.status(404).json(Map.of(
                "error", "no zone matching " + requestedLabel + " on this host"));
            return;
        }
        var scheme = ctx.scheme() == null ? "https" : ctx.scheme();
        var host = ctx.host() != null ? ctx.host() : resource.substring(at + 1);
        var selfUrl = scheme + "://" + host + "/.well-known/wyrd-zone";
        // Per RFC 7033 the response uses media type application/jrd+json.
        // Use result() + explicit content-type so Javalin's ctx.json()
        // doesn't override with application/json.
        try {
            var payload = MAPPER.writeValueAsString(Map.of(
                "subject", resource,
                "links", List.of(Map.of(
                    "rel", "self",
                    "type", "application/json",
                    "href", selfUrl))));
            ctx.result(payload);
            ctx.contentType("application/jrd+json");
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "serialization failed"));
        }
    }

    private void serveRecent(Context ctx) {
        var dir = directorySupplier.get();
        if (dir == null) {
            ctx.status(503).json(Map.of("error", "directory not initialised"));
            return;
        }
        int limit = clampLimit(ctx.queryParam("limit"), 20, 100);
        var manifests = dir.recent(limit).stream()
            .map(m -> MAPPER.convertValue(m, Map.class))
            .toList();
        ctx.json(Map.of("count", manifests.size(), "manifests", manifests));
    }

    private void serveTag(Context ctx) {
        var dir = directorySupplier.get();
        if (dir == null) {
            ctx.status(503).json(Map.of("error", "directory not initialised"));
            return;
        }
        var tag = ctx.pathParam("tag");
        var dids = dir.discoverByTag(tag);
        ctx.json(Map.of("tag", tag, "count", dids.size(), "dids", dids));
    }

    private void serveCapability(Context ctx) {
        var dir = directorySupplier.get();
        if (dir == null) {
            ctx.status(503).json(Map.of("error", "directory not initialised"));
            return;
        }
        var cap = ctx.pathParam("capability");
        var dids = dir.discoverByCapability(cap);
        ctx.json(Map.of("capability", cap, "count", dids.size(), "dids", dids));
    }

    private void serveKnownManifests(Context ctx) {
        var dir = directorySupplier.get();
        if (dir == null) {
            ctx.status(503).json(Map.of("error", "directory not initialised"));
            return;
        }
        int hops;
        try { hops = Integer.parseInt(ctx.queryParam("hops") == null ? "1" : ctx.queryParam("hops")); }
        catch (NumberFormatException e) { hops = 1; }
        int limit = clampLimit(ctx.queryParam("limit"), 100, 500);
        var manifests = dir.recent(limit).stream()
            .map(m -> MAPPER.convertValue(m, Map.class))
            .toList();
        ctx.json(Map.of(
            "hops", hops,
            "count", manifests.size(),
            "manifests", manifests));
    }

    private void serveLookup(Context ctx) {
        var dir = directorySupplier.get();
        if (dir == null) {
            ctx.status(503).json(Map.of("error", "directory not initialised"));
            return;
        }
        var opt = dir.lookup(ctx.pathParam("did"));
        if (opt.isEmpty()) {
            ctx.status(404).json(Map.of(
                "error", "no manifest published for " + ctx.pathParam("did")));
            return;
        }
        ctx.json(opt.get());
    }

    private static int clampLimit(String raw, int defaultValue, int max) {
        if (raw == null) return defaultValue;
        try {
            return Math.max(1, Math.min(max, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
