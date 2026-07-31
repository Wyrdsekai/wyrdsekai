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
 * MangaDex search and chapter listing.
 *
 * <p>MangaDex's API is unauthenticated for read endpoints. Methods:</p>
 * <ul>
 *   <li>{@code search(query, opts?)} — manga search.</li>
 *   <li>{@code chapter_list(mangaId, opts?)} — chapter feed for a manga.</li>
 * </ul>
 */
public final class MangaDexAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "mangadex";

    private static final String DEFAULT_BASE = "https://api.mangadex.org";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public MangaDexAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public MangaDexAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "chapter_list");
    }

    /** MangaDex is auth-free for reads. */
    @Override public String credentialSlot() { return "mangadex.session"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search" -> search(request);
            case "chapter_list" -> chapterList(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse search(AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");

        var limit = args.getOrDefault("limit", 10);
        var url = baseUrl + "/manga?title=" + HttpAdapterSupport.urlEncode(query)
            + "&limit=" + limit;
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            var data = parsed.get("data");
            if (data instanceof List<?> list) {
                for (var d : list) {
                    if (d instanceof Map<?, ?> dm) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("mangaId", dm.get("id"));
                        entry.put("type", dm.get("type"));
                        var attrs = dm.get("attributes");
                        if (attrs instanceof Map<?, ?> am) {
                            entry.put("title", am.get("title"));
                            entry.put("description", am.get("description"));
                            entry.put("originalLanguage", am.get("originalLanguage"));
                            entry.put("status", am.get("status"));
                        }
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }

    private AdapterResponse chapterList(AdapterRequest request) {
        var args = request.args();
        var mangaId = (String) args.get("mangaId");
        if (mangaId == null || mangaId.isBlank()) return http.missingArg("mangaId");

        var limit = args.getOrDefault("limit", 50);
        var url = baseUrl + "/manga/" + HttpAdapterSupport.urlEncode(mangaId)
            + "/feed?limit=" + limit + "&order[chapter]=asc";
        var req = http.reqBuilder(URI.create(url))
            .header("accept", "application/json")
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new ArrayList<Map<String, Object>>();
            var data = parsed.get("data");
            if (data instanceof List<?> list) {
                for (var d : list) {
                    if (d instanceof Map<?, ?> dm) {
                        var entry = new LinkedHashMap<String, Object>();
                        entry.put("chapterId", dm.get("id"));
                        var attrs = dm.get("attributes");
                        if (attrs instanceof Map<?, ?> am) {
                            entry.put("chapter", am.get("chapter"));
                            entry.put("title", am.get("title"));
                            entry.put("language", am.get("translatedLanguage"));
                            entry.put("publishAt", am.get("publishAt"));
                            entry.put("pages", am.get("pages"));
                        }
                        out.add(entry);
                    }
                }
            }
            return out;
        });
    }
}
