package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorldApiTest {

    @Test void getRoomId_returns_configured_id() {
        var api = new WorldApi("nexus");
        assertThat(api.getRoomId()).isEqualTo("nexus");
    }

    @Test void emit_fires_event_callback() {
        var api = new WorldApi("test-room");
        var received = new ArrayList<String>();
        api.onEvent((type, data) -> received.add(type));

        api.emit("narrate", Map.of("text", "Hello"));
        assertThat(received).containsExactly("narrate");
    }

    @Test void scheduleTimer_stores_request() {
        var api = new WorldApi("test-room");
        api.scheduleTimer("patrol", 60, "onTimer");

        var requests = api.consumeTimerRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().timerId()).isEqualTo("patrol");
        assertThat(requests.getFirst().intervalSeconds()).isEqualTo(60);
        assertThat(requests.getFirst().hookName()).isEqualTo("onTimer");
    }

    @Test void consumeTimerRequests_clears_after_consumption() {
        var api = new WorldApi("test-room");
        api.scheduleTimer("timer1", 30, "onTick");

        assertThat(api.consumeTimerRequests()).hasSize(1);
        assertThat(api.consumeTimerRequests()).isEmpty();
    }

    @Test void scheduleTimer_clamps_interval() {
        var api = new WorldApi("test-room");
        api.scheduleTimer("tiny", 0, "onTick"); // too small → 1
        api.scheduleTimer("huge", 99999, "onTick"); // too large → 3600

        var requests = api.consumeTimerRequests();
        assertThat(requests.get(0).intervalSeconds()).isEqualTo(1);
        assertThat(requests.get(1).intervalSeconds()).isEqualTo(3600);
    }

    @Test void cancelTimer_emits_event() {
        var api = new WorldApi("test-room");
        var events = new ArrayList<String>();
        api.onEvent((type, data) -> events.add(type));

        api.cancelTimer("timer1");
        assertThat(events).containsExactly("timer_cancelled");
    }

    @Test void getAdjacentSummary_without_bridge_returns_fallback() {
        var api = new WorldApi("nexus");
        assertThat(api.getAdjacentSummary()).contains("No adjacent room data");
    }

    @Test void getAdjacentSummary_with_bridge_returns_data() {
        var api = new WorldApi("nexus");
        api.setBridgeDataProvider(new StubBridgeDataProvider());
        assertThat(api.getAdjacentSummary()).contains("stub adjacent");
    }

    @Test void getSystemMetrics_returns_non_empty() {
        var api = new WorldApi("boiler-room");
        var metrics = api.getSystemMetrics();
        assertThat(metrics).contains("Heap:");
        assertThat(metrics).contains("Processors:");
        assertThat(metrics).contains("Uptime:");
    }

    // --- Wave 3A: New WorldApi methods ---

    @Test void getEntities_returns_added_entities() {
        var api = new WorldApi("test-room");
        api.addEntity("p1", "Alice", "player");
        api.addEntity("a1", "Bob", "agent");

        var entities = api.getEntities();
        assertThat(entities).hasSize(2);
        assertThat(entities.stream().map(m -> m.get("name")).toList())
            .containsExactlyInAnyOrder("Alice", "Bob");
    }

    @Test void getObjects_returns_added_objects() {
        var api = new WorldApi("test-room");
        api.addObject("obj1", "Sword", "A rusty sword");
        api.addObject("obj2", "Shield", "A wooden shield");

        var objects = api.getObjects();
        assertThat(objects).hasSize(2);
        assertThat(objects.stream().map(m -> m.get("name")).toList())
            .containsExactlyInAnyOrder("Sword", "Shield");
    }

    @Test void createObject_emits_object_added_event() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<Map.Entry<String, Map<String, Object>>>();
        api.onEvent((type, data) -> emissions.add(Map.entry(type, data)));

        api.createObject("key1", "Golden Key", "Opens the gate", false);
        assertThat(emissions).hasSize(1);
        assertThat(emissions.getFirst().getKey()).isEqualTo("object_added");
        assertThat(emissions.getFirst().getValue().get("objectId")).isEqualTo("key1");
        assertThat(emissions.getFirst().getValue().get("objectName")).isEqualTo("Golden Key");
    }

    @Test void createObject_rejects_blank_id() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<String>();
        api.onEvent((type, data) -> emissions.add(type));

        api.createObject("", "Name", "Desc", true);
        assertThat(emissions).isEmpty();
    }

    @Test void removeObject_emits_event() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<String>();
        api.onEvent((type, data) -> emissions.add(type));

        api.removeObject("obj1");
        assertThat(emissions).containsExactly("object_removed");
    }

    @Test void removeEntity_emits_event() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<String>();
        api.onEvent((type, data) -> emissions.add(type));

        api.removeEntity("npc1");
        assertThat(emissions).containsExactly("entity_removed");
    }

    @Test void setProperty_emits_property_changed() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<Map.Entry<String, Map<String, Object>>>();
        api.onEvent((type, data) -> emissions.add(Map.entry(type, data)));

        api.setProperty("light", "dim");
        assertThat(emissions).hasSize(1);
        assertThat(emissions.getFirst().getKey()).isEqualTo("property_changed");
        assertThat(emissions.getFirst().getValue().get("key")).isEqualTo("light");
        assertThat(emissions.getFirst().getValue().get("value")).isEqualTo("dim");
    }

    @Test void getProperty_returns_value() {
        var api = new WorldApi("test-room");
        api.setProperties(Map.of("light", "bright", "mood", "cheerful"));

        assertThat(api.getProperty("light")).isEqualTo("bright");
        assertThat(api.getProperty("mood")).isEqualTo("cheerful");
        assertThat(api.getProperty("missing")).isNull();
    }

    @Test void lockExit_emits_event() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<String>();
        api.onEvent((type, data) -> emissions.add(type));

        api.lockExit("north");
        assertThat(emissions).containsExactly("exit_locked");
    }

    @Test void unlockExit_emits_event() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<String>();
        api.onEvent((type, data) -> emissions.add(type));

        api.unlockExit("north");
        assertThat(emissions).containsExactly("exit_unlocked");
    }

    @Test void random_returns_within_range() {
        var api = new WorldApi("test-room");
        for (int i = 0; i < 100; i++) {
            var val = api.random(6);
            assertThat(val).isBetween(0, 5);
        }
        assertThat(api.random(0)).isEqualTo(0);
    }

    @Test void findEntity_returns_match() {
        var api = new WorldApi("test-room");
        api.addEntity("p1", "Alice", "player");

        var found = api.findEntity("p1");
        assertThat(found).isNotNull();
        assertThat(found.get("name")).isEqualTo("Alice");
        assertThat(found.get("type")).isEqualTo("player");

        assertThat(api.findEntity("ghost")).isNull();
    }

    @Test void findObject_returns_match() {
        var api = new WorldApi("test-room");
        api.addObject("obj1", "Sword", "A rusty sword");

        var found = api.findObject("obj1");
        assertThat(found).isNotNull();
        assertThat(found.get("name")).isEqualTo("Sword");

        assertThat(api.findObject("ghost")).isNull();
    }

    @Test void lockExit_rejects_blank_direction() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<String>();
        api.onEvent((type, data) -> emissions.add(type));

        api.lockExit("");
        assertThat(emissions).isEmpty();
    }

    @Test void setProperty_rejects_blank_key() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<String>();
        api.onEvent((type, data) -> emissions.add(type));

        api.setProperty("", "value");
        assertThat(emissions).isEmpty();
    }

    @Test void removeEntity_rejects_blank() {
        var api = new WorldApi("test-room");
        var emissions = new ArrayList<String>();
        api.onEvent((type, data) -> emissions.add(type));

        api.removeEntity("");
        assertThat(emissions).isEmpty();
    }

    // --- MCP Gateway (§86.1) ---

    @Test void mcp_returns_unavailable_without_provider() {
        var api = new WorldApi("docks");
        var result = api.mcp("searxng", "search", Map.of("q", "hello"));
        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("not available");
    }

    @Test void mcp_returns_result_with_provider() {
        var api = new WorldApi("docks");
        api.setMcpGatewayProvider(new StubMcpGatewayProvider());
        var result = api.mcp("searxng", "search", Map.of("q", "hello"));
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("data")).isEqualTo("stub result");
    }

    @Test void mcp_uses_current_entity_as_agent() {
        var api = new WorldApi("docks");
        var provider = new StubMcpGatewayProvider();
        api.setMcpGatewayProvider(provider);
        api.setCurrentEntityId("agent-42");
        api.setZoneId("zone-1");

        api.mcp("searxng", "search", Map.of());
        assertThat(provider.lastAgentId).isEqualTo("agent-42");
        assertThat(provider.lastZoneId).isEqualTo("zone-1");
    }

    @Test void mcp_falls_back_to_room_id_when_no_entity() {
        var api = new WorldApi("docks");
        var provider = new StubMcpGatewayProvider();
        api.setMcpGatewayProvider(provider);

        api.mcp("searxng", "search", Map.of());
        assertThat(provider.lastAgentId).isEqualTo("docks");
        assertThat(provider.lastZoneId).isEqualTo("default");
    }

    @Test void mcp_rejects_blank_service() {
        var api = new WorldApi("docks");
        api.setMcpGatewayProvider(new StubMcpGatewayProvider());
        var result = api.mcp("", "search", Map.of());
        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("Missing service");
    }

    @Test void mcp_rejects_blank_tool() {
        var api = new WorldApi("docks");
        api.setMcpGatewayProvider(new StubMcpGatewayProvider());
        var result = api.mcp("searxng", "", Map.of());
        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("Missing tool");
    }

    @Test void mcpAvailable_without_provider_returns_false() {
        var api = new WorldApi("docks");
        assertThat(api.mcpAvailable("searxng")).isFalse();
    }

    @Test void mcpAvailable_delegates_to_provider() {
        var api = new WorldApi("docks");
        api.setMcpGatewayProvider(new StubMcpGatewayProvider());
        assertThat(api.mcpAvailable("searxng")).isTrue();
        assertThat(api.mcpAvailable("nonexistent")).isFalse();
    }

    @Test void mcpBudget_without_provider_returns_zero() {
        var api = new WorldApi("docks");
        assertThat(api.mcpBudget("agent-1", "searxng")).isEqualTo(0);
    }

    @Test void mcpBudget_delegates_to_provider() {
        var api = new WorldApi("docks");
        api.setMcpGatewayProvider(new StubMcpGatewayProvider());
        assertThat(api.mcpBudget("agent-1", "searxng")).isEqualTo(10);
    }

    // --- Voice profile (Study furnishing #416) ---

    @Test void formatVoiceProfile_outside_study_is_blocked() {
        var api = new WorldApi("nexus");
        api.setBridgeDataProvider(new VoiceCapableStubBridge());
        api.setCurrentEntityId("did:key:user-1");
        assertThat(api.formatVoiceProfile()).contains("only available from The Study");
    }

    @Test void formatVoiceProfile_in_study_delegates_to_bridge() {
        var api = new WorldApi("study");
        api.setBridgeDataProvider(new VoiceCapableStubBridge());
        api.setCurrentEntityId("did:key:user-1");
        assertThat(api.formatVoiceProfile()).contains("voice-stub");
    }

    @Test void setVoiceClause_in_study_delegates_to_bridge() {
        var api = new WorldApi("study");
        api.setBridgeDataProvider(new VoiceCapableStubBridge());
        api.setCurrentEntityId("did:key:user-1");
        assertThat(api.setVoiceClause("greeting-tone", "warm, brief", "test"))
            .contains("set greeting-tone");
    }

    @Test void setVoiceClause_outside_study_is_blocked() {
        var api = new WorldApi("nexus");
        api.setBridgeDataProvider(new VoiceCapableStubBridge());
        api.setCurrentEntityId("did:key:user-1");
        assertThat(api.setVoiceClause("k", "v", "r"))
            .contains("only available from The Study");
    }

    @Test void revertVoice_in_study_delegates_to_bridge() {
        var api = new WorldApi("study");
        api.setBridgeDataProvider(new VoiceCapableStubBridge());
        api.setCurrentEntityId("did:key:user-1");
        assertThat(api.revertVoice(2)).contains("reverted to 2");
    }

    /** Stub bridge that records voice-method calls. */
    private static class VoiceCapableStubBridge implements BridgeDataProvider {
        @Override public String formatRoomList() { return ""; }
        @Override public String formatWards(String roomId) { return ""; }
        @Override public String formatGrant(String r, String p, String perm) { return ""; }
        @Override public String formatRevoke(String r, String p, String perm) { return ""; }
        @Override public int roomCount() { return 0; }
        @Override public int userCount() { return 0; }
        @Override public int wardCount() { return 0; }
        @Override public String formatAdjacentSummary(String roomId) { return ""; }
        @Override public String formatVoiceProfile(String userDid) { return "voice-stub for " + userDid; }
        @Override public String formatVoiceHistory(String userDid) { return "history-stub"; }
        @Override public String setVoiceClause(String userDid, String key, String value, String reason, String author) {
            return "set " + key;
        }
        @Override public String unsetVoiceClause(String userDid, String key, String reason, String author) {
            return "unset " + key;
        }
        @Override public String freezeVoice(String userDid, String reason, String author) { return "frozen"; }
        @Override public String unfreezeVoice(String userDid, String reason, String author) { return "unfrozen"; }
        @Override public String revertVoice(String userDid, int targetRevision, String author) {
            return "reverted to " + targetRevision;
        }
    }

    /** Minimal stub for testing bridge-dependent methods. */
    private static class StubBridgeDataProvider implements BridgeDataProvider {
        @Override public String formatRoomList() { return ""; }
        @Override public String formatWards(String roomId) { return ""; }
        @Override public String formatGrant(String r, String p, String perm) { return ""; }
        @Override public String formatRevoke(String r, String p, String perm) { return ""; }
        @Override public int roomCount() { return 0; }
        @Override public int userCount() { return 0; }
        @Override public int wardCount() { return 0; }
        @Override public String formatAdjacentSummary(String roomId) {
            return "stub adjacent rooms for " + roomId;
        }
    }

    /** Stub MCP gateway for testing world.mcp() methods. */
    private static class StubMcpGatewayProvider implements McpGatewayProvider {
        String lastAgentId;
        String lastZoneId;

        @Override
        public Map<String, Object> execute(String agentId, String zoneId,
                                            String serviceId, String toolName,
                                            Map<String, Object> params) {
            this.lastAgentId = agentId;
            this.lastZoneId = zoneId;
            return Map.of("success", true, "data", "stub result",
                "serviceId", serviceId, "toolName", toolName);
        }

        @Override
        public boolean isAvailable(String serviceId) {
            return "searxng".equals(serviceId);
        }

        @Override
        public int remainingBudget(String agentId, String serviceId) {
            return 10;
        }
    }
}
