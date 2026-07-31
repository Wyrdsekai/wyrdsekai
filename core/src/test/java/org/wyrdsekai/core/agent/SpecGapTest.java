package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;
import org.wyrdsekai.core.item.EquipmentService;
import org.wyrdsekai.core.mcp.ProcessMcpProvisioner;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all 10 spec gap implementations.
 */
class SpecGapTest {

    // ── #2 Ward command parsing ──

    @Test void parse_grant_ward() {
        var cmd = CommandParser.parse("grant ward ember");
        assertInstanceOf(ParsedCommand.GrantWard.class, cmd);
        assertEquals("ember", ((ParsedCommand.GrantWard) cmd).agentName());
    }

    @Test void parse_revoke_ward() {
        var cmd = CommandParser.parse("revoke ward ember");
        assertInstanceOf(ParsedCommand.RevokeWard.class, cmd);
        assertEquals("ember", ((ParsedCommand.RevokeWard) cmd).agentName());
    }

    @Test void parse_invite() {
        var cmd = CommandParser.parse("invite claude");
        assertInstanceOf(ParsedCommand.Invite.class, cmd);
        assertEquals("claude", ((ParsedCommand.Invite) cmd).agentName());
    }

    @Test void parse_dismiss() {
        var cmd = CommandParser.parse("dismiss ember");
        assertInstanceOf(ParsedCommand.Dismiss.class, cmd);
        assertEquals("ember", ((ParsedCommand.Dismiss) cmd).agentName());
    }

    @Test void grant_ward_requires_agent_name() {
        var cmd = CommandParser.parse("grant ward");
        // "grant ward" with no agent name — won't match (words.length < 3)
        assertFalse(cmd instanceof ParsedCommand.GrantWard, "grant ward without name should not parse as GrantWard");
    }

    // ── #10 ProcessMcpProvisioner ──

    @Test void process_provisioner_list_empty() {
        var provisioner = new ProcessMcpProvisioner();
        assertTrue(provisioner.list().isEmpty());
    }

    @Test void process_provisioner_deprovision_unknown() {
        var provisioner = new ProcessMcpProvisioner();
        assertFalse(provisioner.deprovision("nonexistent"));
    }

    @Test void process_provisioner_health_unknown() {
        var provisioner = new ProcessMcpProvisioner();
        assertFalse(provisioner.isHealthy("nonexistent"));
    }

    // ── #13 AgentSupervisor ──

    @Test void agent_supervisor_register_and_query() {
        var supervisor = new AgentSupervisor();
        supervisor.register("agent-1", "Ember", "nexus");
        supervisor.register("agent-2", "Wyrd", "nexus");

        assertEquals(2, supervisor.agentCount());
        assertNotNull(supervisor.getAgent("agent-1"));
        assertEquals("Ember", supervisor.getAgent("agent-1").agentName());
        assertEquals("nexus", supervisor.getAgent("agent-1").roomId());
    }

    @Test void agent_supervisor_unregister() {
        var supervisor = new AgentSupervisor();
        supervisor.register("agent-1", "Ember", "nexus");
        supervisor.unregister("agent-1");
        assertEquals(0, supervisor.agentCount());
        assertNull(supervisor.getAgent("agent-1"));
    }

    @Test void agent_supervisor_update_state() {
        var supervisor = new AgentSupervisor();
        supervisor.register("agent-1", "Ember", "nexus");
        supervisor.updateState("agent-1", AgentSupervisor.AgentState.THINKING);
        assertEquals(AgentSupervisor.AgentState.THINKING, supervisor.getAgent("agent-1").state());
    }

    @Test void agent_supervisor_update_room() {
        var supervisor = new AgentSupervisor();
        supervisor.register("agent-1", "Ember", "nexus");
        supervisor.updateRoom("agent-1", "library");
        assertEquals("library", supervisor.getAgent("agent-1").roomId());
    }

    @Test void agent_supervisor_agents_in_room() {
        var supervisor = new AgentSupervisor();
        supervisor.register("agent-1", "Ember", "nexus");
        supervisor.register("agent-2", "Wyrd", "nexus");
        supervisor.register("agent-3", "Claude", "library");

        var inNexus = supervisor.agentsInRoom("nexus");
        assertEquals(2, inNexus.size());
        assertTrue(inNexus.contains("agent-1"));
        assertTrue(inNexus.contains("agent-2"));
    }

    // ── #12 EquipmentService ward ──

    @Test void equip_ward_and_query() {
        var service = new EquipmentService();
        boolean equipped = service.equipWard("agent-1", "ward-study-123",
            "Study Ward (mas)", "study-ward",
            "Access to mas's Study [study-123]",
            "carrying a warm crystal ward");
        assertTrue(equipped);

        var items = service.getEquipped("agent-1");
        assertEquals(1, items.size());
        assertEquals("Study Ward (mas)", items.getFirst().label());
        assertEquals("study-ward", items.getFirst().slotHint());
        assertTrue(items.getFirst().promptOverlay().contains("study-123"));
    }

    @Test void equip_ward_no_duplicate() {
        var service = new EquipmentService();
        service.equipWard("agent-1", "ward-123", "Ward", "study-ward", "overlay", "appearance");
        boolean second = service.equipWard("agent-1", "ward-123", "Ward", "study-ward", "overlay", "appearance");
        assertFalse(second, "Duplicate ward should not be equipped");
    }

    // ── #8 Bi-temporal Lucene ──

    @Test void bi_temporal_fragment_insert_compiles() {
        // Verify the method signature exists (compile-time check)
        // Actual Lucene test requires store initialization — covered in LuceneSearchE2ETest
        assertDoesNotThrow(() -> {
            // Method exists with temporal parameters
            var method = WyrdLuceneStore.class.getMethod(
                "insertFragment", String.class, String.class, String.class,
                String.class, List.class,
                long.class, float.class, long.class, long.class, String.class);
            assertNotNull(method);
        });
    }
}
