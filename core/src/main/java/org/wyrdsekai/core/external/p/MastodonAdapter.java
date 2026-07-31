package org.wyrdsekai.core.external.p;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mastodon ActivityPub adapter.
 *
 * <p>Methods (Phase P scope): {@code post}, {@code reply}, {@code search},
 * {@code follow}.</p>
 *
 * <p>Default base URL is {@code https://mastodon.social}; users can override
 * via the {@code mastodon.instance} credential field if needed (a future
 * iteration). For Phase P we read the access token from
 * {@code mastodon.access_token} and target the canonical instance.</p>
 */
public final class MastodonAdapter extends BaseHttpAdapter {

    private static final String DEFAULT_BASE = "https://mastodon.social";

    @Override public String namespace() { return "mastodon"; }

    @Override public Set<String> capabilities() {
        return Set.of("post", "reply", "search", "follow");
    }

    @Override public String credentialSlot() { return "mastodon.access_token"; }

    @Override protected String defaultBaseUrl() { return DEFAULT_BASE; }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return credentialsMissing();
        var args = req.args();
        return switch (req.method()) {
            case "post" -> post(args, token.get(), null);
            case "reply" -> {
                var replyTo = requireString(args, "statusId");
                if (replyTo == null) yield AdapterResponse.fail(
                    "missing_arg", "reply requires statusId", false);
                yield post(args, token.get(), replyTo);
            }
            case "search" -> search(args, token.get());
            case "follow" -> follow(args, token.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse post(Map<String, Object> args, String token, String replyTo) {
        var text = requireString(args, "text");
        if (text == null) return AdapterResponse.fail("missing_arg", "text required", false);
        var body = new LinkedHashMap<String, Object>();
        body.put("status", text);
        var visibility = optString(args, "visibility", "public");
        body.put("visibility", visibility);
        if (replyTo != null) body.put("in_reply_to_id", replyTo);
        var cw = (String) args.get("contentWarning");
        if (cw != null && !cw.isBlank()) body.put("spoiler_text", cw);
        var lang = (String) args.get("language");
        if (lang != null && !lang.isBlank()) body.put("language", lang);

        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/v1/statuses"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body));
        return execute(rb, tree -> {
            var id = tree.path("id").asText();
            var url = tree.path("url").asText();
            return AdapterResponse.ok(Map.of("id", id, "url", url));
        });
    }

    private AdapterResponse search(Map<String, Object> args, String token) {
        var q = requireString(args, "query");
        if (q == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/v2/search?q=" + urlEncode(q)))
            .header("Authorization", "Bearer " + token)
            .GET();
        return execute(rb, tree -> {
            var out = new LinkedHashMap<String, Object>();
            out.put("accounts", nodeToList(tree.path("accounts")));
            out.put("statuses", nodeToList(tree.path("statuses")));
            out.put("hashtags", nodeToList(tree.path("hashtags")));
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse follow(Map<String, Object> args, String token) {
        var account = requireString(args, "account");
        if (account == null) return AdapterResponse.fail("missing_arg", "account required", false);
        // Mastodon follow requires the account ID; for Phase P we accept either an ID
        // (numeric) or a webfinger handle and resolve to ID via /api/v1/accounts/lookup.
        String accountId = account;
        if (!account.matches("\\d+")) {
            var lookup = HttpRequest.newBuilder()
                .uri(uri("/api/v1/accounts/lookup?acct=" + urlEncode(account)))
                .header("Authorization", "Bearer " + token)
                .GET();
            var lookupResp = execute(lookup, tree -> AdapterResponse.ok(tree.path("id").asText()));
            if (!lookupResp.success()) return lookupResp;
            accountId = String.valueOf(lookupResp.data());
            if (accountId == null || accountId.isBlank()) {
                return AdapterResponse.fail("account_not_found", account, false);
            }
        }
        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/v1/accounts/" + urlEncode(accountId) + "/follow"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody());
        return execute(rb, tree -> AdapterResponse.ok(Map.of("ok", true,
            "following", tree.path("following").asBoolean(true))));
    }
}
