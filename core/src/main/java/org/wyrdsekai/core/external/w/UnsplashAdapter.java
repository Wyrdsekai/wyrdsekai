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
 * Unsplash photo search.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code search(query, opts?)} — Tier 1 / public asset read.</li>
 *   <li>{@code download_url(photoId)} — Tier 1; resolves the canonical
 *       download endpoint (Unsplash ToS requires a {@code GET}/{@code HEAD}
 *       to {@code download_location} to track the download). The download
 *       itself is performed by the caller via {@code world.web.fetch_raw}.</li>
 * </ul>
 */
public final class UnsplashAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "unsplash";

    private static final String DEFAULT_BASE = "https://api.unsplash.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public UnsplashAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public UnsplashAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "download_url", "download");
    }

    @Override public String credentialSlot() { return "unsplash.access_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search" -> search(request);
            case "download_url", "download" -> downloadUrl(request);
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
        var page = args.getOrDefault("page", 1);
        var url = baseUrl + "/search/photos?query=" + HttpAdapterSupport.urlEncode(query)
            + "&per_page=" + perPage + "&page=" + page;
        var req = http.reqBuilder(URI.create(url))
            .header("Authorization", "Client-ID " + key.get())
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            var results = parsed.get("results");
            if (results instanceof List<?> list) {
                for (var r : list) {
                    if (r instanceof Map<?, ?> rm) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("photoId", rm.get("id"));
                        entry.put("urls", rm.get("urls"));
                        entry.put("user", rm.get("user"));
                        entry.put("license", "Unsplash License");
                        entry.put("description", rm.get("description"));
                        entry.put("alt", rm.get("alt_description"));
                        var links = rm.get("links");
                        if (links instanceof Map<?, ?> lm) {
                            entry.put("downloadLocation", lm.get("download_location"));
                        }
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }

    private AdapterResponse downloadUrl(AdapterRequest request) {
        var args = request.args();
        var photoId = (String) args.get("photoId");
        if (photoId == null || photoId.isBlank()) return http.missingArg("photoId");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());

        var url = baseUrl + "/photos/" + HttpAdapterSupport.urlEncode(photoId) + "/download";
        var req = http.reqBuilder(URI.create(url))
            .header("Authorization", "Client-ID " + key.get())
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("url", parsed.get("url"));
            out.put("photoId", photoId);
            out.put("attribution", "Photo via Unsplash");
            out.put("raw", parsed);
            return out;
        });
    }
}
