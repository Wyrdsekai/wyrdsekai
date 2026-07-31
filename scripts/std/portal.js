// std/portal.js — Interface to external system.
// Wraps MCP endpoints, APIs, or external services as tangible world objects.
// Creator configures: source (URL, API name, or MCP endpoint), refresh mode.
// Override: connect() for custom data fetching, render() for custom display.

item._type = "portal";
item._source = "";              // URL, API name, or description
item._source_type = "web";      // "web", "mcp", "api"
item._refresh_mode = "on_use";  // "on_use", "periodic", "manual"
item._label = "portal";
item._last_data = null;

item.set_source = function(s) { item._source = s; };
item.set_source_type = function(t) { item._source_type = t; };
item.set_refresh = function(mode) { item._refresh_mode = mode; };
item.set_label = function(l) { item._label = l; };

function invoke(params) {
    var action = params.action || "view";

    if (action === "view" || action === "connect") {
        if (item._source_type === "web") {
            var content = world.web.fetch(item._source);
            if (content) {
                var summary = world.llm.summarize(content,
                    "Summarize the key information from this page.");
                item._last_data = summary;
                return { source: item._source, content: summary };
            }
            return { source: item._source, error: "Could not fetch content" };
        }

        if (item._source_type === "api") {
            // API portals use web search to find current data
            var results = world.web.search(item._source + " " + (params.query || ""));
            var snippets = [];
            for (var i = 0; i < Math.min(results.length, 3); i++) {
                snippets.push(results[i].snippet || results[i].title);
            }
            item._last_data = snippets.join("\n");
            return { source: item._source, results: snippets };
        }

        // MCP portals delegate to inventory (MCP items resolve through tool system)
        return {
            source: item._source,
            source_type: item._source_type,
            note: "MCP portal — invoke via tool calling"
        };
    }

    if (action === "refresh") {
        // Force refresh by re-invoking connect
        return invoke({ action: "connect", query: params.query });
    }

    if (action === "last") {
        // Return cached data without fetching
        if (item._last_data) {
            return { source: item._source, cached: true, content: item._last_data };
        }
        return { source: item._source, cached: false, note: "No cached data. Use view first." };
    }

    return { error: "Unknown action: " + action + ". Use view, refresh, or last." };
}
