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
 * Pixabay free image library search.
 *
 * <p>Pixabay's API is a single GET endpoint with the {@code key} query
 * parameter. Methods: {@code search(query, opts?)}.</p>
 */
public final class PixabayAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "pixabay";

    private static final String DEFAULT_BASE = "https://pixabay.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public PixabayAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public PixabayAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("search"); }

    @Override public String credentialSlot() { return "pixabay.api_key"; }

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
        var url = baseUrl + "/api/?key=" + HttpAdapterSupport.urlEncode(key.get())
            + "&q=" + HttpAdapterSupport.urlEncode(query)
            + "&per_page=" + perPage;
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            var hits = parsed.get("hits");
            if (hits instanceof List<?> list) {
                for (var h : list) {
                    if (h instanceof Map<?, ?> hm) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("id", hm.get("id"));
                        entry.put("urls", Map.of(
                            "preview", hm.get("previewURL"),
                            "web", hm.get("webformatURL"),
                            "large", hm.get("largeImageURL")));
                        entry.put("user", hm.get("user"));
                        entry.put("license", "Pixabay Content License");
                        entry.put("tags", hm.get("tags"));
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }
}
