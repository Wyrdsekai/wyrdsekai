package org.wyrdsekai.core.external.p;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitHub REST API adapter.
 *
 * <p>Methods (Phase P scope): {@code create_issue}, {@code comment} (alias of
 * add_comment), {@code list_prs}, {@code search_code}, {@code create_pr}.</p>
 *
 * <p>Reads from {@code github.token} (a fine-grained PAT or app token).
 * The {@code repo} arg is the canonical {@code owner/repo} string used by
 * the GitHub API.</p>
 */
public final class GitHubAdapter extends BaseHttpAdapter {

    private static final String DEFAULT_BASE = "https://api.github.com";

    @Override public String namespace() { return "github"; }

    @Override public Set<String> capabilities() {
        return Set.of("create_issue", "comment", "list_prs",
            "search_code", "create_pr");
    }

    @Override public String credentialSlot() { return "github.token"; }

    @Override protected String defaultBaseUrl() { return DEFAULT_BASE; }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return credentialsMissing();
        var args = req.args();
        return switch (req.method()) {
            case "create_issue" -> createIssue(args, token.get());
            case "comment" -> addComment(args, token.get());
            case "list_prs" -> listPrs(args, token.get());
            case "search_code" -> searchCode(args, token.get());
            case "create_pr" -> createPr(args, token.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse createIssue(Map<String, Object> args, String token) {
        var repo = requireString(args, "repo");
        var title = requireString(args, "title");
        if (repo == null) return AdapterResponse.fail("missing_arg", "repo required", false);
        if (title == null) return AdapterResponse.fail("missing_arg", "title required", false);
        var body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        var bodyText = (String) args.get("body");
        if (bodyText != null) body.put("body", bodyText);
        var labels = (List<?>) args.get("labels");
        if (labels != null && !labels.isEmpty()) body.put("labels", labels);
        var assignees = (List<?>) args.get("assignees");
        if (assignees != null && !assignees.isEmpty()) body.put("assignees", assignees);

        var rb = HttpRequest.newBuilder()
            .uri(uri("/repos/" + repo + "/issues"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .POST(jsonBody(body));
        return execute(rb, tree -> AdapterResponse.ok(Map.of(
            "number", tree.path("number").asLong(),
            "url", tree.path("html_url").asText())));
    }

    private AdapterResponse addComment(Map<String, Object> args, String token) {
        var repo = requireString(args, "repo");
        var issue = requireString(args, "issueNumber");
        var body = requireString(args, "body");
        if (repo == null) return AdapterResponse.fail("missing_arg", "repo required", false);
        if (issue == null) return AdapterResponse.fail("missing_arg", "issueNumber required", false);
        if (body == null) return AdapterResponse.fail("missing_arg", "body required", false);

        var payload = Map.<String, Object>of("body", body);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/repos/" + repo + "/issues/" + urlEncode(issue) + "/comments"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .POST(jsonBody(payload));
        return execute(rb, tree -> AdapterResponse.ok(Map.of(
            "commentId", tree.path("id").asLong(),
            "url", tree.path("html_url").asText())));
    }

    private AdapterResponse listPrs(Map<String, Object> args, String token) {
        var repo = requireString(args, "repo");
        if (repo == null) return AdapterResponse.fail("missing_arg", "repo required", false);
        var state = optString(args, "state", "open");
        var rb = HttpRequest.newBuilder()
            .uri(uri("/repos/" + repo + "/pulls?state=" + urlEncode(state)))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .GET();
        return execute(rb, tree -> {
            var out = new ArrayList<Map<String, Object>>();
            for (var pr : tree) {
                var e = new LinkedHashMap<String, Object>();
                e.put("number", pr.path("number").asLong());
                e.put("title", pr.path("title").asText());
                e.put("state", pr.path("state").asText());
                e.put("url", pr.path("html_url").asText());
                e.put("user", pr.path("user").path("login").asText());
                out.add(e);
            }
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse searchCode(Map<String, Object> args, String token) {
        var q = requireString(args, "query");
        if (q == null) return AdapterResponse.fail("missing_arg", "query required", false);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/search/code?q=" + urlEncode(q)))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .GET();
        return execute(rb, tree -> {
            var items = tree.path("items");
            var out = new ArrayList<Map<String, Object>>();
            for (var i : items) {
                var e = new LinkedHashMap<String, Object>();
                e.put("path", i.path("path").asText());
                e.put("repo", i.path("repository").path("full_name").asText());
                e.put("url", i.path("html_url").asText());
                e.put("fragment", i.path("text_matches").toString());
                out.add(e);
            }
            return AdapterResponse.ok(out);
        });
    }

    private AdapterResponse createPr(Map<String, Object> args, String token) {
        var repo = requireString(args, "repo");
        var head = requireString(args, "head");
        var base = requireString(args, "base");
        var title = requireString(args, "title");
        if (repo == null) return AdapterResponse.fail("missing_arg", "repo required", false);
        if (head == null) return AdapterResponse.fail("missing_arg", "head required", false);
        if (base == null) return AdapterResponse.fail("missing_arg", "base required", false);
        if (title == null) return AdapterResponse.fail("missing_arg", "title required", false);

        var body = new LinkedHashMap<String, Object>();
        body.put("head", head);
        body.put("base", base);
        body.put("title", title);
        var description = (String) args.get("body");
        if (description != null) body.put("body", description);

        var rb = HttpRequest.newBuilder()
            .uri(uri("/repos/" + repo + "/pulls"))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .POST(jsonBody(body));
        return execute(rb, tree -> AdapterResponse.ok(Map.of(
            "number", tree.path("number").asLong(),
            "url", tree.path("html_url").asText())));
    }
}
