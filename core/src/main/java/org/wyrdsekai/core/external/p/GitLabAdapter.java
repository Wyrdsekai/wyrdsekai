package org.wyrdsekai.core.external.p;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GitLab REST API adapter.
 *
 * <p>Methods (Phase P scope): {@code create_issue}, {@code comment} (note),
 * {@code list_mrs}.</p>
 *
 * <p>The {@code project} arg is the GitLab project identifier — either the
 * numeric ID or the URL-encoded {@code namespace/name} pair.</p>
 */
public final class GitLabAdapter extends BaseHttpAdapter {

    private static final String DEFAULT_BASE = "https://gitlab.com";

    @Override public String namespace() { return "gitlab"; }

    @Override public Set<String> capabilities() {
        return Set.of("create_issue", "comment", "list_mrs");
    }

    @Override public String credentialSlot() { return "gitlab.token"; }

    @Override protected String defaultBaseUrl() { return DEFAULT_BASE; }

    @Override public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return credentialsMissing();
        var args = req.args();
        return switch (req.method()) {
            case "create_issue" -> createIssue(args, token.get());
            case "comment" -> addComment(args, token.get());
            case "list_mrs" -> listMrs(args, token.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private static String projectPath(String project) {
        // numeric → use as-is; named → URL-encode the namespace/name pair.
        if (project.matches("\\d+")) return project;
        return urlEncode(project);
    }

    private AdapterResponse createIssue(Map<String, Object> args, String token) {
        var project = requireString(args, "project");
        var title = requireString(args, "title");
        if (project == null) return AdapterResponse.fail("missing_arg", "project required", false);
        if (title == null) return AdapterResponse.fail("missing_arg", "title required", false);
        var body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        var description = (String) args.get("body");
        if (description != null) body.put("description", description);

        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/v4/projects/" + projectPath(project) + "/issues"))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body));
        return execute(rb, tree -> AdapterResponse.ok(Map.of(
            "iid", tree.path("iid").asLong(),
            "url", tree.path("web_url").asText())));
    }

    private AdapterResponse addComment(Map<String, Object> args, String token) {
        var project = requireString(args, "project");
        var iid = requireString(args, "issueIid");
        var body = requireString(args, "body");
        if (project == null) return AdapterResponse.fail("missing_arg", "project required", false);
        if (iid == null) return AdapterResponse.fail("missing_arg", "issueIid required", false);
        if (body == null) return AdapterResponse.fail("missing_arg", "body required", false);

        var payload = Map.<String, Object>of("body", body);
        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/v4/projects/" + projectPath(project)
                + "/issues/" + urlEncode(iid) + "/notes"))
            .header("PRIVATE-TOKEN", token)
            .header("Content-Type", "application/json")
            .POST(jsonBody(payload));
        return execute(rb, tree -> AdapterResponse.ok(Map.of(
            "noteId", tree.path("id").asLong())));
    }

    private AdapterResponse listMrs(Map<String, Object> args, String token) {
        var project = requireString(args, "project");
        if (project == null) return AdapterResponse.fail("missing_arg", "project required", false);
        var state = optString(args, "state", "opened");
        var rb = HttpRequest.newBuilder()
            .uri(uri("/api/v4/projects/" + projectPath(project)
                + "/merge_requests?state=" + urlEncode(state)))
            .header("PRIVATE-TOKEN", token)
            .GET();
        return execute(rb, tree -> {
            var out = new ArrayList<Map<String, Object>>();
            for (var mr : tree) {
                var e = new LinkedHashMap<String, Object>();
                e.put("iid", mr.path("iid").asLong());
                e.put("title", mr.path("title").asText());
                e.put("state", mr.path("state").asText());
                e.put("url", mr.path("web_url").asText());
                e.put("author", mr.path("author").path("username").asText());
                out.add(e);
            }
            return AdapterResponse.ok(out);
        });
    }
}
