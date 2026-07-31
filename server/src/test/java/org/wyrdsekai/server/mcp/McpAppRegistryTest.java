package org.wyrdsekai.server.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpAppRegistryTest {

    private McpAppRegistry registry;

    @BeforeEach void setUp() {
        registry = new McpAppRegistry();
    }

    @Test void builtinApps_registered() {
        assertThat(registry.listApps()).hasSize(3);
    }

    @Test void getApp_room_map_exists() {
        var app = registry.getApp("room-map");
        assertThat(app).isPresent();
        assertThat(app.get().type()).isEqualTo(McpAppRegistry.AppType.MAP);
        assertThat(app.get().htmlContent()).contains("Zone Map");
    }

    @Test void getApp_economy_dashboard_exists() {
        var app = registry.getApp("economy-dashboard");
        assertThat(app).isPresent();
        assertThat(app.get().type()).isEqualTo(McpAppRegistry.AppType.DASHBOARD);
    }

    @Test void register_custom_app() {
        var app = new McpAppRegistry.McpApp("custom", "Custom app",
            "1.0", McpAppRegistry.AppType.INTERACTIVE,
            "<html>Custom</html>", McpAppRegistry.SandboxPolicy.standard());
        registry.register(app);
        assertThat(registry.listApps()).hasSize(4);
        assertThat(registry.getApp("custom")).isPresent();
    }

    @Test void sandboxPolicy_restrictive_defaults() {
        var policy = McpAppRegistry.SandboxPolicy.restrictive();
        assertThat(policy.allowScripts()).isTrue();
        assertThat(policy.allowForms()).isFalse();
        assertThat(policy.allowPopups()).isFalse();
        assertThat(policy.toIframeSandbox()).isEqualTo("allow-scripts");
    }

    @Test void sandboxPolicy_csp_header() {
        var policy = McpAppRegistry.SandboxPolicy.standard();
        assertThat(policy.toContentSecurityPolicy())
            .isEqualTo("sandbox allow-scripts allow-forms");
    }

    @Test void listAppsJson_returns_array() {
        var json = registry.listAppsJson();
        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get(0).get("name").asText()).isEqualTo("room-map");
    }
}
