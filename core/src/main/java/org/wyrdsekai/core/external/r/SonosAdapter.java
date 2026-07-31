package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sonos cloud control API.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code play(group, opts?)} — playback/play.</li>
 *   <li>{@code pause(group)} — playback/pause.</li>
 *   <li>{@code skip(group)} — playback/skipToNextTrack.</li>
 *   <li>{@code queue(group, uri)} — playbackMetadata/loadQueue (passthrough).</li>
 * </ul>
 *
 * <p>Authentication uses the OAuth bearer token stored at
 * {@code sonos.access_token} (refresh handled out-of-band).</p>
 */
public final class SonosAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "sonos";

    private static final String DEFAULT_BASE = "https://api.ws.sonos.com";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public SonosAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public SonosAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("play", "pause", "skip", "queue"); }

    @Override public String credentialSlot() { return "sonos.access_token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        var token = CredentialResolver.get().resolve(credentialSlot());
        if (token.isEmpty()) return http.missingCredential(credentialSlot());
        var args = request.args();
        var group = (String) args.get("group");
        if (group == null || group.isBlank()) group = (String) args.get("groupId");
        if (group == null || group.isBlank()) return http.missingArg("group");
        return switch (request.method()) {
            case "play" -> simplePost(token.get(), group, "playback/play", Map.of());
            case "pause" -> simplePost(token.get(), group, "playback/pause", Map.of());
            case "skip" -> simplePost(token.get(), group, "playback/skipToNextTrack", Map.of());
            case "queue" -> queue(token.get(), group, args);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse simplePost(String token, String group, String path,
                                         Map<String, Object> body) {
        var req = http.reqBuilder(URI.create(
                baseUrl + "/control/api/v1/groups/" + group + "/" + path))
            .header("authorization", "Bearer " + token)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        return http.execute(req, raw -> Map.of("ok", true));
    }

    private AdapterResponse queue(String token, String group, Map<String, Object> args) {
        var uri = (String) args.get("uri");
        if (uri == null || uri.isBlank()) return http.missingArg("uri");
        var body = new LinkedHashMap<String, Object>();
        body.put("favoriteId", args.get("favoriteId"));
        body.put("playOnCompletion", args.getOrDefault("playOnCompletion", false));
        body.put("uri", uri);
        return simplePost(token, group, "playbackMetadata/loadQueue", body);
    }
}
