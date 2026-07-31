package org.wyrdsekai.core.external.p;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * X (Twitter) v2 API adapter.
 *
 * <p>Methods (Phase P scope): {@code post}, {@code search}, {@code dm}.</p>
 *
 * <p>X is rate-limited aggressively. The adapter applies a per-method local
 * rate limiter (in-memory, sliding 15-minute window) on top of any
 * server-side limits — exceeding the local cap returns
 * {@code rate_limited} immediately so item scripts can back off without
 * burning quota. Limits per spec defaults: 50 posts / 300 searches /
 * 100 DMs per 15 minutes.</p>
 */
public final class XAdapter extends BaseHttpAdapter {

    private static final String DEFAULT_BASE = "https://api.x.com";
    private static final long WINDOW_MS = 15L * 60 * 1000;

    private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();

    @Override public String namespace() { return "x"; }

    @Override public Set<String> capabilities() {
        return Set.of("post", "search", "dm");
    }

    @Override public String credentialSlot() { return "x.bearer_token"; }

    @Override protected String defaultBaseUrl() { return DEFAULT_BASE; }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return credentialsMissing();
        if (!checkRate(req.method())) {
            return AdapterResponse.fail("rate_limited",
                "local rate limit hit for x." + req.method(), true);
        }
        var args = req.args();
        return switch (req.method()) {
            case "post" -> post(args, token.get());
            case "search" -> search(args, token.get());
            case "dm" -> dm(args, token.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private boolean checkRate(String method) {
        int cap = switch (method) {
            case "post" -> 50;
            case "search" -> 300;
            case "dm" -> 100;
            default -> 1000;
        };
        var bucket = buckets.computeIfAbsent(method, k -> new RateBucket());
        return bucket.tryAcquire(cap);
    }

    private AdapterResponse post(Map<String, Object> args, String token) {
        var text = requireString(args, "text");
        if (text == null) return AdapterResponse.fail("missing_arg", "text required", false);
        var body = new LinkedHashMap<String, Object>();
        body.put("text", text);
        var replyTo = (String) args.get("replyTo");
        if (replyTo != null && !replyTo.isBlank()) {
            body.put("reply", Map.of("in_reply_to_tweet_id", replyTo));
        }
        var rb = HttpRequest.newBuilder()
            .uri(uri("/2/tweets"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body));
        return execute(rb, tree -> {
            var data = tree.path("data");
            return AdapterResponse.ok(Map.of(
                "id", data.path("id").asText(),
                "text", data.path("text").asText()));
        });
    }

    private AdapterResponse search(Map<String, Object> args, String token) {
        var q = requireString(args, "query");
        if (q == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/2/tweets/search/recent?query=" + urlEncode(q)))
            .header("Authorization", "Bearer " + token)
            .GET();
        return execute(rb, tree -> {
            var data = tree.path("data");
            var out = new ArrayList<Map<String, Object>>();
            for (var t : data) {
                var e = new LinkedHashMap<String, Object>();
                e.put("id", t.path("id").asText());
                e.put("text", t.path("text").asText());
                out.add(e);
            }
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse dm(Map<String, Object> args, String token) {
        var recipient = requireString(args, "recipient");
        var text = requireString(args, "text");
        if (recipient == null) return AdapterResponse.fail("missing_arg", "recipient required", false);
        if (text == null) return AdapterResponse.fail("missing_arg", "text required", false);
        var body = Map.<String, Object>of("text", text);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/2/dm_conversations/with/" + urlEncode(recipient) + "/messages"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body));
        return execute(rb, tree -> {
            var data = tree.path("data");
            return AdapterResponse.ok(Map.of(
                "id", data.path("dm_event_id").asText(),
                "ok", true));
        });
    }

    /** Test-only: clear rate buckets so a clean run starts at full quota. */
    public void clearRateLimitForTests() {
        buckets.clear();
    }

    /** Sliding-window counter — coarse but enough for the local cap. */
    private static final class RateBucket {
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong count = new AtomicLong(0);

        boolean tryAcquire(int cap) {
            var now = System.currentTimeMillis();
            var start = windowStart.get();
            if (now - start > WINDOW_MS) {
                windowStart.set(now);
                count.set(0);
            }
            return count.incrementAndGet() <= cap;
        }
    }
}
