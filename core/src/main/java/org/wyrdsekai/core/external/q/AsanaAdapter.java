package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asana adapter.
 *
 * <p>Exposes {@code world.asana.{list_tasks, create_task, update_task}}.
 * Asana uses a personal-access-token bearer auth.</p>
 */
public final class AsanaAdapter extends AbstractHttpAdapter {

    private static final String BASE = "https://app.asana.com/api/1.0";

    @Override public String namespace() { return "asana"; }

    @Override public Set<String> capabilities() {
        return Set.of("list_tasks", "create_task", "update_task");
    }

    @Override public String credentialSlot() { return "asana.token"; }

    @Override protected List<String> defaultDomains() {
        return List.of("app.asana.com");
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
            case "list_tasks" -> listTasks(req, headers);
            case "create_task" -> createTask(req, headers);
            case "update_task" -> updateTask(req, headers);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse listTasks(AdapterRequest req, Map<String, String> headers) {
        var params = new LinkedHashMap<String, Object>();
        if (req.args().get("project") != null) params.put("project", req.args().get("project"));
        if (req.args().get("assignee") != null) params.put("assignee", req.args().get("assignee"));
        if (req.args().get("workspace") != null) params.put("workspace", req.args().get("workspace"));
        return httpGetJson(BASE + "/tasks", headers, params);
    }

    private AdapterResponse createTask(AdapterRequest req, Map<String, String> headers) {
        var name = requireString(req, "name");
        if (name == null) return AdapterResponse.fail("missing_arg", "name required", false);
        var data = new LinkedHashMap<String, Object>();
        data.put("name", name);
        if (req.args().get("project") != null) data.put("projects", List.of(req.args().get("project")));
        if (req.args().get("notes") != null) data.put("notes", req.args().get("notes"));
        if (req.args().get("assignee") != null) data.put("assignee", req.args().get("assignee"));
        if (req.args().get("due") != null) data.put("due_on", req.args().get("due"));
        return httpPostJson(BASE + "/tasks", headers, Map.of("data", data));
    }

    private AdapterResponse updateTask(AdapterRequest req, Map<String, String> headers) {
        var taskId = requireString(req, "taskId");
        if (taskId == null) return AdapterResponse.fail("missing_arg", "taskId required", false);
        var data = new LinkedHashMap<>(req.args());
        data.remove("taskId");
        return httpPatchJson(BASE + "/tasks/" + taskId, headers, Map.of("data", data));
    }
}
