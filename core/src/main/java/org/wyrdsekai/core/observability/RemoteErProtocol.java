package org.wyrdsekai.core.observability;

import java.time.Instant;
import java.util.*;

/**
 * Remote ER protocol for phone buds (§105.5).
 * Headline-connected remote diagnostics, patching, and recall.
 */
public class RemoteErProtocol {

    /** A remote ER request from a bud. */
    public record RemoteRequest(
        String requestId,
        String budDid,
        String householdId,
        RequestSource source,
        Instant requestedAt,
        RemoteRequestStatus status,
        String diagnosticSummary
    ) {}

    public enum RequestSource {
        /** Human pressed emergency button. */
        HUMAN_EMERGENCY,
        /** Bud detected own anomaly. */
        SELF_REPORT,
        /** Household detected bud anomaly via headline. */
        HEADLINE_MONITOR
    }

    public enum RemoteRequestStatus {
        RECEIVED, DIAGNOSING, RESPONSE_SENT, RESOLVED, FAILED
    }

    /** Response options for a remote ER request. */
    public enum ResponseAction {
        /** Push corrected fragments and updated manifest. */
        REMOTE_PATCH,
        /** Strip bud to minimal personality. */
        SAFE_MODE,
        /** Warm handoff — pull bud home for full repair. */
        RECALL,
        /** Force bud into sleep cycle for self-repair. */
        EMERGENCY_SLEEP
    }

    /** A remote ER response. */
    public record RemoteResponse(
        String requestId,
        ResponseAction action,
        Instant respondedAt,
        Map<String, String> payload
    ) {}

    /** Diagnostic data sent from the bud. */
    public record BudDiagnostic(
        String budDid,
        Map<String, Double> vitalitySnapshot,
        List<String> recentFragmentChecksums,
        double behavioralEmbeddingSimilarity,
        Instant collectedAt
    ) {}

    private final Map<String, RemoteRequest> requests = new LinkedHashMap<>();
    private final Map<String, RemoteResponse> responses = new LinkedHashMap<>();
    private int nextId = 1;

    /** Receive a remote ER request from a bud or human. */
    public RemoteRequest receiveRequest(String budDid, String householdId, RequestSource source) {
        var request = new RemoteRequest("rer-" + nextId++, budDid, householdId,
            source, Instant.now(), RemoteRequestStatus.RECEIVED, null);
        requests.put(request.requestId(), request);
        return request;
    }

    /** Run remote diagnostics on a bud. Returns assessment summary. */
    public RemoteRequest diagnose(String requestId, BudDiagnostic diagnostic) {
        var request = requests.get(requestId);
        if (request == null) return null;

        var summary = assessBud(diagnostic);
        var diagnosing = new RemoteRequest(request.requestId(), request.budDid(),
            request.householdId(), request.source(), request.requestedAt(),
            RemoteRequestStatus.DIAGNOSING, summary);
        requests.put(requestId, diagnosing);
        return diagnosing;
    }

    /** Recommend a response action based on diagnostics. */
    public ResponseAction recommendAction(String requestId) {
        var request = requests.get(requestId);
        if (request == null || request.diagnosticSummary() == null) return ResponseAction.SAFE_MODE;

        var summary = request.diagnosticSummary();
        if (summary.contains("CRITICAL")) return ResponseAction.RECALL;
        if (summary.contains("DEGRADED")) return ResponseAction.SAFE_MODE;
        if (summary.contains("DRIFT")) return ResponseAction.REMOTE_PATCH;
        return ResponseAction.EMERGENCY_SLEEP;
    }

    /** Send a response to the bud. */
    public RemoteResponse respond(String requestId, ResponseAction action, Map<String, String> payload) {
        var request = requests.get(requestId);
        if (request == null) return null;

        var response = new RemoteResponse(requestId, action, Instant.now(),
            payload != null ? Map.copyOf(payload) : Map.of());
        responses.put(requestId, response);

        var updated = new RemoteRequest(request.requestId(), request.budDid(),
            request.householdId(), request.source(), request.requestedAt(),
            RemoteRequestStatus.RESPONSE_SENT, request.diagnosticSummary());
        requests.put(requestId, updated);
        return response;
    }

    /** Mark a request as resolved. */
    public RemoteRequest resolve(String requestId) {
        var request = requests.get(requestId);
        if (request == null) return null;
        var resolved = new RemoteRequest(request.requestId(), request.budDid(),
            request.householdId(), request.source(), request.requestedAt(),
            RemoteRequestStatus.RESOLVED, request.diagnosticSummary());
        requests.put(requestId, resolved);
        return resolved;
    }

    public Optional<RemoteRequest> getRequest(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    public Optional<RemoteResponse> getResponse(String requestId) {
        return Optional.ofNullable(responses.get(requestId));
    }

    public List<RemoteRequest> activeRequests() {
        return requests.values().stream()
            .filter(r -> r.status() != RemoteRequestStatus.RESOLVED
                      && r.status() != RemoteRequestStatus.FAILED)
            .toList();
    }

    private String assessBud(BudDiagnostic diagnostic) {
        var issues = new ArrayList<String>();

        // Check vitality tanks
        long criticalTanks = diagnostic.vitalitySnapshot().values().stream()
            .filter(v -> v < 0.15).count();
        long degradedTanks = diagnostic.vitalitySnapshot().values().stream()
            .filter(v -> v < 0.3).count();

        if (criticalTanks >= 3) issues.add("CRITICAL: " + criticalTanks + " tanks below 15%");
        else if (degradedTanks >= 4) issues.add("DEGRADED: " + degradedTanks + " tanks below 30%");

        // Check behavioral drift
        if (diagnostic.behavioralEmbeddingSimilarity() < 0.5) {
            issues.add("CRITICAL: severe behavioral DRIFT (sim=" +
                String.format("%.2f", diagnostic.behavioralEmbeddingSimilarity()) + ")");
        } else if (diagnostic.behavioralEmbeddingSimilarity() < 0.7) {
            issues.add("DRIFT: moderate behavioral drift (sim=" +
                String.format("%.2f", diagnostic.behavioralEmbeddingSimilarity()) + ")");
        }

        if (issues.isEmpty()) return "HEALTHY: no anomalies detected";
        return String.join("; ", issues);
    }
}
