package org.wyrdsekai.core.codezaiku;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.ActionParser.AgentAction;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.coding.CodeZaikuBackend;
import org.wyrdsekai.core.coding.CodeZaikuEventAdapter;
import org.wyrdsekai.core.coding.CodingTaskItemBridge;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Code Items pipeline.
 *
 * <p>Post-Phase-2 cleanup: the legacy {@code CodeZaikuItemBridge} +
 * {@code CodexItem} / {@code ArtifactItem} shims are gone. The full
 * pipeline now runs through the generic {@link CodingTaskItemBridge} +
 * {@link CodeZaikuEventAdapter} pair against {@link CodeItemStore} and
 * the backend-agnostic {@link org.wyrdsekai.core.coding.SourceArtifact} /
 * {@link org.wyrdsekai.core.coding.BuildArtifact} records.</p>
 */
class CodeItemsIntegrationTest {

    private CodeItemStore store;
    private CodeItemGenerator generator;
    private List<CodingTaskItemBridge.RoomItemPlacement> placements;
    private CodingTaskItemBridge bridge;

    @TempDir Path tempDir;

    @BeforeEach void setUp() {
        var dbPath = tempDir.resolve("integration-test.db");
        store = new CodeItemStore("jdbc:sqlite:" + dbPath.toAbsolutePath());
        generator = new CodeItemGenerator(store);
        placements = new CopyOnWriteArrayList<>();
        var registry = new BackendRegistry();
        registry.register(new CodeZaikuEventAdapter(generator));
        bridge = new CodingTaskItemBridge(registry, placements::add);
    }

    // --- Helper methods ---

    /** Build a ZoneBroadcast with a board_completed ZoneResponse. */
    private AgentEvent.ZoneBroadcast boardCompletionEvent(
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
        for (String f : files) {
            filesArray.add(f);
        }

        var zoneResponse = new S2CMessage.ZoneResponse(
            1L, "req-1", CodeZaikuBackend.NAME, "Board completed", data, List.of());

        return new AgentEvent.ZoneBroadcast(
            CodeZaikuBackend.NAME, "workshop", zoneResponse, Instant.now());
    }

    /** Build a ZoneBroadcast with a board_completed event including build info. */
    private AgentEvent.ZoneBroadcast fullBoardCompletionEvent() {
        return boardCompletionEvent(
            "board-42", "/home/agent/workspace", "gpu-host",
            "Java", List.of("Main.java", "Helper.java"), "did:key:alice",
            10, 2, "success");
    }

    /** Build a ZoneBroadcast with a board_completed event without build step. */
    private AgentEvent.ZoneBroadcast noBuildCompletionEvent() {
        return boardCompletionEvent(
            "board-99", "/home/agent/scripts", "gpu-host",
            "Python", List.of("main.py", "utils.py"), "did:key:bob",
            null, null, null);
    }

    // --- Pipeline tests ---

    @Test void board_completion_event_generates_source_and_build() {
        bridge.accept(fullBoardCompletionEvent());

        // Verify items were persisted
        var sources = store.listSources();
        assertThat(sources).hasSize(1);

        var src = sources.getFirst();
        assertThat(src.taskId()).isEqualTo("board-42");
        assertThat(src.workspacePath()).isEqualTo("/home/agent/workspace");
        assertThat(src.files()).containsExactly("Main.java", "Helper.java");
        assertThat(src.backendMetadata())
            .containsEntry("hostNode", "gpu-host")
            .containsEntry("language", "Java")
            .containsEntry("createdBy", "did:key:alice");

        var codexId = (String) src.backendMetadata().get("codexId");
        var builds = store.findBuildsBySource(codexId);
        assertThat(builds).hasSize(1);

        var build = builds.getFirst();
        assertThat(build.sourceArtifactId()).isEqualTo(codexId);
        assertThat(build.testsPassed()).isEqualTo(10);
        assertThat(build.testsFailed()).isEqualTo(2);
        assertThat(build.status()).isEqualTo("success");

        // Room objects were placed (codex + artifact = 2)
        assertThat(placements).hasSize(1);
        var placement = placements.getFirst();
        assertThat(placement.roomId()).isEqualTo("workshop");
        assertThat(placement.objects()).hasSize(2);
    }

    @Test void board_completion_without_build_generates_source_only() {
        bridge.accept(noBuildCompletionEvent());

        var sources = store.listSources();
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().taskId()).isEqualTo("board-99");
        assertThat(sources.getFirst().backendMetadata())
            .containsEntry("language", "Python");

