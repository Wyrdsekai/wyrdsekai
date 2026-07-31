package org.wyrdsekai.core.external.p;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reddit OAuth API adapter.
 *
 * <p>Methods (Phase P scope): {@code post} (submit), {@code comment},
 * {@code search}, {@code subscribe}.</p>
 *
 * <p>Reads from the {@code reddit.refresh_token} slot. The adapter assumes
 * the token has been exchanged for a usable access token externally — the
 * OAuth dance is out of scope for the API surface.</p>
 *
 * <p>{@code search} hits the public Reddit JSON endpoint
 * ({@code https://www.reddit.com/search.json}); it doesn't require auth, but
 * we still gate the entire adapter on credential presence to keep the
 * surface symmetric with write methods. If a steward wants un-authed search
 * they can populate the slot with a sentinel value (e.g. {@code "anonymous"}).</p>
 */
public final class RedditAdapter extends BaseHttpAdapter {

    private static final String DEFAULT_BASE = "https://oauth.reddit.com";
    private static final String PUBLIC_BASE = "https://www.reddit.com";
    private static final String USER_AGENT = "wyrdsekai-bot/1.0";

    @Override public String namespace() { return "reddit"; }

    @Override public Set<String> capabilities() {
        return Set.of("post", "comment", "search", "subscribe");
    }

    @Override public String credentialSlot() { return "reddit.refresh_token"; }

    @Override protected String defaultBaseUrl() { return DEFAULT_BASE; }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return credentialsMissing();
        var args = req.args();
        return switch (req.method()) {
            case "post" -> submit(args, token.get());
            case "comment" -> comment(args, token.get());
            case "search" -> search(args, token.get());
            case "subscribe" -> subscribe(args, token.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse submit(Map<String, Object> args, String token) {
        var subreddit = requireString(args, "subreddit");
        var title = requireString(args, "title");
        if (subreddit == null) return AdapterResponse.fail("missing_arg", "subreddit required", false);
        if (title == null) return AdapterResponse.fail("missing_arg", "title required", false);

        var url = (String) args.get("url");
        var text = (String) args.get("text");
        var form = new StringBuilder();
        form.append("sr=").append(urlEncode(subreddit));
        form.append("&title=").append(urlEncode(title));
        if (url != null && !url.isBlank()) {
            form.append("&kind=link&url=").append(urlEncode(url));
        } else {
            form.append("&kind=self&text=").append(urlEncode(text == null ? "" : text));
        }
        form.append("&api_type=json");

        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/submit"))
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString()));

        return execute(rb, tree -> {
            // Reddit envelope: {json: {data: {id, name, url}, errors: [...]}}
            var data = tree.path("json").path("data");
            var id = data.path("id").asText();
            var permalink = data.path("url").asText();
            if (id.isBlank()) {
                var errors = tree.path("json").path("errors");
                return AdapterResponse.fail("reddit_error",
                    errors.isMissingNode() ? "submit failed" : errors.toString(), false);
            }
            return AdapterResponse.ok(Map.of("id", id, "permalink", permalink));
        });
    }

    private AdapterResponse comment(Map<String, Object> args, String token) {
        var parentId = requireString(args, "parentId");
        var text = requireString(args, "text");
        if (parentId == null) return AdapterResponse.fail("missing_arg", "parentId required", false);
        if (text == null) return AdapterResponse.fail("missing_arg", "text required", false);

        var form = "thing_id=" + urlEncode(parentId)
            + "&text=" + urlEncode(text)
            + "&api_type=json";
        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/comment"))
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form));

        return execute(rb, tree -> {
            // Reddit returns {json: {data: {things: [{data: {id, permalink}}]}}}
            var things = tree.path("json").path("data").path("things");
            if (things.isArray() && things.size() > 0) {
                var d = things.get(0).path("data");
                return AdapterResponse.ok(Map.of(
                    "id", d.path("id").asText(),
                    "permalink", d.path("permalink").asText()));
            }
            return AdapterResponse.fail("reddit_error", "comment failed", false);
        });
    }

    private AdapterResponse search(Map<String, Object> args, String token) {
        var q = requireString(args, "query");
        if (q == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var subreddit = (String) args.get("subreddit");
        var path = subreddit != null && !subreddit.isBlank()
            ? "/r/" + urlEncode(subreddit) + "/search.json?restrict_sr=on&q=" + urlEncode(q)
            : "/search.json?q=" + urlEncode(q);

        // Search uses the public endpoint — base URL override.
        var publicBase = baseUrl().contains("127.0.0.1") || baseUrl().contains("localhost")
            ? baseUrl() : PUBLIC_BASE;
        var rb = HttpRequest.newBuilder()
            .uri(URI.create(publicBase + path))
            .header("User-Agent", USER_AGENT)
            .GET();
        return execute(rb, tree -> {
            var children = tree.path("data").path("children");
            var out = new ArrayList<Map<String, Object>>();
            for (var c : children) {
                var d = c.path("data");
                var entry = new LinkedHashMap<String, Object>();
                entry.put("id", d.path("id").asText());
                entry.put("title", d.path("title").asText());
                entry.put("author", d.path("author").asText());
                entry.put("score", d.path("score").asLong());
                out.add(entry);
            }
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse subscribe(Map<String, Object> args, String token) {
        var subreddit = requireString(args, "subreddit");
        if (subreddit == null) return AdapterResponse.fail("missing_arg", "subreddit required", false);
        var form = "action=sub&sr_name=" + urlEncode(subreddit);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/subscribe"))
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form));
        return execute(rb, tree -> AdapterResponse.ok(Map.of("ok", true)));
    }
}
