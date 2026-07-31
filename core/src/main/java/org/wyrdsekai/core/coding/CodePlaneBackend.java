package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.CommandRouter;
import org.wyrdsekai.core.codeplane.CodeItemStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * CodePlane implementation of {@link CodingTaskBackend}.
 *
 * <p>Wraps the existing CodePlane infrastructure ({@link CodeItemStore},
 * {@link CommandRouter}) without changing it. Tasks are dispatched as
 * {@code codeplane.create} zone commands through the existing
 * {@link CommandRouter} path; artifacts are read from the existing
 * {@link CodeItemStore}.</p>
 *
 * <p>. CodePlane stays the only
 * permitted concrete backend until Phase 2 ships Aider.</p>
 */
public final class CodePlaneBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link CodePlaneEventAdapter#namespace()}. */
    public static final String NAME = "codeplane";

    private static final Logger log = LoggerFactory.getLogger(CodePlaneBackend.class);

    private final CodeItemStore store;
    private final CommandRouter commandRouter;
    /** DID-or-zone identifier the backend submits commands as. */
    private final String submitterId;

    /**
     * @param store          legacy CodePlane item store; used for {@link
     *                       #artifactsFor(String)} lookups by board ID.
     * @param commandRouter  routes {@code codeplane.*} commands across the
     *                       zone bridge to the live CodePlane process. May be
     *                       {@code null} in tests / standalone setups —
     *                       {@link #submitTask} will then return a FAILED
     *                       result rather than throw.
     * @param submitterId    DID or zone-id used as the entityId on
     *                       {@link CommandRouter#execute commandRouter.execute}.
     *                       Typically the local zone's DID.
     */
    public CodePlaneBackend(CodeItemStore store, CommandRouter commandRouter,
                             String submitterId) {
        this.store = store;
        this.commandRouter = commandRouter;
        this.submitterId = submitterId != null ? submitterId : "local";
    }

    @Override public String name() { return NAME; }

    /**
     * CodePlane runs locally on the household — same machine as the zone in
     * single-node deployments, sibling node in cluster mode. Compute / disk
     * cost is real but no per-token billing applies.
     */
    @Override public BackendTier tier() { return BackendTier.LOCAL_HEAVY; }

    @Override
    public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
        var future = new CompletableFuture<TaskResult>();
        var started = System.currentTimeMillis();
        var taskId = spec.taskId() != null ? spec.taskId() : UUID.randomUUID();

        if (commandRouter == null) {
            log.warn("CodePlaneBackend: no CommandRouter wired — task {} aborted", taskId);
            future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                "CodePlane command router not configured", List.of(), 0L, 0L));
            return future;
        }

        // Build the equivalent zone_command payload. Existing CodePlane
        // commands accept these keys ( +
        // CodePlane's documented command shape); unknown keys are ignored
        // by the receiver, so this stays forward-compatible.
        var payload = new LinkedHashMap<String, String>();
        payload.put("taskId", taskId.toString());
        if (spec.taskType() != null) payload.put("taskType", spec.taskType());
        if (spec.description() != null) payload.put("description", spec.description());
        if (spec.workspaceHint() != null) payload.put("workspaceHint", spec.workspaceHint());
        if (spec.companionDid() != null) payload.put("createdBy", spec.companionDid());
        if (spec.files() != null && !spec.files().isEmpty()) {
            payload.put("files", String.join(",", spec.files()));
        }
        if (spec.maxCu() > 0) payload.put("maxCu", String.valueOf(spec.maxCu()));
        if (spec.deadline() != null) payload.put("deadline", spec.deadline().toString());

        try {
            // Fire-and-acknowledge: the zone bridge accepts the command and
            // CodePlane runs the work asynchronously. The actual board
            // completion arrives later as a ZoneBroadcast — translated into
            // a CodingArtifact by CodePlaneEventAdapter and placed into
            // the room by CodingTaskItemBridge.
            boolean routed = commandRouter.execute(submitterId, "codeplane.create",
                List.of(), payload, (S2CMessage resp) -> {
                    // Acknowledge response only used here for log breadcrumbs.
                    log.debug("codeplane.create ack for task {}: {}", taskId, resp);
                });

            long durationMs = System.currentTimeMillis() - started;
            if (!routed) {
                future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                    "No handler registered for codeplane namespace",
                    List.of(), 0L, durationMs));
            } else {
                // Submission accepted — the artifact-bearing ZoneBroadcast
                // arrives later. Surface "submitted" as SUCCEEDED so the
                // caller knows the request was handed off cleanly. Phase 1b
                // / Phase 2 will hook a correlation map so the future
                // resolves on the actual board_completed broadcast.
                future.complete(new TaskResult(taskId, NAME, TaskStatus.SUCCEEDED,
                    "Submitted to CodePlane", List.of(), 0L, durationMs));
            }
        } catch (Exception e) {
            log.warn("CodePlane submission failed for task {}: {}", taskId, e.getMessage());
            future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                "Submission error: " + e.getMessage(), List.of(), 0L,
                System.currentTimeMillis() - started));
        }
        return future;
    }

    @Override
    public Stream<CodingArtifact> artifactsFor(String taskId) {
        if (store == null || taskId == null) return Stream.empty();
        // Legacy CodePlane keys items by boardId; we treat the inbound
        // taskId as the board ID for the lookup. The list-and-filter shape
        // below avoids adding a new query method to CodeItemStore.
        var sources = store.listSources().stream()
            .filter(s -> taskId.equals(s.taskId()))
            .toList();
        var sourceStream = sources.stream()
            .map(s -> (CodingArtifact) s);
        var buildStream = sources.stream()
            .flatMap(s -> {
                var codexId = s.backendMetadata() != null
                    ? (String) s.backendMetadata().get("codexId")
                    : null;
                if (codexId == null) return Stream.<BuildArtifact>empty();
                return store.findBuildsBySource(codexId).stream();
            })
            .map(b -> (CodingArtifact) b);
        return Stream.concat(sourceStream, buildStream);
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        // Phase 1a: CodePlane health is "is a codeplane handler registered?".
        // Phase 2 will probe the actual zone bridge connection.
        var ok = commandRouter != null
            && commandRouter.availableNamespaces().contains("codeplane");
        return CompletableFuture.completedFuture(ok);
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        // CodePlane runs locally; no per-token billing. Return 0 so cost
        // policy doesn't gate it in Phase 1a. Phase 5 will refine this with
        // historical task duration → CU equivalence.
        return 0L;
    }
}
