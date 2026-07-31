package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Todoist adapter.
 *
 * <p>Exposes {@code world.todoist.{list, add, complete}} via Todoist REST v2.</p>
 */
public final class TodoistAdapter extends AbstractHttpAdapter {

    private static final String BASE = "https://api.todoist.com/rest/v2";

    @Override public String namespace() { return "todoist"; }

    @Override public Set<String> capabilities() {
        return Set.of("list", "add", "complete");
    }

    @Override public String credentialSlot() { return "todoist.api_token"; }

    @Override protected List<String> defaultDomains() {
        return List.of("api.todoist.com");
    }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = resolveCredential();
        if (token.isEmpty()) return missingCredentials();
        var headers = Map.of(
            "Authorization", "Bearer " + token.get(),
            "Accept", "application/json"
        );
        return switch (req.method()) {
            case "list" -> list(req, headers);
            case "add" -> add(req, headers);
            case "complete" -> complete(req, headers);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse list(AdapterRequest req, Map<String, String> headers) {
        var params = new LinkedHashMap<String, Object>();
        if (req.args().get("project") != null) params.put("project_id", req.args().get("project"));
        if (req.args().get("filter") != null) params.put("filter", req.args().get("filter"));
        return httpGetJson(BASE + "/tasks", headers, params);
    }

    private AdapterResponse add(AdapterRequest req, Map<String, String> headers) {
        var content = requireString(req, "content");
        if (content == null) return AdapterResponse.fail("missing_arg", "content required", false);
        var body = new LinkedHashMap<String, Object>();
        body.put("content", content);
        if (req.args().get("project") != null) body.put("project_id", req.args().get("project"));
        if (req.args().get("due") != null) body.put("due_string", req.args().get("due"));
        if (req.args().get("priority") != null) body.put("priority", req.args().get("priority"));
        if (req.args().get("labels") != null) body.put("labels", req.args().get("labels"));
        return httpPostJson(BASE + "/tasks", headers, body);
    }

    private AdapterResponse complete(AdapterRequest req, Map<String, String> headers) {
        var taskId = requireString(req, "taskId");
        if (taskId == null) return AdapterResponse.fail("missing_arg", "taskId required", false);
        return httpPostJson(BASE + "/tasks/" + taskId + "/close", headers, Map.of());
    }
}
