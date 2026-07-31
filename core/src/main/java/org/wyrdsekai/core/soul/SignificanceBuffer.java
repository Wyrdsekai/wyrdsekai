package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Buffer for agent-flagged significance entries. The agent writes to this during
 * active conversation via remember/note/forget actions. The Forge consumes it
 * during the sleep cycle to boost/suppress fragment significance.
 *
 * Thread-safe: agent writes from actor thread, Forge reads from background thread.
 * Maximum 50 entries — oldest dropped on overflow.
 */
public final class SignificanceBuffer {

    private static final int MAX_ENTRIES = 50;

    /** A single significance entry from the agent. */
    public record Entry(
        String content,
        float importance,       // 0.0-1.0
        Source source,
        boolean superseded,     // true for forget entries
        String target,          // for forget: what to supersede (nullable)
        Instant timestamp
    ) {}

    public enum Source {
        AGENT_REMEMBER,  // explicit "remember this"
        AGENT_NOTE,      // lighter observation
        AGENT_FORGET     // mark something as outdated
    }

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    /** Add a remember entry. */
    public void remember(String content, float importance) {
        add(new Entry(content, Math.clamp(importance, 0f, 1f),
            Source.AGENT_REMEMBER, false, null, Instant.now()));
    }

    /** Add a note entry. */
    public void note(String content) {
        add(new Entry(content, 0.4f, Source.AGENT_NOTE, false, null, Instant.now()));
    }

    /** Add a forget entry. */
    public void forget(String target, String reason) {
        add(new Entry(reason != null ? reason : "Agent requested forget",
            0.0f, Source.AGENT_FORGET, true, target, Instant.now()));
    }

    private void add(Entry entry) {
        entries.add(entry);
        // Trim oldest if over max
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    /** Get all entries and clear the buffer. Called by the Forge during sleep. */
    public List<Entry> consumeAll() {
        var snapshot = new ArrayList<>(entries);
        entries.clear();
        return Collections.unmodifiableList(snapshot);
    }

    /** Peek at current entries without consuming. */
    public List<Entry> peek() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Current entry count. */
    public int size() {
        return entries.size();
    }

    /** True if buffer has any entries. */
    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    /** Drop all entries without consuming them. Test-only reset. */
    public void clear() {
        entries.clear();
    }
}
