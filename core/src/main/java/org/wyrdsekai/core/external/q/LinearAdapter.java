package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linear adapter.
 *
 * <p>Linear's API is GraphQL-only. We expose the four most-needed verbs as
 * pre-baked queries: {@code list_issues}, {@code create_issue},
 * {@code update_issue}, {@code comment}. Items needing other queries can
 * declare {@code linear.*} and use the workshop room — but for the common
 * ledger we provide structured shortcuts.</p>
 */
public final class LinearAdapter extends AbstractHttpAdapter {

    private static final String ENDPOINT = "https://api.linear.app/graphql";

    @Override public String namespace() { return "linear"; }

    @Override public Set<String> capabilities() {
        return Set.of("list_issues", "create_issue", "update_issue", "comment");
    }

    @Override public String credentialSlot() { return "linear.api_key"; }

    @Override protected List<String> defaultDomains() {
        return List.of("api.linear.app");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var key = resolveCredential();
        if (key.isEmpty()) return missingCredentials();
        var headers = Map.of(
            "Authorization", key.get(),
            "Accept", "application/json"
        );
        return switch (req.method()) {
            case "list_issues" -> listIssues(req, headers);
            case "create_issue" -> createIssue(req, headers);
            case "update_issue" -> updateIssue(req, headers);
            case "comment" -> comment(req, headers);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse listIssues(AdapterRequest req, Map<String, String> headers) {
        var first = req.args().getOrDefault("max", 50);
        var query = "query($first:Int){issues(first:$first){nodes{id identifier title state{name} priority url}}}";
        var body = Map.of(
            "query", query,
            "variables", Map.of("first", first)
        );
        return httpPostJson(ENDPOINT, headers, body);
    }

    private AdapterResponse createIssue(AdapterRequest req, Map<String, String> headers) {
        var team = requireString(req, "team");
        var title = requireString(req, "title");
        if (team == null || title == null) {
            return AdapterResponse.fail("missing_arg", "team + title required", false);
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("teamId", team);
        input.put("title", title);
        if (req.args().get("body") != null) input.put("description", req.args().get("body"));
        if (req.args().get("priority") != null) input.put("priority", req.args().get("priority"));
        if (req.args().get("assignee") != null) input.put("assigneeId", req.args().get("assignee"));
        var query = "mutation($input:IssueCreateInput!){issueCreate(input:$input){success issue{id identifier url}}}";
        var body = Map.of("query", query, "variables", Map.of("input", input));
        return httpPostJson(ENDPOINT, headers, body);
    }

    private AdapterResponse updateIssue(AdapterRequest req, Map<String, String> headers) {
        var id = requireString(req, "id");
        if (id == null) return AdapterResponse.fail("missing_arg", "id required", false);
        var input = new LinkedHashMap<>(req.args());
        input.remove("id");
        var query = "mutation($id:String!,$input:IssueUpdateInput!){issueUpdate(id:$id,input:$input){success}}";
        var body = Map.of("query", query, "variables", Map.of("id", id, "input", input));
        return httpPostJson(ENDPOINT, headers, body);
    }

    private AdapterResponse comment(AdapterRequest req, Map<String, String> headers) {
        var issueId = requireString(req, "issueId");
        var text = requireString(req, "body");
        if (issueId == null || text == null) {
            return AdapterResponse.fail("missing_arg", "issueId + body required", false);
        }
        var query = "mutation($input:CommentCreateInput!){commentCreate(input:$input){success comment{id}}}";
        var body = Map.of(
            "query", query,
            "variables", Map.of("input", Map.of("issueId", issueId, "body", text))
        );
        return httpPostJson(ENDPOINT, headers, body);
    }
}
