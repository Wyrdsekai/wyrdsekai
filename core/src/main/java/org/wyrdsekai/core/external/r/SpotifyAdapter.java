package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spotify Web API.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code search(query, type?)} — GET /v1/search.</li>
 *   <li>{@code play(uri, opts?)} — PUT /v1/me/player/play.</li>
 *   <li>{@code queue(uri, deviceId?)} — POST /v1/me/player/queue.</li>
 *   <li>{@code recently_played(opts?)} — GET /v1/me/player/recently-played.</li>
 * </ul>
 */
public final class SpotifyAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "spotify";

    private static final String DEFAULT_BASE = "https://api.spotify.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public SpotifyAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public SpotifyAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "play", "queue", "recently_played");
    }

    @Override public String credentialSlot() { return "spotify.access_token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var token = key.get();
        return switch (request.method()) {
            case "search" -> search(token, request);
            case "play" -> play(token, request);
            case "queue" -> queue(token, request);
            case "recently_played" -> recentlyPlayed(token, request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse search(String token, AdapterRequest request) {
        var args = request.args();
        var query = (String) args.get("query");
        if (query == null || query.isBlank()) return http.missingArg("query");
        var type = (String) args.getOrDefault("type", "track");
        var url = baseUrl + "/v1/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&type=" + URLEncoder.encode(type, StandardCharsets.UTF_8) + "&limit=20";
        var req = http.reqBuilder(URI.create(url))
            .header("authorization", "Bearer " + token)
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            return Map.of("results", parsed);
        });
    }

    private AdapterResponse play(String token, AdapterRequest request) {
        var args = request.args();
        var uri = (String) args.get("uri");
        // uri optional — when missing, spotify resumes the last context.
        var body = new LinkedHashMap<String, Object>();
        if (uri != null && !uri.isBlank()) {
            if (uri.contains(":track:") || uri.startsWith("spotify:track:")) {
                body.put("uris", List.of(uri));
            } else {
                body.put("context_uri", uri);
            }
        }
        var deviceQ = "";
        if (args.get("deviceId") instanceof String dev && !dev.isBlank()) {
            deviceQ = "?device_id=" + URLEncoder.encode(dev, StandardCharsets.UTF_8);
        }
        var req = http.reqBuilder(URI.create(baseUrl + "/v1/me/player/play" + deviceQ))
            .header("authorization", "Bearer " + token)
            .header("content-type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> Map.of("ok", true));
    }

    private AdapterResponse queue(String token, AdapterRequest request) {
        var args = request.args();
        var uri = (String) args.get("uri");
        if (uri == null || uri.isBlank()) return http.missingArg("uri");
        var url = baseUrl + "/v1/me/player/queue?uri=" + URLEncoder.encode(uri, StandardCharsets.UTF_8);
        if (args.get("deviceId") instanceof String dev && !dev.isBlank()) {
            url += "&device_id=" + URLEncoder.encode(dev, StandardCharsets.UTF_8);
        }
        var req = http.reqBuilder(URI.create(url))
            .header("authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        return http.execute(req, raw -> Map.of("ok", true));
    }

    private AdapterResponse recentlyPlayed(String token, AdapterRequest request) {
        var args = request.args();
        var limit = args.getOrDefault("limit", 20);
        var url = baseUrl + "/v1/me/player/recently-played?limit=" + limit;
        var req = http.reqBuilder(URI.create(url))
            .header("authorization", "Bearer " + token)
            .GET().build();
        return http.execute(req, raw -> {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) http.parseJson(raw);
            var out = new LinkedHashMap<String, Object>();
            out.put("items", parsed.get("items"));
            out.put("raw", parsed);
            return out;
        });
    }
}
