package org.wyrdsekai.between.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.TopologyRegister;
import org.wyrdsekai.between.federation.FederationCouncil;
import org.wyrdsekai.between.traversal.BetweenTraversal;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BetweenMcpServerTest {

    private BetweenMcpServer server;
    private TopologyRegister topology;
    private BetweenTraversal traversal;
    private FederationCouncil council;

    @BeforeEach
    void setUp() {
        topology = new TopologyRegister();
        traversal = new BetweenTraversal();
        council = new FederationCouncil();
        server = new BetweenMcpServer(topology, traversal, council);
    }

    @Test void list_tools_returns_all() {
        var tools = server.listTools();
        assertThat(tools).hasSizeGreaterThanOrEqualTo(6);
        assertThat(tools.stream().map(BetweenMcpServer.ToolDef::name))
            .contains("between.topology", "between.traverse", "between.journey_status",
                "between.check_ban", "between.active_bans", "between.active_journeys");
    }

    @Test void tool_count() {
        assertThat(server.toolCount()).isEqualTo(6);
    }

    @Test void topology_tool() {
        var result = server.callTool("between.topology", Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("connectedNodes");
    }

    @Test void traverse_tool() {
        var result = server.callTool("between.traverse", Map.of(
            "agentId", "agent-1",
            "sourceZoneId", "zone-a",
            "targetZoneId", "zone-b",
            "latencyMs", "50.0"
        ));
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("journeyId");
        assertThat(result.data()).containsKey("narrative");
    }

    @Test void traverse_missing_params() {
        var result = server.callTool("between.traverse", Map.of("agentId", "agent-1"));
        assertThat(result.success()).isFalse();
    }

    @Test void traverse_invalid_latency() {
        var result = server.callTool("between.traverse", Map.of(
            "agentId", "agent-1",
            "sourceZoneId", "zone-a",
            "targetZoneId", "zone-b",
            "latencyMs", "not-a-number"
        ));
        assertThat(result.success()).isFalse();
    }

    @Test void journey_status_tool() {
        // First create a journey
        server.callTool("between.traverse", Map.of(
            "agentId", "agent-1",
            "sourceZoneId", "zone-a",
            "targetZoneId", "zone-b",
            "latencyMs", "50.0"
        ));

        var result = server.callTool("between.journey_status", Map.of("journeyId", "journey-1"));
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("status");
    }

    @Test void journey_status_not_found() {
        var result = server.callTool("between.journey_status", Map.of("journeyId", "nonexistent"));
        assertThat(result.success()).isFalse();
    }

    @Test void check_ban_clear() {
        var result = server.callTool("between.check_ban", Map.of("entityId", "entity-1"));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Clear");
    }

    @Test void check_ban_banned() {
        council.ban("entity-1", "zone-a", "spamming",
            FederationCouncil.BanScope.FEDERATION_WIDE, null);

        var result = server.callTool("between.check_ban", Map.of("entityId", "entity-1"));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("BANNED");
    }

    @Test void check_ban_with_zone() {
        council.ban("entity-1", "zone-a", "local ban",
            FederationCouncil.BanScope.ZONE_LOCAL, null);

        var result = server.callTool("between.check_ban", Map.of(
            "entityId", "entity-1",
            "zoneId", "zone-b"  // different zone, local ban shouldn't apply
        ));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Clear");
    }

    @Test void active_bans_tool() {
        council.ban("entity-1", "zone-a", "reason",
            FederationCouncil.BanScope.FEDERATION_WIDE, null);

        var result = server.callTool("between.active_bans", Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(1);
    }

    @Test void active_journeys_tool() {
        var result = server.callTool("between.active_journeys", Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(0);
    }

    @Test void unknown_tool_error() {
        var result = server.callTool("between.nonexistent", Map.of());
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("Unknown tool");
    }
}
