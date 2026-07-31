package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.event.WorldEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Lightweight conversation thread tracking for agent engagement.
 *
 * Tracks which agents have responded to which conversation threads,
 * preventing pile-ons and echo responses.
 *
 * A thread is identified by: room + initiating speaker + first 30 chars + time bucket.
 * Threads expire after 5 minutes of no activity.
 */
public class ConversationTracker {

    private static final Duration THREAD_EXPIRY = Duration.ofMinutes(5);
    private static final int MAX_THREADS = 50;

    private final Map<String, ThreadState> threads = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ThreadState> eldest) {
            return size() > MAX_THREADS;
        }
    };

    /**
     * Last time we heard from each sender (by entity id). Used by the companion
     * to skip greetings mid-conversation —.
     * Cap size to the same bound as thread tracking so long-running companions
     * don't leak memory.
     */
    // ultrareview bug_009 / #421 — access-order LinkedHashMap so put() on an
    // existing key moves the entry to the tail. Without this the eviction is
    // by insertion order, which means the most actively-conversing partner
    // (recorded first) gets evicted as "eldest" once 50 distinct senders have
    // been seen — re-triggering the mid-conversation greeting
    // regression this map was added to prevent.
    private final Map<String, Instant> lastHeardFrom = new LinkedHashMap<>(MAX_THREADS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_THREADS;
        }
    };

    /** State of a conversation thread. */
    private static class ThreadState {
        final Instant started;
        Instant lastActivity;
        final Set<String> responders = new HashSet<>();

        ThreadState() {
            this.started = Instant.now();
            this.lastActivity = this.started;
        }

        boolean isExpired() {
            return Duration.between(lastActivity, Instant.now()).compareTo(THREAD_EXPIRY) > 0;
        }
    }

    /**
     * Generate a thread ID for a Said event.
     * Same speaker + similar text within a 1-minute bucket = same thread.
     */
    public String threadIdFor(WorldEvent.Said said) {
        var textPrefix = said.text() != null && said.text().length() > 30
            ? said.text().substring(0, 30) : (said.text() != null ? said.text() : "");
        var minuteBucket = Instant.now().getEpochSecond() / 60;
        return said.roomId() + "|" + said.entityId() + "|"
            + textPrefix.toLowerCase().hashCode() + "|" + minuteBucket;
    }

    /**
     * Record that an agent responded to a thread.
     */
    public void recordResponse(String threadId, String agentEntityId) {
        var state = threads.computeIfAbsent(threadId, _ -> new ThreadState());
        state.responders.add(agentEntityId);
        state.lastActivity = Instant.now();
    }

    /**
     * Record that speech was observed (creates/updates thread without marking a response).
     */
    public void recordSpeech(WorldEvent.Said said) {
        var threadId = threadIdFor(said);
        threads.computeIfAbsent(threadId, _ -> new ThreadState()).lastActivity = Instant.now();
        if (said.entityId() != null) {
            lastHeardFrom.put(said.entityId(), Instant.now());
        }
    }

    /**
     * How long ago we last heard from this sender, or null if never.
     * Used to distinguish first-contact (greeting appropriate) from
     * mid-conversation (greeting is noise).
     */
    public Duration sinceLastHeardFrom(String senderEntityId) {
        if (senderEntityId == null) return null;
        var when = lastHeardFrom.get(senderEntityId);
        if (when == null) return null;
        return Duration.between(when, Instant.now());
    }

    /**
     * Check if a specific agent has already responded to this thread.
     */
    public boolean hasResponded(String threadId, String agentEntityId) {
        var state = threads.get(threadId);
        if (state == null || state.isExpired()) return false;
        return state.responders.contains(agentEntityId);
    }

    /**
     * Count how many agents have responded to this thread.
     */
    public int responseCount(String threadId) {
        var state = threads.get(threadId);
        if (state == null || state.isExpired()) return 0;
        return state.responders.size();
    }

    /**
     * Clean up expired threads. Call periodically.
     */
    public void cleanup() {
        threads.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    /** Drop all threads + last-heard state. Test-only reset between probes. */
    public void clear() {
        threads.clear();
        lastHeardFrom.clear();
    }
}
