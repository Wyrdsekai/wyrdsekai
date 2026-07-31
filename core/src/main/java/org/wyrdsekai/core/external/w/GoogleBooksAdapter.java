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
 * Google Books.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code search(query, opts?)}.</li>
 *   <li>{@code volume_info(volumeId)}.</li>
 * </ul>
 *
 * <p>An API key is optional for low-volume read-only use; we resolve it
 * when present and fall back to unauthenticated otherwise.</p>
 */
public final class GoogleBooksAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "gbooks";

    private static final String DEFAULT_BASE = "https://www.googleapis.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public GoogleBooksAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public GoogleBooksAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "volume_info");
    }

    @Override public String credentialSlot() { return "googlebooks.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search" -> search(request);
            case "volume_info" -> volumeInfo(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse search(AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");

        var max = args.getOrDefault("maxResults", 10);
        var url = new StringBuilder(baseUrl)
            .append("/books/v1/volumes?q=").append(HttpAdapterSupport.urlEncode(query))
            .append("&maxResults=").append(max);
        http.resolveCredential(credentialSlot()).ifPresent(k ->
            url.append("&key=").append(HttpAdapterSupport.urlEncode(k)));

        var req = http.reqBuilder(URI.create(url.toString()))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            var items = parsed.get("items");
            if (items instanceof List<?> list) {
                for (var i : list) {
                    if (i instanceof Map<?, ?> im) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("volumeId", im.get("id"));
                        var info = im.get("volumeInfo");
                        if (info instanceof Map<?, ?> vi) {
                            entry.put("title", vi.get("title"));
                            entry.put("authors", vi.get("authors"));
                            entry.put("snippet", vi.get("description"));
                            entry.put("publishedDate", vi.get("publishedDate"));
                        }
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }

    private AdapterResponse volumeInfo(AdapterRequest request) {
        var volumeId = (String) request.args().get("volumeId");
        if (volumeId == null || volumeId.isBlank()) return http.missingArg("volumeId");

        var url = new StringBuilder(baseUrl)
            .append("/books/v1/volumes/").append(HttpAdapterSupport.urlEncode(volumeId));
        http.resolveCredential(credentialSlot()).ifPresent(k ->
            url.append("?key=").append(HttpAdapterSupport.urlEncode(k)));

        var req = http.reqBuilder(URI.create(url.toString()))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("volumeId", parsed.get("id"));
            var info = parsed.get("volumeInfo");
            if (info instanceof Map<?, ?> vi) {
                out.put("title", vi.get("title"));
                out.put("authors", vi.get("authors"));
                out.put("description", vi.get("description"));
                out.put("previewLink", vi.get("previewLink"));
                out.put("pageCount", vi.get("pageCount"));
                out.put("categories", vi.get("categories"));
            }
            return out;
        });
    }
}
