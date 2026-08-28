package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.*;

/**
 * Bridge between Wyrdsekai's Crucible and CodeZaiku's MCP tools (§85.17.2).
 *
 * The Forge is the face. CodeZaiku is the engine. The Between is the nervous system.
 *
 * MCP Server contracts:
 * - Experiment MCP (Trackio, Q1): Track growth experiments, compare variants
 * - Training Monitor MCP (Q6): Stream progress during LoRA fine-tuning
 * - Eval Harness MCP (Q7): Behavioral regression testing
 * - Dataset MCP (Oxen.ai, Q2): Version agent's training data
 * - Model Registry (Q3): Track adapter lineage
 * - Evolutionary Search (Q11): Population-based variant search
 *
 * This bridge translates Crucible operations into MCP tool calls.
 * Actual transport is abstracted — in production, calls go through McpGatewayService.
 */
public class CrucibleMcpBridge {

    /** MCP call request (transport-independent). */
    public record McpCall(
        String server,      // "experiment", "training", "eval", "dataset", "registry", "evolution"
        String tool,        // MCP tool name
        Map<String, Object> arguments,
        Instant requestedAt
    ) {
        public static McpCall to(String server, String tool, Map<String, Object> args) {
            return new McpCall(server, tool, args, Instant.now());
        }
    }

    /** MCP call result. */
    public record McpResult(
        boolean success,
        String server,
        String tool,
        Map<String, Object> result,
        String error,
        Instant completedAt
    ) {
        public static McpResult success(String server, String tool, Map<String, Object> result) {
            return new McpResult(true, server, tool, result, null, Instant.now());
        }

        public static McpResult failure(String server, String tool, String error) {
            return new McpResult(false, server, tool, Map.of(), error, Instant.now());
        }
    }

    /** Transport abstraction for MCP calls. */
    @FunctionalInterface
    public interface McpTransport {
        McpResult call(McpCall request);
    }

    private final McpTransport transport;
    private final List<McpCall> callLog = new ArrayList<>();

    public CrucibleMcpBridge(McpTransport transport) {
        this.transport = transport;
    }

    // ── Experiment MCP (Trackio, Q1) ──

    /** Create a growth experiment. */
    public McpResult createExperiment(String agentDid, String description,
                                       List<String> variantIds) {
        return call(McpCall.to("experiment", "experiment.create", Map.of(
            "agentDid", agentDid,
            "description", description,
            "variants", variantIds
        )));
    }

    /** Compare variants in an experiment. */
    public McpResult compareExperiment(String experimentId) {
        return call(McpCall.to("experiment", "experiment.compare", Map.of(
            "experimentId", experimentId
        )));
    }

    /** Log a metric to an experiment run. */
    public McpResult logMetric(String experimentId, String runId,
                                 String key, double value) {
        return call(McpCall.to("experiment", "experiment.log_metric", Map.of(
            "experimentId", experimentId,
            "runId", runId,
            "key", key,
            "value", value
        )));
    }

    // ── Training Monitor MCP (Q6) ──

    /** Start monitoring a training run. */
    public McpResult startTraining(String experimentId, String runId,
                                     Map<String, Object> config) {
        return call(McpCall.to("training", "training.start", Map.of(
            "experimentId", experimentId,
            "runId", runId,
            "config", config
        )));
    }

    /** Get training status. */
    public McpResult trainingStatus(String runId) {
        return call(McpCall.to("training", "training.status", Map.of(
            "runId", runId
        )));
    }

    // ── Eval Harness MCP (Q7) ──

    /** Run behavioral evaluation on a variant. */
    public McpResult runEval(String runId, String scenarioSet) {
        return call(McpCall.to("eval", "eval.run", Map.of(
            "runId", runId,
            "scenarioSet", scenarioSet
        )));
    }

    /** Run regression test between current and proposed soul. */
    public McpResult regressionTest(String currentSoulHash, String proposedSoulHash) {
        return call(McpCall.to("eval", "eval.regression", Map.of(
            "currentSoul", currentSoulHash,
            "proposedSoul", proposedSoulHash
        )));
    }

    // ── Dataset MCP (Oxen.ai, Q2) ──

    /** Snapshot agent's training data from event journal. */
    public McpResult snapshotDataset(String agentDid, Instant from, Instant to) {
        return call(McpCall.to("dataset", "dataset.snapshot", Map.of(
            "agentDid", agentDid,
            "from", from.toString(),
            "to", to.toString()
        )));
    }

    // ── Model Registry (Q3) ──

    /** Publish a trained adapter. */
    public McpResult publishAdapter(String runId, String adapterUri) {
        return call(McpCall.to("registry", "registry.publish", Map.of(
            "runId", runId,
            "adapter", adapterUri
        )));
    }

    /** Get adapter lineage. */
    public McpResult adapterLineage(String adapterUri) {
        return call(McpCall.to("registry", "registry.lineage", Map.of(
            "adapterUri", adapterUri
        )));
    }

    // ── Evolutionary Search (Q11) ──

    /** Start Mind Evolution search for behavioral variants. */
    public McpResult startEvolution(String agentDid, int populationSize,
                                      int maxGenerations, Map<String, Object> config) {
        return call(McpCall.to("evolution", "evolution.search", Map.of(
            "agentDid", agentDid,
            "populationSize", populationSize,
            "maxGenerations", maxGenerations,
            "config", config
        )));
    }

    // ── Call Log ──

    /** Get recent MCP calls. */
    public List<McpCall> recentCalls(int limit) {
        int start = Math.max(0, callLog.size() - limit);
        return List.copyOf(callLog.subList(start, callLog.size()));
    }

    /** Total MCP calls made. */
    public int totalCalls() {
        return callLog.size();
    }

    private McpResult call(McpCall request) {
        callLog.add(request);
        return transport.call(request);
    }
}
