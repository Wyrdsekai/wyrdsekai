package org.wyrdsekai.core.external.p;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bluesky / atproto adapter.
 *
 * <p>Methods (Phase P scope): {@code post}, {@code search}.</p>
 *
 * <p>Reads the {@code bluesky.app_password} slot which encodes the value
 * <em>{handle}|{access_jwt}</em> (the steward bakes the session externally).
 * The adapter splits on {@code |} and uses the JWT as the bearer token; if
 * no separator is present the entire value is treated as the JWT.</p>
 */
public final class BlueskyAdapter extends BaseHttpAdapter {

    private static final String DEFAULT_BASE = "https://bsky.social";

    @Override public String namespace() { return "bluesky"; }

    @Override public Set<String> capabilities() {
        return Set.of("post", "search");
    }

    @Override public String credentialSlot() { return "bluesky.app_password"; }

    @Override protected String defaultBaseUrl() { return DEFAULT_BASE; }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var cred = resolveCredential();
        if (cred.isEmpty()) return credentialsMissing();
        var split = splitCredential(cred.get());
        var handle = split[0];
        var jwt = split[1];
        var args = req.args();
        return switch (req.method()) {
            case "post" -> post(args, handle, jwt);
            case "search" -> search(args, jwt);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private static String[] splitCredential(String raw) {
        var idx = raw.indexOf('|');
        if (idx < 0) return new String[] { "did:plc:unknown", raw };
        return new String[] { raw.substring(0, idx), raw.substring(idx + 1) };
    }

    private AdapterResponse post(Map<String, Object> args, String handle, String jwt) {
        var text = requireString(args, "text");
        if (text == null) return AdapterResponse.fail("missing_arg", "text required", false);
        var record = new LinkedHashMap<String, Object>();
        record.put("$type", "app.bsky.feed.post");
        record.put("text", text);
        record.put("createdAt", Instant.now().toString());

        var langs = (List<?>) args.get("langs");
        if (langs != null && !langs.isEmpty()) record.put("langs", langs);

        var body = new LinkedHashMap<String, Object>();
        body.put("repo", handle);
        body.put("collection", "app.bsky.feed.post");
        body.put("record", record);

        var rb = HttpRequest.newBuilder()
            .uri(uri("/xrpc/com.atproto.repo.createRecord"))
            .header("Authorization", "Bearer " + jwt)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body));
        return execute(rb, tree -> AdapterResponse.ok(Map.of(
            "uri", tree.path("uri").asText(),
            "cid", tree.path("cid").asText())));
    }

    private AdapterResponse search(Map<String, Object> args, String jwt) {
        var q = requireString(args, "query");
        if (q == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/xrpc/app.bsky.feed.searchPosts?q=" + urlEncode(q)))
            .header("Authorization", "Bearer " + jwt)
            .GET();
        return execute(rb, tree -> {
            var posts = tree.path("posts");
            var out = new ArrayList<Map<String, Object>>();
            for (var p : posts) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("uri", p.path("uri").asText());
                entry.put("cid", p.path("cid").asText());
                entry.put("record", nodeToMap(p.path("record")));
                out.add(entry);
            }
            return AdapterResponse.ok(out);
        });
    }
}
