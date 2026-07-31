package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Iconify icon search.
 *
 * <p>Iconify's API is unauthenticated; the search endpoint accepts a
 * {@code query} param and returns matching icon names across collections.
 * Methods: {@code search_icons(query, opts?)} / {@code search}.</p>
 */
public final class IconifyAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "iconify";

    private static final String DEFAULT_BASE = "https://api.iconify.design";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public IconifyAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public IconifyAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search_icons", "search");
    }

    /** Iconify is auth-free — slot is declared so the framework can ignore it. */
    @Override public String credentialSlot() { return "iconify.user_agent"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search_icons", "search" -> search(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse search(AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");

        var limit = args.getOrDefault("limit", 32);
        var url = baseUrl + "/search?query=" + HttpAdapterSupport.urlEncode(query)
            + "&limit=" + limit;
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("total", parsed.get("total"));
            var icons = parsed.get("icons");
            var entries = new ArrayList<Map<String, Object>>();
            if (icons instanceof List<?> list) {
                for (var i : list) {
                    if (i instanceof String name) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("name", name);
                        entry.put("svgUrl", baseUrl + "/" + name + ".svg");
                        entries.add(entry);
                    }
                }
            }
            out.put("icons", entries);
            return out;
        });
    }
}
