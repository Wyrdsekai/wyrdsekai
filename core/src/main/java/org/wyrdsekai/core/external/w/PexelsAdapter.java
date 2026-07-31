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
 * Pexels free stock photo search.
 *
 * <p>Methods: {@code search(query, opts?)}.</p>
 */
public final class PexelsAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "pexels";

    private static final String DEFAULT_BASE = "https://api.pexels.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public PexelsAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public PexelsAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("search"); }

    @Override public String credentialSlot() { return "pexels.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search" -> search(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse search(AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());

        var perPage = args.getOrDefault("perPage", 10);
        var url = baseUrl + "/v1/search?query=" + HttpAdapterSupport.urlEncode(query)
            + "&per_page=" + perPage;
        var req = http.reqBuilder(URI.create(url))
            .header("Authorization", key.get())
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            var photos = parsed.get("photos");
            if (photos instanceof List<?> list) {
                for (var p : list) {
                    if (p instanceof Map<?, ?> pm) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("photoId", pm.get("id"));
                        entry.put("src", pm.get("src"));
                        entry.put("photographer", pm.get("photographer"));
                        entry.put("photographerUrl", pm.get("photographer_url"));
                        entry.put("license", "Pexels License");
                        entry.put("alt", pm.get("alt"));
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }
}
