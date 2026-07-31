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
 * Hacker News (Algolia + Firebase) adapter.
 *
 * <p>Methods (Phase P scope): {@code top}, {@code search}, {@code comments}.</p>
 *
 * <p>HN reads are public — no credentials required. The adapter still
 * advertises a {@code hn.username} slot for future write surfaces (post /
 * comment, spec §4.25 Tier 5) but lookup-on-miss does not block reads.</p>
 */
public final class HackerNewsAdapter extends BaseHttpAdapter {

    private static final String ALGOLIA_BASE = "https://hn.algolia.com";
    private static final String FIREBASE_BASE = "https://hacker-news.firebaseio.com";

    private volatile String firebaseBaseOverride;

    @Override public String namespace() { return "hn"; }

    @Override public Set<String> capabilities() {
        return Set.of("top", "search", "comments");
    }

    @Override public String credentialSlot() { return "hn.username"; }

    @Override protected String defaultBaseUrl() { return ALGOLIA_BASE; }

    /** Test-only: redirect Firebase calls (top + comments) to a local mock. */
    public void setFirebaseBaseOverride(String url) {
        this.firebaseBaseOverride = url;
    }

    private String firebaseBase() {
        var override = firebaseBaseOverride;
        return override != null && !override.isBlank() ? override : FIREBASE_BASE;
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        // No credential gate — public reads. Future write methods (post/comment)
        // would call resolveCredential() and return credentialsMissing() on miss.
        var args = req.args();
        return switch (req.method()) {
            case "top" -> top(args);
            case "search" -> search(args);
            case "comments" -> comments(args);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse top(Map<String, Object> args) {
        var rb = HttpRequest.newBuilder()
            .uri(URI.create(firebaseBase() + "/v0/topstories.json"))
            .GET();
        return execute(rb, tree -> {
            var out = new ArrayList<Long>();
            int limit = 30;
            try {
                var l = args.get("limit");
                if (l != null) limit = Math.max(1, Math.min(500, Integer.parseInt(String.valueOf(l))));
            } catch (NumberFormatException nfe) {
                // ignore — fall through to default
            }
            int i = 0;
            for (var n : tree) {
                if (i++ >= limit) break;
                out.add(n.asLong());
            }
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse search(Map<String, Object> args) {
        var q = requireString(args, "query");
        if (q == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/v1/search?query=" + urlEncode(q)))
            .GET();
        return execute(rb, tree -> {
            var hits = tree.path("hits");
            var out = new ArrayList<Map<String, Object>>();
            for (var h : hits) {
                var e = new LinkedHashMap<String, Object>();
                e.put("id", h.path("objectID").asText());
                e.put("title", h.path("title").asText());
                e.put("author", h.path("author").asText());
                e.put("points", h.path("points").asLong());
                e.put("url", h.path("url").asText());
                out.add(e);
            }
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse comments(Map<String, Object> args) {
        var itemId = requireString(args, "itemId");
        if (itemId == null) return AdapterResponse.fail("missing_arg", "itemId required", false);
        var rb = HttpRequest.newBuilder()
            .uri(URI.create(firebaseBase() + "/v0/item/" + urlEncode(itemId) + ".json"))
            .GET();
        return execute(rb, tree -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("id", tree.path("id").asLong());
            out.put("title", tree.path("title").asText());
            out.put("author", tree.path("by").asText());
            out.put("text", tree.path("text").asText());
            var kids = tree.path("kids");
            var kidsList = new ArrayList<Long>();
            for (var k : kids) kidsList.add(k.asLong());
            out.put("kids", kidsList);
            return AdapterResponse.ok(out);
        });
    }

}
