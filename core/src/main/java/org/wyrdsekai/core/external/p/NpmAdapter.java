package org.wyrdsekai.core.external.p;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * npm registry / api adapter.
 *
 * <p>Methods (Phase P scope): {@code search}, {@code info},
 * {@code downloads}.</p>
 *
 * <p>All Phase P methods are public reads — no token required. The
 * adapter still advertises the {@code npm.token} slot for the future
 * publish surface; reads do not block on credential presence.</p>
 */
public final class NpmAdapter extends BaseHttpAdapter {

    private static final String REGISTRY_BASE = "https://registry.npmjs.org";
    private static final String API_BASE = "https://api.npmjs.org";

    private volatile String apiBaseOverride;

    @Override public String namespace() { return "npm"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "info", "downloads");
    }

    @Override public String credentialSlot() { return "npm.token"; }

    @Override protected String defaultBaseUrl() { return REGISTRY_BASE; }

    /** Test-only — redirect downloads endpoint (api.npmjs.org) to a mock. */
    public void setApiBaseOverride(String url) { this.apiBaseOverride = url; }

    private String apiBase() {
        var override = apiBaseOverride;
        return override != null && !override.isBlank() ? override : API_BASE;
    }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var args = req.args();
        return switch (req.method()) {
            case "search" -> search(args);
            case "info" -> info(args);
            case "downloads" -> downloads(args);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse search(Map<String, Object> args) {
        var q = requireString(args, "query");
        if (q == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/-/v1/search?text=" + urlEncode(q)))
            .GET();
        return execute(rb, tree -> {
            var objects = tree.path("objects");
            var out = new ArrayList<Map<String, Object>>();
            for (var o : objects) {
                var pkg = o.path("package");
                var e = new LinkedHashMap<String, Object>();
                e.put("name", pkg.path("name").asText());
                e.put("version", pkg.path("version").asText());
                e.put("description", pkg.path("description").asText());
                out.add(e);
            }
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse info(Map<String, Object> args) {
        var pkg = requireString(args, "packageName");
        if (pkg == null) return AdapterResponse.fail("missing_arg", "packageName required", false);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/" + urlEncode(pkg)))
            .GET();
        return execute(rb, tree -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("name", tree.path("name").asText());
            out.put("description", tree.path("description").asText());
            out.put("latest", tree.path("dist-tags").path("latest").asText());
            out.put("homepage", tree.path("homepage").asText());
            out.put("license", tree.path("license").asText());
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse downloads(Map<String, Object> args) {
        var pkg = requireString(args, "packageName");
        if (pkg == null) return AdapterResponse.fail("missing_arg", "packageName required", false);
        var period = optString(args, "period", "last-week");
        var rb = HttpRequest.newBuilder()
            .uri(URI.create(apiBase() + "/downloads/point/"
                + urlEncode(period) + "/" + urlEncode(pkg)))
            .GET();
        return execute(rb, tree -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("package", tree.path("package").asText(pkg));
            out.put("downloads", tree.path("downloads").asLong());
            out.put("start", tree.path("start").asText());
            out.put("end", tree.path("end").asText());
            return AdapterResponse.ok(out);
        });
    }

}
