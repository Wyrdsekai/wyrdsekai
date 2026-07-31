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
 * Open Library (Internet Archive).
 *
 * <p>Open Library has an unauthenticated REST API. Methods:</p>
 * <ul>
 *   <li>{@code search(query, opts?)} — works search.</li>
 *   <li>{@code work_info(workId)} — work details.</li>
 *   <li>{@code edition_info(editionId)} — edition / book details.</li>
 * </ul>
 */
public final class OpenLibraryAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "openlib";

    private static final String DEFAULT_BASE = "https://openlibrary.org";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public OpenLibraryAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public OpenLibraryAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "work_info", "edition_info");
    }

    /** Open Library is auth-free. */
    @Override public String credentialSlot() { return "openlib.user_agent"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search" -> search(request);
            case "work_info" -> workInfo(request);
            case "edition_info" -> editionInfo(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse search(AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");

        var limit = args.getOrDefault("limit", 10);
        var url = baseUrl + "/search.json?q=" + HttpAdapterSupport.urlEncode(query)
            + "&limit=" + limit;
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            var docs = parsed.get("docs");
            if (docs instanceof List<?> list) {
                for (var d : list) {
                    if (d instanceof Map<?, ?> dm) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("workKey", dm.get("key"));
                        entry.put("title", dm.get("title"));
                        entry.put("authors", dm.get("author_name"));
                        entry.put("firstPublishYear", dm.get("first_publish_year"));
                        entry.put("editionCount", dm.get("edition_count"));
                        entry.put("coverId", dm.get("cover_i"));
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }

    private AdapterResponse workInfo(AdapterRequest request) {
        var workId = (String) request.args().get("workId");
        if (workId == null || workId.isBlank()) return http.missingArg("workId");
        // Allow both "OL12345W" and "/works/OL12345W".
        var path = workId.startsWith("/") ? workId : "/works/" + workId;
        var url = baseUrl + path + ".json";
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> http.parseJson(raw));
    }

    private AdapterResponse editionInfo(AdapterRequest request) {
        var editionId = (String) request.args().get("editionId");
        if (editionId == null || editionId.isBlank()) return http.missingArg("editionId");
        var path = editionId.startsWith("/") ? editionId : "/books/" + editionId;
        var url = baseUrl + path + ".json";
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> http.parseJson(raw));
    }
}
