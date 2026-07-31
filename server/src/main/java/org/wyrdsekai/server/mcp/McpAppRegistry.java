package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP Apps registry (§81).
 * Manages interactive UI components that MCP clients can render.
 * Each app has a name, HTML/CSS/JS content, and sandbox policy.
 *
 * Apps are served as resources via Javalin static file handler
 * and referenced in MCP tool responses.
 */
public class McpAppRegistry {

    /** An MCP App definition. */
    public record McpApp(
        String name,
        String description,
        String version,
        AppType type,
        String htmlContent,
        SandboxPolicy sandbox
    ) {}

    /** Type of MCP App. */
    public enum AppType {
        DASHBOARD,    // Read-only visualization
        INTERACTIVE,  // Interactive UI with form inputs
        MAP,          // Spatial/room map
        MONITOR       // Real-time status monitor
    }

    /** Sandbox policy for iframe embedding. */
    public record SandboxPolicy(
        boolean allowScripts,
        boolean allowForms,
        boolean allowPopups,
        boolean allowSameOrigin,
        List<String> allowedOrigins
    ) {
        /** Restrictive default — scripts only, no forms/popups/same-origin. */
        public static SandboxPolicy restrictive() {
            return new SandboxPolicy(true, false, false, false, List.of());
        }

        /** Standard policy — scripts + forms, no popups. */
        public static SandboxPolicy standard() {
            return new SandboxPolicy(true, true, false, false, List.of());
        }

        /** Convert to CSP header value. */
        public String toContentSecurityPolicy() {
            var sb = new StringBuilder("sandbox");
            if (allowScripts) sb.append(" allow-scripts");
            if (allowForms) sb.append(" allow-forms");
            if (allowPopups) sb.append(" allow-popups");
            if (allowSameOrigin) sb.append(" allow-same-origin");
            return sb.toString();
        }

        /** Convert to iframe sandbox attribute. */
        public String toIframeSandbox() {
            var parts = new ArrayList<String>();
            if (allowScripts) parts.add("allow-scripts");
            if (allowForms) parts.add("allow-forms");
            if (allowPopups) parts.add("allow-popups");
            if (allowSameOrigin) parts.add("allow-same-origin");
            return String.join(" ", parts);
        }
    }

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, McpApp> apps = new LinkedHashMap<>();

    public McpAppRegistry() {
        registerBuiltinApps();
    }

    /** Register an app. */
    public void register(McpApp app) {
        apps.put(app.name(), app);
    }

    /** Get an app by name. */
    public Optional<McpApp> getApp(String name) {
        return Optional.ofNullable(apps.get(name));
    }

    /** List all registered apps. */
    public List<McpApp> listApps() {
        return new ArrayList<>(apps.values());
    }

    /** Generate app listing as JSON. */
    public JsonNode listAppsJson() {
        var array = mapper.createArrayNode();
        for (var app : apps.values()) {
            var node = mapper.createObjectNode();
            node.put("name", app.name());
            node.put("description", app.description());
            node.put("version", app.version());
            node.put("type", app.type().name().toLowerCase());
            array.add(node);
        }
        return array;
    }

    private void registerBuiltinApps() {
        // Room Map — spatial visualization of the zone
        register(new McpApp("room-map", "Interactive room map showing zone topology",
            "0.1.0", AppType.MAP,
            roomMapHtml(), SandboxPolicy.restrictive()));

        // Economy Dashboard — credit ledger and reputation
        register(new McpApp("economy-dashboard",
            "Economy overview: credit balances, reputation scores, recent trades",
            "0.1.0", AppType.DASHBOARD,
            economyDashboardHtml(), SandboxPolicy.restrictive()));

        // Agent Monitor — live agent vitality
        register(new McpApp("agent-monitor",
            "Real-time agent vitality and status monitor",
            "0.1.0", AppType.MONITOR,
            agentMonitorHtml(), SandboxPolicy.restrictive()));
    }

    private String roomMapHtml() {
        return """
            <!DOCTYPE html>
            <html><head><title>Room Map</title>
            <style>
              body { font-family: monospace; background: #1a1a2e; color: #e6e6e6; margin: 20px; }
              .room { display: inline-block; padding: 8px 12px; margin: 4px; border: 1px solid #4a4a6a;
                      border-radius: 4px; cursor: pointer; }
              .room:hover { background: #2a2a4e; }
              .room.current { border-color: #7b68ee; background: #2a2a4e; }
              .connection { stroke: #4a4a6a; stroke-width: 1; }
              h1 { color: #7b68ee; }
            </style></head>
            <body>
              <h1>Zone Map</h1>
              <div id="map">Loading room data...</div>
              <script>
                // In a live session, this fetches room data via MCP and renders the map
                document.getElementById('map').innerHTML = '<p>Connect via MCP to view live room topology.</p>';
              </script>
            </body></html>
            """;
    }

    private String economyDashboardHtml() {
        return """
            <!DOCTYPE html>
            <html><head><title>Economy Dashboard</title>
            <style>
              body { font-family: monospace; background: #1a1a2e; color: #e6e6e6; margin: 20px; }
              table { border-collapse: collapse; width: 100%; }
              th, td { border: 1px solid #4a4a6a; padding: 6px 10px; text-align: left; }
              th { background: #2a2a4e; }
              h1, h2 { color: #7b68ee; }
              .positive { color: #50fa7b; }
              .negative { color: #ff5555; }
            </style></head>
            <body>
              <h1>Economy Dashboard</h1>
              <div id="content">Loading economy data...</div>
              <script>
                document.getElementById('content').innerHTML = '<p>Connect via MCP to view live economy data.</p>';
              </script>
            </body></html>
            """;
    }

    private String agentMonitorHtml() {
        return """
            <!DOCTYPE html>
            <html><head><title>Agent Monitor</title>
            <style>
              body { font-family: monospace; background: #1a1a2e; color: #e6e6e6; margin: 20px; }
              .agent { border: 1px solid #4a4a6a; border-radius: 6px; padding: 12px; margin: 8px 0; }
              .tank { display: inline-block; width: 80px; height: 12px; background: #2a2a4e;
                      border-radius: 3px; margin: 2px 4px; vertical-align: middle; }
              .tank-fill { height: 100%; border-radius: 3px; }
              h1 { color: #7b68ee; }
              .label { display: inline-block; width: 100px; }
            </style></head>
            <body>
              <h1>Agent Monitor</h1>
              <div id="agents">Loading agent data...</div>
              <script>
                document.getElementById('agents').innerHTML = '<p>Connect via MCP to view live agent vitality.</p>';
              </script>
            </body></html>
            """;
    }
}
