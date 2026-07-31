package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YouTube Data API (read-only).
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code search(query, opts?)} — search.list.</li>
 *   <li>{@code transcript(videoId, lang?)} — fetched via timedtext (no auth).</li>
 *   <li>{@code channel_videos(channelId, opts?)} — search.list with channelId param.</li>
 * </ul>
 *
 * <p>The Data API uses an API key for read-only calls; OAuth is required for
 * upload/comment which are scoped out of Phase R.</p>
 */
public final class YouTubeAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "youtube";

    private static final String DATA_BASE = "https://www.googleapis.com/youtube/v3";
    private static final String TIMEDTEXT_BASE = "https://www.youtube.com/api/timedtext";

    private final HttpAdapterSupport http;
    private final String dataBase;
    private final String timedTextBase;

    public YouTubeAdapter() { this(new HttpAdapterSupport(), DATA_BASE, TIMEDTEXT_BASE); }

    public YouTubeAdapter(HttpAdapterSupport http, String dataBase, String timedTextBase) {
        this.http = http;
        this.dataBase = dataBase == null ? DATA_BASE : dataBase;
        this.timedTextBase = timedTextBase == null ? TIMEDTEXT_BASE : timedTextBase;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "transcript", "channel_videos");
    }

    @Override public String credentialSlot() { return "youtube.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search" -> search(request);
            case "transcript" -> transcript(request);
            case "channel_videos" -> channelVideos(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse search(AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var url = dataBase + "/search?part=snippet&type=video&q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&key=" + URLEncoder.encode(key.get(), StandardCharsets.UTF_8)
            + "&maxResults=" + args.getOrDefault("maxResults", 20);
        var req = http.reqBuilder(URI.create(url)).GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("items", parsed.get("items"));
            out.put("raw", parsed);
            return out;
        });
    }

    private AdapterResponse channelVideos(AdapterRequest request) {
        var args = request.args();
        var channelId = (String) args.get("channelId");
        if (channelId == null || channelId.isBlank()) return http.missingArg("channelId");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var url = dataBase + "/search?part=snippet&type=video&channelId="
            + URLEncoder.encode(channelId, StandardCharsets.UTF_8)
            + "&order=" + URLEncoder.encode((String) args.getOrDefault("order", "date"),
                StandardCharsets.UTF_8)
            + "&key=" + URLEncoder.encode(key.get(), StandardCharsets.UTF_8)
            + "&maxResults=" + args.getOrDefault("maxResults", 20);
        var req = http.reqBuilder(URI.create(url)).GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("items", parsed.get("items"));
            out.put("raw", parsed);
            return out;
        });
    }

    private AdapterResponse transcript(AdapterRequest request) {
        var args = request.args();
        var videoId = (String) args.get("videoId");
        if (videoId == null || videoId.isBlank()) return http.missingArg("videoId");
        var lang = (String) args.getOrDefault("lang", "en");
        // timedtext is unauthenticated and may return an empty body if no caption track exists.
        var url = timedTextBase + "?v=" + URLEncoder.encode(videoId, StandardCharsets.UTF_8)
            + "&lang=" + URLEncoder.encode(lang, StandardCharsets.UTF_8) + "&fmt=json3";
        var req = http.reqBuilder(URI.create(url)).GET().build();
        return http.execute(req, raw -> {
            if (raw == null || raw.isBlank()) {
                return Map.of("segments", List.of(), "available", false);
            }
            try {
                var parsed = http.parseJson(raw);
                return Map.of("segments", parsed, "available", true);
            } catch (Exception e) {
                return Map.of("segments", List.of(), "available", false);
            }
        });
    }
}
