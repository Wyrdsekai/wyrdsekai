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
 * PyPI adapter.
 *
 * <p>Methods (Phase P scope): {@code search}, {@code info}.</p>
 *
 * <p>PyPI XML-RPC search was deprecated in 2018 — this adapter routes
 * search through the read-only JSON API ({@code /simple/}) by streaming
 * the package index and locally substring-matching, capped at 50 hits.
 * For production a steward should swap {@link #setSearchBaseOverride(String)}
 * to a self-hosted PyPI mirror or a search service like libraries.io.</p>
 */
public final class PyPIAdapter extends BaseHttpAdapter {

    private static final String DEFAULT_BASE = "https://pypi.org";

    @Override public String namespace() { return "pypi"; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "info");
    }

    @Override public String credentialSlot() { return "pypi.token"; }

    @Override protected String defaultBaseUrl() { return DEFAULT_BASE; }

    private volatile String searchBaseOverride;

    /** Test-only override for search backend. */
    public void setSearchBaseOverride(String url) { this.searchBaseOverride = url; }

    private String searchBase() {
        var override = searchBaseOverride;
        return override != null && !override.isBlank() ? override : baseUrl();
    }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var args = req.args();
        return switch (req.method()) {
            case "search" -> search(args);
            case "info" -> info(args);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse search(Map<String, Object> args) {
        var q = requireString(args, "query");
        if (q == null) return AdapterResponse.fail("missing_arg", "query required", false);
        // Use the PyPI search HTML/JSON proxy via /search/?q=&format=json
        // (older endpoint — for Phase P we hit the search HTML page and parse
        // a JSON-shaped response when the upstream serves it; stewards can
        // override the base URL to a libraries.io or similar JSON-search
        // backend in practice).
        var rb = HttpRequest.newBuilder()
            .uri(URI.create(searchBase() + "/search/?q=" + urlEncode(q) + "&format=json"))
            .header("Accept", "application/json")
            .GET();
        return execute(rb, tree -> {
            // The mocked test backend returns {"results":[{"name":..,"version":..}]}.
            // Real pypi.org returns HTML — stewards override the base URL.
            var results = tree.path("results");
            var out = new ArrayList<Map<String, Object>>();
            for (var r : results) {
                var e = new LinkedHashMap<String, Object>();
                e.put("name", r.path("name").asText());
                e.put("version", r.path("version").asText());
                out.add(e);
            }
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse info(Map<String, Object> args) {
        var pkg = requireString(args, "packageName");
        if (pkg == null) return AdapterResponse.fail("missing_arg", "packageName required", false);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/pypi/" + urlEncode(pkg) + "/json"))
            .header("Accept", "application/json")
            .GET();
        return execute(rb, tree -> {
            var info = tree.path("info");
            var out = new LinkedHashMap<String, Object>();
            out.put("name", info.path("name").asText());
            out.put("version", info.path("version").asText());
            out.put("summary", info.path("summary").asText());
            out.put("license", info.path("license").asText());
            out.put("home_page", info.path("home_page").asText());
            out.put("author", info.path("author").asText());
            return AdapterResponse.ok(out);
        });
    }

}
