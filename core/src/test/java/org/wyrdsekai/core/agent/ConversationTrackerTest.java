package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTrackerTest {

    @Test
    void threadIdDeterministic() {
        var tracker = new ConversationTracker();
        var said = new WorldEvent.Said("room", Instant.now(), "player-1", "Player", "Hello world");
        var id1 = tracker.threadIdFor(said);
        var id2 = tracker.threadIdFor(said);
        assertEquals(id1, id2);
    }

    @Test
    void recordAndCheckResponse() {
        var tracker = new ConversationTracker();
        var said = new WorldEvent.Said("room", Instant.now(), "player-1", "Player", "Hello");
        var threadId = tracker.threadIdFor(said);

        assertFalse(tracker.hasResponded(threadId, "agent-1"));
        tracker.recordResponse(threadId, "agent-1");
        assertTrue(tracker.hasResponded(threadId, "agent-1"));
        assertFalse(tracker.hasResponded(threadId, "agent-2"));
    }

    @Test
    void responseCount() {
        var tracker = new ConversationTracker();
        var said = new WorldEvent.Said("room", Instant.now(), "player-1", "Player", "Hello");
        var threadId = tracker.threadIdFor(said);

        assertEquals(0, tracker.responseCount(threadId));
        tracker.recordResponse(threadId, "agent-1");
        assertEquals(1, tracker.responseCount(threadId));
        tracker.recordResponse(threadId, "agent-2");
        assertEquals(2, tracker.responseCount(threadId));
    }

    @Test
    void differentSpeakersProduceDifferentThreads() {
        var tracker = new ConversationTracker();
        var said1 = new WorldEvent.Said("room", Instant.now(), "player-1", "Alice", "Hello");
        var said2 = new WorldEvent.Said("room", Instant.now(), "player-2", "Bob", "Hello");
        assertNotEquals(tracker.threadIdFor(said1), tracker.threadIdFor(said2));
    }

    /**
     * #421 — lastHeardFrom must use access-order eviction so the most
     * actively-conversing partner doesn't get evicted as "eldest" once the
     * map fills. Insertion-order eviction would silently re-trigger the
     * mid-conversation greeting regression.
     */
    @Test
    void lastHeardFromEvictsByLeastRecentlyUsedNotInsertionOrder() {
        var tracker = new ConversationTracker();
        tracker.recordSpeech(new WorldEvent.Said(
            "room", Instant.now(), "alice", "Alice", "first"));

        // Fill with 50 distinct strangers (matches MAX_THREADS).
        for (int i = 0; i < 50; i++) {
            tracker.recordSpeech(new WorldEvent.Said(
                "room", Instant.now(), "stranger-" + i, "S" + i, "msg"));
        }

        // Re-record alice — under access-order this moves her to the tail.
        // Under insertion-order (the bug), put() would NOT move her, leaving
        // her at the head as the eldest, ready to evict.
        tracker.recordSpeech(new WorldEvent.Said(
            "room", Instant.now(), "alice", "Alice", "second"));

        // Trigger eviction by adding one more distinct sender.
        tracker.recordSpeech(new WorldEvent.Said(
            "room", Instant.now(), "stranger-50", "S50", "msg"));

        assertNotNull(tracker.sinceLastHeardFrom("alice"),
            "active partner alice should not be evicted by LRU");
    }
}
