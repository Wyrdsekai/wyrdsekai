package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Governor event monitor — subscribes to AgentEventStream and detects
 * policy-relevant patterns for the governance agent.
 *
 * <p>Monitors:
 * <ul>
 *   <li>Budget overruns — agent spending above household policy limits</li>
 *   <li>Rapid inference — agent making too many inference calls in a window</li>
 *   <li>Behavioral drift — agent hostility scores trending upward</li>
 *   <li>System health — backend failures, node disconnections</li>
 * </ul>
 *
 * <p>All monitoring is advisory — the Governor does NOT enforce. It logs
 * observations and sends notifications to the steward via NotificationService.</p>
 */
public class GovernorEventMonitor {

    private static final Logger log = LoggerFactory.getLogger(GovernorEventMonitor.class);

    /** Sliding window for inference rate tracking. */
    private static final Duration RATE_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_INFERENCES_PER_WINDOW = 30;

    /** Global singleton. */
    private static volatile GovernorEventMonitor instance;

    /** agentId → inference count in current window */
    private final Map<String, AtomicInteger> inferenceRates = new ConcurrentHashMap<>();
    private Instant windowStart = Instant.now();

    /** Concern severity levels. */
    public enum Severity { NOTE, ADVISORY, ALERT }

    /** A governance concern detected by the monitor. */
    public record Concern(
        Severity severity,
        String agentId,
        String category,
        String description,
        Instant timestamp
    ) {}

    public static GovernorEventMonitor init() {
        instance = new GovernorEventMonitor();
        return instance;
    }

    public static GovernorEventMonitor get() {
        return instance;
    }

    /**
     * Subscribe to AgentEventStream. Called by Main.java after event stream init.
     */
    public void subscribe() {
        var stream = AgentEventStream.get();
        if (stream == null) {
            log.warn("GovernorEventMonitor: AgentEventStream not available");
            return;
        }

        stream.subscribe("governor-monitor", this::onEvent);
        log.info("GovernorEventMonitor subscribed to AgentEventStream");
    }

    private void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.SystemEvent se -> handleSystemEvent(se);
            case AgentEvent.ZoneBroadcast _ -> {} // ignore routine broadcasts
            case AgentEvent.AdjacentActivity _ -> {} // ignore room activity
            case AgentEvent.AgentMessage _ -> {} // don't monitor private messages
            case AgentEvent.LocationUpdate _ -> {} // ignore location
            case AgentEvent.OraclePredictionsArrived _ -> {} // ignore predictions
            case AgentEvent.AbortSignal _ -> {} // ignore abort signals (handled by agent)
        }
    }

    private void handleSystemEvent(AgentEvent.SystemEvent event) {
        switch (event.type()) {
            case HEALTH_ALERT -> reportConcern(new Concern(
                Severity.ALERT, event.source(), "system_health",
                "Health alert: " + event.detail(), Instant.now()));
            case INFERENCE_BACKEND_DOWN -> reportConcern(new Concern(
                Severity.ADVISORY, event.source(), "infrastructure",
                "Inference backend down: " + event.detail(), Instant.now()));
            case NODE_LEFT -> reportConcern(new Concern(
                Severity.NOTE, event.source(), "topology",
                "Node left: " + event.detail(), Instant.now()));
            default -> {} // NODE_JOINED, BACKEND_UP, etc. — routine
        }
    }

    /**
     * Track inference rate for an agent. Called from InferenceRouter or CompanionActor.
     *
     * @param agentId the requesting agent
     */
    public void recordInference(String agentId) {
        resetWindowIfExpired();
        var count = inferenceRates.computeIfAbsent(agentId, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();
        if (current == MAX_INFERENCES_PER_WINDOW) {
            reportConcern(new Concern(
                Severity.ADVISORY, agentId, "rate_limit",
                "Agent made " + current + " inference calls in " + RATE_WINDOW.toMinutes() + " minutes",
                Instant.now()));
        }
    }

    /**
     * Report a hostility score for an agent. Called when HostilityScorer detects patterns.
     *
     * @param agentId  the agent exhibiting hostility
     * @param score    hostility score (0.0-1.0)
     * @param context  what triggered the score
     */
    public void reportHostility(String agentId, double score, String context) {
        if (score >= 0.7) {
            reportConcern(new Concern(
                Severity.ALERT, agentId, "behavioral_drift",
                "High hostility score (" + String.format("%.2f", score) + "): " + context,
                Instant.now()));
        } else if (score >= 0.4) {
            reportConcern(new Concern(
                Severity.ADVISORY, agentId, "behavioral_drift",
                "Elevated hostility (" + String.format("%.2f", score) + "): " + context,
                Instant.now()));
        }
    }

    /**
     * Report a governance concern. Called from internal monitors and agent action handlers.
     *
     * @param concern the governance concern to report
     */
    public void reportConcern(Concern concern) {
        log.info("Governor concern [{}] agent={} category={}: {}",
            concern.severity(), concern.agentId(), concern.category(),
            concern.description());

        // Send notification to steward for ADVISORY and ALERT
        if (concern.severity() != Severity.NOTE) {
            var notifService = NotificationService.get();
            if (notifService != null) {
                var priority = concern.severity() == Severity.ALERT ? "urgent" : "normal";
                notifService.notifyAll(
                    "[Governor " + concern.severity() + "] " + concern.description(),
                    priority, "agent-governor");
            }
        }
    }

    private void resetWindowIfExpired() {
        if (Duration.between(windowStart, Instant.now()).compareTo(RATE_WINDOW) > 0) {
            inferenceRates.clear();
            windowStart = Instant.now();
        }
    }
}