        // No build artifacts because no buildStatus was provided
        var codexId = (String) sources.getFirst().backendMetadata().get("codexId");
        var builds = store.findBuildsBySource(codexId);
        assertThat(builds).isEmpty();

        // Room placement has only the source codex
        assertThat(placements).hasSize(1);
        assertThat(placements.getFirst().objects()).hasSize(1);
        assertThat(placements.getFirst().objects().getFirst().id()).startsWith("codex-");
    }

    @Test void generated_room_objects_are_takeable_and_visible() {
        bridge.accept(fullBoardCompletionEvent());

        var placement = placements.getFirst();
        for (var obj : placement.objects()) {
            assertThat(obj.takeable()).isTrue();
            assertThat(obj.visible()).isTrue();
        }
    }

    @Test void source_and_build_linked() {
        bridge.accept(fullBoardCompletionEvent());

        var src = store.listSources().getFirst();
        var codexId = (String) src.backendMetadata().get("codexId");
        var builds = store.findBuildsBySource(codexId);
        assertThat(builds).hasSize(1);

        var artifactId = (String) builds.getFirst().backendMetadata().get("artifactId");
        var found = store.findBuild(artifactId);
        assertThat(found).isPresent();
        assertThat(found.get().sourceArtifactId()).isEqualTo(codexId);
    }

    @Test void agent_codex_action_parses_correctly() {
        var llmOutput = """
            I'll commit these changes to the codex.
            ```json
            {"action": "codex_action", "operation": "commit", "itemId": "codex-abc123", "params": {"message": "Fix null pointer in parser"}}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.CodexAction.class);

        var codexAction = (AgentAction.CodexAction) action;
        assertThat(codexAction.operation()).isEqualTo("commit");
        assertThat(codexAction.itemId()).isEqualTo("codex-abc123");
        assertThat(codexAction.params()).containsEntry("message", "Fix null pointer in parser");

        var cmd = new CodexCommand.Commit(codexAction.itemId(), codexAction.params().get("message"));
        assertThat(cmd.codexId()).isEqualTo("codex-abc123");
        assertThat(cmd.message()).isEqualTo("Fix null pointer in parser");
    }

    @Test void codex_action_maps_to_zone_command() {
        verifyCodexActionMapsToCommand("commit", "codex-abc",
            Map.of("message", "Fix bug"),
            new CodexCommand.Commit("codex-abc", "Fix bug"));

        verifyCodexActionMapsToCommand("push", "codex-abc",
            Map.of(),
            new CodexCommand.Push("codex-abc"));

        verifyCodexActionMapsToCommand("build", "codex-abc",
            Map.of(),
            new CodexCommand.Build("codex-abc"));

        verifyCodexActionMapsToCommand("deploy", "artifact-xyz",
            Map.of("target", "boiler-room"),
            new CodexCommand.Deploy("artifact-xyz", "boiler-room"));

        verifyCodexActionMapsToCommand("examine", "codex-abc",
            Map.of("file", "src/Main.java"),
            new CodexCommand.Examine("codex-abc", "src/Main.java"));
    }

    private void verifyCodexActionMapsToCommand(
            String operation, String itemId, Map<String, String> params,
            CodexCommand expectedCommand) {

        var paramsJson = new StringBuilder("{");
        var entries = new ArrayList<>(params.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) paramsJson.append(", ");
            paramsJson.append("\"").append(entries.get(i).getKey())
                .append("\": \"").append(entries.get(i).getValue()).append("\"");
        }
        paramsJson.append("}");

        var llmOutput = String.format("""
            ```json
            {"action": "codex_action", "operation": "%s", "itemId": "%s", "params": %s}
            ```
            """, operation, itemId, paramsJson);

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.CodexAction.class);
        var ca = (AgentAction.CodexAction) action;

        CodexCommand cmd = buildCommandFromAction(ca);
        assertThat(cmd).isNotNull();

        switch (expectedCommand) {
            case CodexCommand.Commit exp -> {
                assertThat(cmd).isInstanceOf(CodexCommand.Commit.class);
                var actual = (CodexCommand.Commit) cmd;
                assertThat(actual.codexId()).isEqualTo(exp.codexId());
                assertThat(actual.message()).isEqualTo(exp.message());
            }
            case CodexCommand.Push exp -> {
                assertThat(cmd).isInstanceOf(CodexCommand.Push.class);
                assertThat(((CodexCommand.Push) cmd).codexId()).isEqualTo(exp.codexId());
            }
            case CodexCommand.Build exp -> {
                assertThat(cmd).isInstanceOf(CodexCommand.Build.class);
                assertThat(((CodexCommand.Build) cmd).codexId()).isEqualTo(exp.codexId());
            }
            case CodexCommand.Deploy exp -> {
                assertThat(cmd).isInstanceOf(CodexCommand.Deploy.class);
                var actual = (CodexCommand.Deploy) cmd;
                assertThat(actual.artifactId()).isEqualTo(exp.artifactId());
                assertThat(actual.target()).isEqualTo(exp.target());
            }
            case CodexCommand.Examine exp -> {
                assertThat(cmd).isInstanceOf(CodexCommand.Examine.class);
                var actual = (CodexCommand.Examine) cmd;
                assertThat(actual.codexId()).isEqualTo(exp.codexId());
                assertThat(actual.file()).isEqualTo(exp.file());
            }
            default -> throw new AssertionError("Unexpected command type: " + expectedCommand);
        }
    }

    private static CodexCommand buildCommandFromAction(AgentAction.CodexAction ca) {
        return switch (ca.operation()) {
            case "commit" -> new CodexCommand.Commit(ca.itemId(), ca.params().get("message"));
            case "push" -> new CodexCommand.Push(ca.itemId());
            case "build" -> new CodexCommand.Build(ca.itemId());
            case "deploy" -> new CodexCommand.Deploy(ca.itemId(), ca.params().get("target"));
            case "examine" -> new CodexCommand.Examine(ca.itemId(), ca.params().get("file"));
            case "branch" -> new CodexCommand.Branch(ca.itemId(), ca.params().get("branchName"));
            case "diff" -> new CodexCommand.Diff(ca.itemId(), ca.params().get("ref"));
            case "destroy" -> new CodexCommand.Destroy(ca.itemId());
            default -> null;
        };
    }

    @Test void multiple_boards_generate_independent_items() {
        var event1 = boardCompletionEvent(
            "board-1", "/tmp/ws1", "node-a",
            "Java", List.of("A.java"), "did:key:alice",
            5, 0, "success");

        var event2 = boardCompletionEvent(
            "board-2", "/tmp/ws2", "node-b",
            "Python", List.of("b.py", "c.py"), "did:key:bob",
            3, 1, "failed");

        bridge.accept(event1);
        bridge.accept(event2);

        var sources = store.listSources();
        assertThat(sources).hasSize(2);

        // Verify they have unique IDs
        var codexIds = sources.stream()
            .map(s -> (String) s.backendMetadata().get("codexId"))
            .toList();
        assertThat(codexIds).doesNotHaveDuplicates();

        // Each source has its own build
        for (var src : sources) {
            var codexId = (String) src.backendMetadata().get("codexId");
            var builds = store.findBuildsBySource(codexId);
            assertThat(builds).hasSize(1);
            assertThat(builds.getFirst().sourceArtifactId()).isEqualTo(codexId);
        }

        // Two separate placement callbacks
        assertThat(placements).hasSize(2);
    }

    @Test void bridge_ignores_non_completion_events() {
        // System event -- not a ZoneBroadcast
        var systemEvent = new AgentEvent.SystemEvent(
            AgentEvent.SystemEventType.NODE_JOINED, "node-1", "New node joined", Instant.now());
        bridge.accept(systemEvent);

        // Adjacent activity -- not a ZoneBroadcast
        var adjacentEvent = new AgentEvent.AdjacentActivity(
            "room-2", "Library", AgentEvent.ActivityType.SPEECH, 3, Instant.now());
        bridge.accept(adjacentEvent);

        // ZoneBroadcast with wrong namespace
        var wrongNamespace = new AgentEvent.ZoneBroadcast(
            "iot", "living-room",
            new S2CMessage.ZoneResponse(1L, "req-1", "iot", "Light toggled", null, List.of()),
            Instant.now());
        bridge.accept(wrongNamespace);

        // ZoneBroadcast with codezaiku namespace but a status event (not board_completed)
        ObjectNode statusData = Json.mapper().createObjectNode();
        statusData.put("event", "board_status");
        statusData.put("boardId", "board-42");
        statusData.put("status", "running");

        var statusEvent = new AgentEvent.ZoneBroadcast(
            CodeZaikuBackend.NAME, "workshop",
            new S2CMessage.ZoneResponse(2L, "req-2", CodeZaikuBackend.NAME,
                "Board running", statusData, List.of()),
            Instant.now());
        bridge.accept(statusEvent);

        // ZoneBroadcast with codezaiku namespace but Prose message (not ZoneResponse)
        var proseEvent = new AgentEvent.ZoneBroadcast(
            CodeZaikuBackend.NAME, "workshop",
            new S2CMessage.Prose(3L, "system", "Build in progress", List.of(), null, "normal"),
            Instant.now());
        bridge.accept(proseEvent);

        // ZoneBroadcast with codezaiku ZoneResponse but null data
        var nullDataEvent = new AgentEvent.ZoneBroadcast(
            CodeZaikuBackend.NAME, "workshop",
            new S2CMessage.ZoneResponse(4L, "req-3", CodeZaikuBackend.NAME,
                "No data", null, List.of()),
            Instant.now());
        bridge.accept(nullDataEvent);

        // ZoneBroadcast with codezaiku ZoneResponse but no "event" field
        ObjectNode noEventData = Json.mapper().createObjectNode();
        noEventData.put("boardId", "board-42");

        var noEventFieldEvent = new AgentEvent.ZoneBroadcast(
            CodeZaikuBackend.NAME, "workshop",
            new S2CMessage.ZoneResponse(5L, "req-4", CodeZaikuBackend.NAME,
                "Missing event", noEventData, List.of()),
            Instant.now());
        bridge.accept(noEventFieldEvent);

        // None of these should produce items or placements
        assertThat(store.listSources()).isEmpty();
        assertThat(placements).isEmpty();
    }

    @Test void store_persistence_survives_reconnect() {
        // Save via the pipeline
        bridge.accept(fullBoardCompletionEvent());

        var src = store.listSources().getFirst();
        var codexId = (String) src.backendMetadata().get("codexId");
        var builds = store.findBuildsBySource(codexId);
        var artifactId = (String) builds.getFirst().backendMetadata().get("artifactId");

        // Create a brand new store pointing to the same SQLite file
        var dbPath = tempDir.resolve("integration-test.db");
        var store2 = new CodeItemStore("jdbc:sqlite:" + dbPath.toAbsolutePath());

        // Verify the source survived the reconnect
        var foundSrc = store2.findSource(codexId);
        assertThat(foundSrc).isPresent();
        assertThat(foundSrc.get().taskId()).isEqualTo("board-42");
        assertThat(foundSrc.get().workspacePath()).isEqualTo("/home/agent/workspace");
        assertThat(foundSrc.get().files()).containsExactly("Main.java", "Helper.java");

        // Verify the build survived too
        var foundBuild = store2.findBuild(artifactId);
        assertThat(foundBuild).isPresent();
        assertThat(foundBuild.get().sourceArtifactId()).isEqualTo(codexId);
        assertThat(foundBuild.get().status()).isEqualTo("success");

        // Verify the source-build link works from the new store
        var linkedBuilds = store2.findBuildsBySource(codexId);
        assertThat(linkedBuilds).hasSize(1);
        assertThat((String) linkedBuilds.getFirst().backendMetadata().get("artifactId"))
            .isEqualTo(artifactId);
    }

    @Test void room_object_placement_callback_fires() {
        var collectedObjects = new ArrayList<RoomObject>();
        var collectedRooms = new ArrayList<String>();

        var customRegistry = new BackendRegistry();
        customRegistry.register(new CodeZaikuEventAdapter(generator));
        var customBridge = new CodingTaskItemBridge(customRegistry, placement -> {
            collectedRooms.add(placement.roomId());
            collectedObjects.addAll(placement.objects());
        });

        customBridge.accept(fullBoardCompletionEvent());

        // Verify callback received the room ID
        assertThat(collectedRooms).containsExactly("workshop");

        // Verify callback received exactly 2 room objects (codex + artifact)
        assertThat(collectedObjects).hasSize(2);

        var ids = collectedObjects.stream().map(RoomObject::id).toList();
        assertThat(ids).anySatisfy(id -> assertThat(id).startsWith("codex-"));
        assertThat(ids).anySatisfy(id -> assertThat(id).startsWith("artifact-"));

        // Verify all placed objects are takeable and visible
        for (var obj : collectedObjects) {
            assertThat(obj.takeable()).isTrue();
            assertThat(obj.visible()).isTrue();
        }

        // For no-build completion, only the codex should be placed
        collectedObjects.clear();
        collectedRooms.clear();

        customBridge.accept(noBuildCompletionEvent());

        assertThat(collectedObjects).hasSize(1);
        assertThat(collectedObjects.getFirst().id()).startsWith("codex-");
    }
}
