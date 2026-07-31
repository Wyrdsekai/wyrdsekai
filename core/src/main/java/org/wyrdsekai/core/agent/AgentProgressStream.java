package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// Streams incremental agent progress to connected clients.
// Used for multi-step tasks: search results, navigation updates, plan progress.
public class AgentProgressStream {

    private static final Logger log = LoggerFactory.getLogger(AgentProgressStream.class);
    private static volatile AgentProgressStream instance;

    public static void init() { instance = new AgentProgressStream(); }
    public static AgentProgressStream get() { return instance; }

    // Progress event types
    public sealed interface ProgressEvent {
        String agentId();
        Instant timestamp();

        // Action started (search initiated, navigation begun, etc.)
        record ActionStarted(String agentId, String actionType, String description,
                            Instant timestamp) implements ProgressEvent {}

        // Incremental result (one search result, one step completed, etc.)
        record IncrementalResult(String agentId, String actionType, String data,
                                int index, int total,
                                Instant timestamp) implements ProgressEvent {}

        // Action completed
        record ActionCompleted(String agentId, String actionType, String summary,
                              boolean success, long durationMs,
                              Instant timestamp) implements ProgressEvent {}

        // Plan progress (goal N of M)
        record PlanProgress(String agentId, String planDescription,
                           int currentGoal, int totalGoals, String goalDescription,
                           String status,
                           Instant timestamp) implements ProgressEvent {}
    }

    private final CopyOnWriteArrayList<Consumer<ProgressEvent>> subscribers = new CopyOnWriteArrayList<>();

    // Subscribe to progress events.
    public void subscribe(Consumer<ProgressEvent> listener) {
        subscribers.add(listener);
    }

    // Unsubscribe.
    public void unsubscribe(Consumer<ProgressEvent> listener) {
        subscribers.remove(listener);
    }

    // Publish a progress event to all subscribers.
    public void publish(ProgressEvent event) {
        for (var sub : subscribers) {
            try {
                sub.accept(event);
            } catch (Exception e) {
                log.debug("Progress subscriber error: {}", e.getMessage());
            }
        }
    }

    // Convenience methods for common events
    public void actionStarted(String agentId, String actionType, String description) {
        publish(new ProgressEvent.ActionStarted(agentId, actionType, description, Instant.now()));
    }

    public void incrementalResult(String agentId, String actionType, String data, int index, int total) {
        publish(new ProgressEvent.IncrementalResult(agentId, actionType, data, index, total, Instant.now()));
    }

    public void actionCompleted(String agentId, String actionType, String summary, boolean success, long durationMs) {
        publish(new ProgressEvent.ActionCompleted(agentId, actionType, summary, success, durationMs, Instant.now()));
    }

    public void planProgress(String agentId, String planDesc, int current, int total, String goalDesc, String status) {
        publish(new ProgressEvent.PlanProgress(agentId, planDesc, current, total, goalDesc, status, Instant.now()));
    }

    public int subscriberCount() { return subscribers.size(); }
}
