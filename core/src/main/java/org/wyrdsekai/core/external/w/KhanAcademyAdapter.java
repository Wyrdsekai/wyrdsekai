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
 * Khan Academy topic search and video
 * lookup. Khan exposes a public Algolia-backed search endpoint and an
 * unauthenticated content GraphQL surface; we use the simpler topic
 * search API plus a per-content lookup. No credential is strictly
 * required for read-only browsing; an optional {@code khanacademy.api_key}
 * may be supplied for partner integrations.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code topic_search(query)} / {@code search(query)} — Tier 4.</li>
 *   <li>{@code video_lookup(slug)} / {@code video_info(slug)} — Tier 4.</li>
 * </ul>
 */
public final class KhanAcademyAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "khan";

    private static final String DEFAULT_BASE = "https://www.khanacademy.org";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public KhanAcademyAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public KhanAcademyAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("topic_search", "search", "video_lookup", "video_info");
    }

    @Override public String credentialSlot() { return "khanacademy.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "topic_search", "search" -> topicSearch(request);
            case "video_lookup", "video_info" -> videoLookup(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse topicSearch(AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");

        var url = baseUrl + "/api/internal/search?page=0&query="
            + HttpAdapterSupport.urlEncode(query);
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            var parsed = http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            if (parsed instanceof Map<?, ?> top) {
                var hits = top.get("hits");
                if (hits instanceof List<?> list) {
                    for (var h : list) {
                        if (h instanceof Map<?, ?> hm) {
                            var entry = new LinkedHashMap<String, Object>();
                            entry.put("contentId", hm.get("id"));
                            entry.put("title", hm.get("title"));
                            entry.put("kind", hm.get("kind"));
                            entry.put("url", hm.get("url"));
                            out.add(entry);
                        }
                    }
                }
            }
            return out;
        });
    }

    private AdapterResponse videoLookup(AdapterRequest request) {
        var args = request.args();
        var slug = (String) args.get("slug");
        if (slug == null || slug.isBlank()) slug = (String) args.get("contentId");
        if (slug == null || slug.isBlank()) return http.missingArg("slug");

        var url = baseUrl + "/api/v1/videos/" + HttpAdapterSupport.urlEncode(slug);
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("contentId", parsed.get("id"));
            out.put("title", parsed.get("title"));
            out.put("description", parsed.get("description"));
            out.put("durationSec", parsed.get("duration"));
            out.put("youtubeId", parsed.get("youtube_id"));
            out.put("raw", parsed);
            return out;
        });
    }
}
