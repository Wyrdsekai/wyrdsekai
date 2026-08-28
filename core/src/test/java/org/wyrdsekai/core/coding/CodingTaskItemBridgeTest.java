package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.codezaiku.CodeItemGenerator;
import org.wyrdsekai.core.codezaiku.CodeItemStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1c — verifies the generic event bridge {@link CodingTaskItemBridge}
 * routes ZoneBroadcast events to the right adapter, and that
 * {@link CodeZaikuEventAdapter} produces a {@link SourceArtifact} (with a
 * sibling {@link BuildArtifact} stashed under the {@code __sibling_build}
 * magic key) on a real {@code board_completed} event. Per
 * step 19.
 */
class CodingTaskItemBridgeTest {

    @TempDir Path tmp;

    private CodeItemStore store;
    private CodeItemGenerator generator;
    private BackendRegistry registry;
    private List<CodingTaskItemBridge.RoomItemPlacement> placements;
    private CodingTaskItemBridge bridge;

    @BeforeEach void setUp() {
        var dbPath = tmp.resolve("bridge.db");
        store = new CodeItemStore("jdbc:sqlite:" + dbPath.toAbsolutePath());
        generator = new CodeItemGenerator(store);
        registry = new BackendRegistry();
        registry.register(new CodeZaikuEventAdapter(generator));
        placements = new CopyOnWriteArrayList<>();
        bridge = new CodingTaskItemBridge(registry, placements::add);
    }

    @AfterEach void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── CodeZaiku translateEvent: SourceArtifact + sibling BuildArtifact ──

    @Test void board_completed_event_produces_source_and_build_artifacts() {
        bridge.accept(boardCompletedEvent("board-1",
            "/ws", "node-a", "Java",
            List.of("Main.java"), "did:key:alice",
            5, 1, "success"));

        // Bridge dropped a single placement holding both items.
        assertThat(placements).hasSize(1);
        var placement = placements.getFirst();
        assertThat(placement.roomId()).isEqualTo("workshop");
        assertThat(placement.objects()).hasSize(2);

        // The first object is the codex (SourceArtifact rendering),
        // the second is the artifact (BuildArtifact rendering).
        var ids = placement.objects().stream().map(o -> o.id()).toList();
        assertThat(ids).anySatisfy(id -> assertThat(id).startsWith("codex-"));
        assertThat(ids).anySatisfy(id -> assertThat(id).startsWith("artifact-"));
    }

    @Test void adapter_translate_event_returns_source_with_sibling_build() {
        // Direct adapter call (bypassing the bridge) — confirms the
        // __sibling_build magic key is exactly where the bridge expects it.
        var adapter = new CodeZaikuEventAdapter(generator);
        var artifact = adapter.translateEvent(boardCompletedEvent(
            "board-2", "/ws2", "node-b", "Python",
            List.of("a.py", "b.py"), "did:key:bob",
            3, 0, "success"));

        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        var src = (SourceArtifact) artifact;
        assertThat(src.backend()).isEqualTo(CodeZaikuBackend.NAME);
        assertThat(src.taskId()).isEqualTo("board-2");
        assertThat(src.workspacePath()).isEqualTo("/ws2");
        assertThat(src.files()).containsExactly("a.py", "b.py");

        // Sibling build under the magic key.
        var sibling = src.backendMetadata().get("__sibling_build");
        assertThat(sibling).isInstanceOf(BuildArtifact.class);
        var build = (BuildArtifact) sibling;
        assertThat(build.backend()).isEqualTo(CodeZaikuBackend.NAME);
        assertThat(build.testsPassed()).isEqualTo(3);
        assertThat(build.testsFailed()).isEqualTo(0);
        assertThat(build.status()).isEqualTo("success");
    }

    @Test void adapter_translate_without_build_status_omits_sibling_build() {
        var adapter = new CodeZaikuEventAdapter(generator);
        var artifact = adapter.translateEvent(boardCompletedEvent(
            "board-3", "/ws3", "node-c", "JavaScript",
            List.of("index.js"), "did:key:carol",
            null, null, /*buildStatus*/ null));

        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        var src = (SourceArtifact) artifact;
        assertThat(src.backendMetadata().get("__sibling_build")).isNull();
    }

    // ─── Adapter routing: unknown namespace dropped silently ──────────

