package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEventStream;

import java.util.List;

/**
 * Announcing that a coding task finished, so its result can become a thing in the world.
 *
 * <p>{@link CodingTaskItemBridge} listens for a terminal {@code ZoneBroadcast}, translates
 * it through the matching {@link BackendAdapter}, runs {@link ItemInvokeSmoke} against a
 * no-side-effect stub, and places the resulting artifact in the originating room. That
 * whole apparatus — the bridge and eleven adapters — depends on someone publishing the
 * event.
 *
 * <p>Two paths reach a backend and only one announced itself. A zone command typed in the
 * Workshop went through {@link CodingNamespaceHandler}, which published. A companion's
 * {@code dispatch_task} called {@code backend.submit()} directly and published nothing, so
 * the work ran and the result was dropped: on the household node the bridge logged its
 * subscription on every boot and received nothing, ever, while Goose completed tasks
 * touching one and two files (2026-08-19).
 *
 * <p>Asked to build an in-world item, the companion chose to delegate it to the coding
 * backend. That was the right instinct and a supported design; she simply used the door
 * that does not ring the bell. This is the shared publish so both doors ring it, rather
 * than a second copy that can drift from the first.
 */
public final class CodingTaskBroadcast {

    private static final Logger log = LoggerFactory.getLogger(CodingTaskBroadcast.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CodingTaskBroadcast() {}

    /**
     * Publish the terminal event for a finished task.
     *
     * @param backendName the namespace an adapter is registered under — it MUST equal the
     *                    backend's name or {@link CodingTaskItemBridge} ignores the event
     *                    silently, which is the failure this class exists to end.
     * @param roomId      where the artifact should land; the originating room.
     * @param taskId      the task's id, as the adapters expect it.
     * @param backend     used to look up the artifacts the run cached.
     */
    public static void publishTerminal(String backendName, String roomId, String taskId,
            TaskResult result, CodingTaskBackend backend) {
        publishTerminal(backendName, roomId, taskId, result, backend, AgentEventStream.get());
    }

    /**
     * As above, publishing to a GIVEN stream.
     *
     * <p>{@link AgentEventStream#init()} replaces the global instance outright, so a test
     * that initialises the stream, subscribes, and then publishes can have its subscriber
     * orphaned by any other test that initialises in between — the publish lands on a
     * different instance and the assertion sees nothing. That is a flake in the one test
     * standing guard over the publish→bridge seam, which is exactly the test that must
     * never be doubted. Taking the stream as a parameter makes it deterministic while the
     * production path above still resolves the global.
     */
    public static void publishTerminal(String backendName, String roomId, String taskId,
            TaskResult result, CodingTaskBackend backend, AgentEventStream stream) {
        if (result == null || backendName == null) return;
        if (roomId == null || roomId.isBlank()) {
            log.debug("[CodingTaskBroadcast] no roomId for {} task {} — skipping "
                + "(an artifact with nowhere to land is not an artifact)", backendName, taskId);
            return;
        }
        if (stream == null) {
            log.debug("[CodingTaskBroadcast] AgentEventStream not initialised; "
                + "skipping ZoneBroadcast for {} task {}", backendName, taskId);
            return;
        }

        publishTerminalWithArtifacts(backendName, roomId, taskId, result,
            backend == null ? List.<CodingArtifact>of() : backend.artifactsFor(taskId).toList(),
            stream);
    }

    /**
     * As above, with the artifacts supplied directly.
     *
     * <p>{@link CodingTaskBackend} is a sealed interface, so a test cannot stand one up to
     * carry artifacts — and without artifacts the published event names an empty
     * workspace, which the bridge correctly ignores. That made the one leg that had never
     * run in production (backend output → bridge → registered, usable item) impossible to
     * exercise without a live backend. Splitting the lookup off keeps every line that
     * SHAPES the event on the shared path.
     */
    public static void publishTerminalWithArtifacts(String backendName, String roomId,
            String taskId, TaskResult result, List<CodingArtifact> artifacts,
            AgentEventStream stream) {
        if (result == null || backendName == null) return;
        if (roomId == null || roomId.isBlank()) {
            log.debug("[CodingTaskBroadcast] no roomId for {} task {} — skipping "
                + "(an artifact with nowhere to land is not an artifact)", backendName, taskId);
            return;
        }
        if (stream == null) {
            log.debug("[CodingTaskBroadcast] AgentEventStream not initialised; "
                + "skipping ZoneBroadcast for {} task {}", backendName, taskId);
            return;
        }
        if (artifacts == null) artifacts = List.of();

        var data = MAPPER.createObjectNode();
        data.put("event", result.status() == TaskStatus.SUCCEEDED
            ? "task_completed" : "task_failed");
        data.put("taskId", taskId);
        data.put("status", result.status().name().toLowerCase());
        var src = artifacts.stream()
            .filter(a -> a instanceof SourceArtifact)
            .map(a -> (SourceArtifact) a)
            .findFirst().orElse(null);
        if (src != null) {
            data.put("workspace", safe(src.workspacePath()));
            var files = data.putArray("files");
            if (src.files() != null) src.files().forEach(files::add);
            if (src.backendMetadata() != null) {
                var av = src.backendMetadata().get("agent_version");
                if (av != null) data.put("agentVersion", String.valueOf(av));
            }
        } else {
            // No SourceArtifact captured — synthesise minimal fields so the adapter can
            // still parse and produce a stub codex.
            data.put("workspace", "");
            data.putArray("files");
        }

        var build = artifacts.stream()
            .filter(a -> a instanceof BuildArtifact)
            .map(a -> (BuildArtifact) a)
            .findFirst().orElse(null);
        if (build != null) {
            ObjectNode b = data.putObject("build");
            b.put("status", safe(build.status()));
            b.put("testsPassed", build.testsPassed());
            b.put("testsFailed", build.testsFailed());
        }

        var msg = new S2CMessage.ZoneResponse(0, taskId, backendName,
            "task " + result.status().name().toLowerCase(), (JsonNode) data, List.of());
        stream.publishZoneBroadcast(backendName, roomId, msg);
        log.info("[CodingTaskBroadcast] published terminal ZoneBroadcast "
            + "(ns={}, room={}, taskId={}, files={})", backendName, roomId, taskId,
            src == null || src.files() == null ? 0 : src.files().size());
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
