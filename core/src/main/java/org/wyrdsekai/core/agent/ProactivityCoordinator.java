package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-agent proactivity coordination.
 *
 * Prevents duplicate actions: if one agent already surfaced a topic, others suppress.
 * Turn-taking: cooldown after any agent speaks proactively.
 *
 * Thread-safe singleton. Agents call recordAction() after proactive behavior
 * and checkDuplicate() before acting.
 */
public class ProactivityCoordinator {

    private static volatile ProactivityCoordinator instance;

    /** Record of a proactive action taken by an agent. */
    public record ProactiveActionTaken(
        String agentDid,
        String category,
        String summary,
        Instant timestamp
    ) {}

    /** category → most recent action on that topic */
    private final Map<String, ProactiveActionTaken> recentActions = new ConcurrentHashMap<>();

    /** Global cooldown — last proactive speech from any agent */
    private volatile Instant lastGlobalProactiveAction = Instant.MIN;

    /** Suppression window — don't repeat same category within this duration. */
    private static final Duration SUPPRESSION_WINDOW = Duration.ofMinutes(5);

    /** Cooldown between any agent's proactive speech. */
    private static final Duration COOLDOWN = Duration.ofSeconds(30);

    private ProactivityCoordinator() {}

    public static void init() {
        instance = new ProactivityCoordinator();
    }

    public static ProactivityCoordinator get() {
        return instance;
    }

    /**
     * Record that an agent took a proactive action.
     * Other agents checking the same category will see this and suppress.
     */
    public void recordAction(String agentDid, String category, String summary) {
        recentActions.put(category, new ProactiveActionTaken(
            agentDid, category, summary, Instant.now()));
        lastGlobalProactiveAction = Instant.now();
    }

    /**
     * Check if a proactive action on this category would be a duplicate.
     *
     * @param agentDid  the agent considering the action
     * @param category  the topic category
     * @return true if another agent already surfaced this topic recently
     */
    public boolean isDuplicate(String agentDid, String category) {
        var recent = recentActions.get(category);
        if (recent == null) return false;
        if (recent.agentDid().equals(agentDid)) return false; // own action doesn't suppress self
        return Duration.between(recent.timestamp(), Instant.now()).compareTo(SUPPRESSION_WINDOW) < 0;
    }

    /**
     * Check if the global cooldown has elapsed since the last proactive action.
     *
     * @return true if cooldown is still active (should NOT act)
     */
    public boolean isCooldownActive() {
        return Duration.between(lastGlobalProactiveAction, Instant.now()).compareTo(COOLDOWN) < 0;
    }

    /** Purge old entries. Called periodically. */
    public void purge() {
        var cutoff = Instant.now().minus(SUPPRESSION_WINDOW);
        recentActions.entrySet().removeIf(e -> e.getValue().timestamp().isBefore(cutoff));
    }
}