    @Test void unknown_namespace_event_dropped_silently_no_placement() {
        // No adapter for "aider" namespace registered → bridge should drop.
        var event = new AgentEvent.ZoneBroadcast(
            "aider", "workshop",
            new S2CMessage.ZoneResponse(1L, "r1", "aider", "Aider event",
                Json.mapper().createObjectNode().put("event", "edit_done"),
                List.of()),
            Instant.now());

        bridge.accept(event);

        assertThat(placements).isEmpty();
    }

    @Test void event_for_namespace_that_returns_null_artifact_is_dropped() {
        // Register a stub adapter whose translateEvent always returns null
        // (e.g. a status ping that isn't artifact-bearing).
        registry.register(new BackendAdapter() {
            @Override public String namespace() { return "stubby"; }
            @Override public CodingArtifact translateEvent(
                    AgentEvent.ZoneBroadcast event) { return null; }
            @Override public TaskSpec parsePlayerCommand(
                    String command, String args) { return null; }
        });

        var event = new AgentEvent.ZoneBroadcast(
            "stubby", "workshop",
            new S2CMessage.ZoneResponse(1L, "r1", "stubby", "ping",
                Json.mapper().createObjectNode().put("event", "ping"),
                List.of()),
            Instant.now());
        bridge.accept(event);

        assertThat(placements).isEmpty();
    }

    @Test void two_adapters_route_independently() {
        // Register a second adapter under a different namespace that
        // produces a SourceArtifact unconditionally. Send one event for
        // each namespace; verify each lands on the correct adapter.
        var customRoom = "library";
        registry.register(new BackendAdapter() {
            @Override public String namespace() { return "custom"; }
            @Override public CodingArtifact translateEvent(
                    AgentEvent.ZoneBroadcast event) {
                return new SourceArtifact(UUID.randomUUID(), "custom",
                    "task-99", "/custom/ws",
                    List.of("custom.js"), null, Instant.now(),
                    Map.of("note", "from custom adapter"));
            }
            @Override public TaskSpec parsePlayerCommand(
                    String command, String args) { return null; }
        });

        // One event for codezaiku, one for custom — sequential.
        bridge.accept(boardCompletedEvent("board-routing-1", "/ws-cp",
            "n", "Java", List.of("X.java"), null, 0, 0, "untested"));
        bridge.accept(new AgentEvent.ZoneBroadcast(
            "custom", customRoom,
            new S2CMessage.ZoneResponse(2L, "r2", "custom", "custom event",
                Json.mapper().createObjectNode().put("event", "anything"),
                List.of()),
            Instant.now()));

        // Two placements, into the right rooms, from the right backends.
        assertThat(placements).hasSize(2);

        var first = placements.get(0);
        assertThat(first.roomId()).isEqualTo("workshop");
        assertThat(first.objects()).isNotEmpty();

        var second = placements.get(1);
        assertThat(second.roomId()).isEqualTo(customRoom);
        assertThat(second.objects()).hasSize(1);
    }

    @Test void non_zone_broadcast_events_are_ignored() {
        bridge.accept(new AgentEvent.SystemEvent(
            AgentEvent.SystemEventType.NODE_JOINED, "n", "joined",
            Instant.now()));
        bridge.accept(new AgentEvent.AdjacentActivity(
            "r", "Library", AgentEvent.ActivityType.SPEECH, 1,
            Instant.now()));
        assertThat(placements).isEmpty();
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private static AgentEvent.ZoneBroadcast boardCompletedEvent(
            String boardId, String workspace, String hostNode,
            String language, List<String> files, String createdBy,
            Integer testsPassed, Integer testsFailed, String buildStatus) {

        ObjectNode data = Json.mapper().createObjectNode();
        data.put("event", "board_completed");
        data.put("boardId", boardId);
        data.put("workspace", workspace);
        data.put("hostNode", hostNode);
        if (language != null) data.put("language", language);
        if (createdBy != null) data.put("createdBy", createdBy);
        if (buildStatus != null) data.put("buildStatus", buildStatus);
        if (testsPassed != null) data.put("testsPassed", testsPassed);
        if (testsFailed != null) data.put("testsFailed", testsFailed);

        ArrayNode filesArray = data.putArray("files");
        for (var f : files) filesArray.add(f);

        var zoneResponse = new S2CMessage.ZoneResponse(
            1L, "req-1", CodeZaikuBackend.NAME, "Board completed",
            data, List.of());
        return new AgentEvent.ZoneBroadcast(
            CodeZaikuBackend.NAME, "workshop", zoneResponse, Instant.now());
    }
}
