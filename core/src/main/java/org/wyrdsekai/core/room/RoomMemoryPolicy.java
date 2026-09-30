package org.wyrdsekai.core.room;

import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.memory.MemoryOrigin;
import org.wyrdsekai.core.memory.MemoryReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tiered memory buffer for agent conversation context.
 * Replaces the simple ArrayList+FIFO approach in CompanionActor.
 *
 * Three tiers:
 * - HOT: Most recent events, full text (high detail)
 * - WARM: Older events, kept as summaries (medium detail)
 * - COMPACTED: Oldest events, reduced to key facts (low detail)
 *
 * Events flow: new → hot → warm → compacted → evicted.
 * Spike handling: during high-traffic bursts, warm events are compacted
 * more aggressively to preserve hot buffer quality.
 *
 * Every entry carries its {@link MemoryOrigin} through all three tiers, and the
 * reads a prompt is built from take the {@link MemoryReader} of the turn: a line
 * someone said to her privately is never read back into another person's turn
 * (audit W4, 2026-09-28).
 */
public final class RoomMemoryPolicy {

    private final int hotSize;
    private final int warmSize;
    private final int compactedSize;

    private final List<WorldEvent.Said> hotBuffer;
    private final List<String> warmBuffer;     // summarized text
    private final List<String> compactedBuffer; // key facts
    // Parallel to the three buffers: who said each entry and whether privately.
    private final List<MemoryOrigin> hotOrigins = new ArrayList<>();
    private final List<MemoryOrigin> warmOrigins = new ArrayList<>();
    private final List<MemoryOrigin> compactedOrigins = new ArrayList<>();

    public RoomMemoryPolicy(int hotSize, int warmSize, int compactedSize) {
        this.hotSize = hotSize;
        this.warmSize = warmSize;
        this.compactedSize = compactedSize;
        this.hotBuffer = new ArrayList<>();
        this.warmBuffer = new ArrayList<>();
        this.compactedBuffer = new ArrayList<>();
    }

    /** Default policy: 20 hot, 50 warm, 20 compacted. */
    public static RoomMemoryPolicy defaultPolicy() {
        return new RoomMemoryPolicy(20, 50, 20);
    }

    /** Minimal policy for low-energy or small-context agents. */
    public static RoomMemoryPolicy minimal() {
        return new RoomMemoryPolicy(5, 10, 5);
    }

    /** Create from hot/warm buffer sizes. */
    public static RoomMemoryPolicy fromConfig(int hotSize, int warmSize) {
        return new RoomMemoryPolicy(hotSize, warmSize, Math.max(5, warmSize / 3));
    }

    /**
     * Add a new Said event to the hot buffer, as a line whose teller is not known (read
     * back only in turns with her bondholder).
     */
    public void add(WorldEvent.Said event) {
        add(event, MemoryOrigin.UNKNOWN);
    }

    /**
     * Add a new Said event to the hot buffer with its origin.
     * Oldest hot events cascade to warm, oldest warm to compacted.
     */
    public void add(WorldEvent.Said event, MemoryOrigin origin) {
        hotBuffer.add(event);
        hotOrigins.add(origin == null ? MemoryOrigin.UNKNOWN : origin);

        // Cascade: hot overflow → warm
        while (hotBuffer.size() > hotSize) {
            var evicted = hotBuffer.removeFirst();
            warmBuffer.add(summarize(evicted));
            warmOrigins.add(hotOrigins.removeFirst());
        }

        // Cascade: warm overflow → compacted
        while (warmBuffer.size() > warmSize) {
            var evicted = warmBuffer.removeFirst();
            compactedBuffer.add(compact(evicted));
            compactedOrigins.add(warmOrigins.removeFirst());
        }

        // Compacted overflow: oldest facts evicted
        while (compactedBuffer.size() > compactedSize) {
            compactedBuffer.removeFirst();
            compactedOrigins.removeFirst();
        }
    }

    /** Get hot buffer events (full text, most recent). */
    public List<WorldEvent.Said> hotEvents() {
        return Collections.unmodifiableList(hotBuffer);
    }

    /** Hot buffer events the reader may read, in order. */
    public List<WorldEvent.Said> hotEvents(MemoryReader reader) {
        var r = reader == null ? MemoryReader.NO_ONE : reader;
        var out = new ArrayList<WorldEvent.Said>(hotBuffer.size());
        for (int i = 0; i < hotBuffer.size(); i++) {
            if (r.mayRead(hotOrigins.get(i))) out.add(hotBuffer.get(i));
        }
        return Collections.unmodifiableList(out);
    }

    /** The origin recorded for this line, if it is still in the hot buffer (same instance). */
    public MemoryOrigin originOf(WorldEvent.Said event) {
        for (int i = hotBuffer.size() - 1; i >= 0; i--) {
            if (hotBuffer.get(i) == event) return hotOrigins.get(i);
        }
        return null;
    }

    /** Get warm buffer summaries. */
    public List<String> warmSummaries() {
        return Collections.unmodifiableList(warmBuffer);
    }

    /** Get compacted key facts. */
    public List<String> compactedFacts() {
        return Collections.unmodifiableList(compactedBuffer);
    }

    /** Total events tracked across all tiers. */
    public int totalSize() {
        return hotBuffer.size() + warmBuffer.size() + compactedBuffer.size();
    }

    /** Whether any memory exists at all. */
    public boolean isEmpty() {
        return hotBuffer.isEmpty() && warmBuffer.isEmpty() && compactedBuffer.isEmpty();
    }

    /**
     * Build the Layer 5 memory buffer string for PromptAssembler.
     * Compacted facts first (lowest detail), then warm summaries, then
     * the hot events are handled separately as conversation history.
     */
    public String buildMemoryContext() {
        return render(compactedBuffer, warmBuffer);
    }

    /** The Layer 5 memory buffer string, holding only what the reader may read. */
    public String buildMemoryContext(MemoryReader reader) {
        var r = reader == null ? MemoryReader.NO_ONE : reader;
        return render(readable(compactedBuffer, compactedOrigins, r), readable(warmBuffer, warmOrigins, r));
    }

    private static List<String> readable(List<String> entries, List<MemoryOrigin> origins, MemoryReader r) {
        var out = new ArrayList<String>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            if (r.mayRead(origins.get(i))) out.add(entries.get(i));
        }
        return out;
    }

    private static String render(List<String> compacted, List<String> warm) {
        if (compacted.isEmpty() && warm.isEmpty()) {
            return null; // No memory beyond hot buffer
        }

        var sb = new StringBuilder();

        if (!compacted.isEmpty()) {
            sb.append("[Earlier context] ");
            sb.append(String.join("; ", compacted));
            sb.append("\n");
        }

        if (!warm.isEmpty()) {
            sb.append("[Recent history] ");
            sb.append(String.join(" | ", warm));
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Handle a traffic spike: compact warm buffer more aggressively.
     * Called when events arrive faster than normal (e.g., >5 events/second).
     */
    public void handleSpike() {
        // Merge pairs of warm summaries to halve the warm buffer. Only a pair with one origin
        // is merged: two people's words in one entry could go back to neither alone.
        if (warmBuffer.size() > 4) {
            var merged = new ArrayList<String>();
            var mergedOrigins = new ArrayList<MemoryOrigin>();
            for (int i = 0; i < warmBuffer.size(); i += 2) {
                if (i + 1 < warmBuffer.size() && warmOrigins.get(i).equals(warmOrigins.get(i + 1))) {
                    merged.add(warmBuffer.get(i) + "; " + warmBuffer.get(i + 1));
                    mergedOrigins.add(warmOrigins.get(i));
                } else {
                    merged.add(warmBuffer.get(i));
                    mergedOrigins.add(warmOrigins.get(i));
                    if (i + 1 < warmBuffer.size()) {
                        merged.add(warmBuffer.get(i + 1));
                        mergedOrigins.add(warmOrigins.get(i + 1));
                    }
                }
            }
            warmBuffer.clear();
            warmBuffer.addAll(merged);
            warmOrigins.clear();
            warmOrigins.addAll(mergedOrigins);
        }
    }

    /** Clear all memory tiers. */
    public void clear() {
        hotBuffer.clear();
        warmBuffer.clear();
        compactedBuffer.clear();
        hotOrigins.clear();
        warmOrigins.clear();
        compactedOrigins.clear();
    }

    /**
     * Summarize a Said event for the warm buffer.
     * Extracts speaker + truncated text.
     */
    private static String summarize(WorldEvent.Said event) {
        var text = event.text();
        if (text.length() > 80) {
            text = text.substring(0, 77) + "...";
        }
        return event.entityName() + ": " + text;
    }

    /**
     * Compact a warm summary into a key fact.
     * Extracts the speaker and first clause.
     */
    private static String compact(String summary) {
        // Trim to first sentence or 50 chars
        var dotIdx = summary.indexOf('.');
        if (dotIdx > 0 && dotIdx < 50) {
            return summary.substring(0, dotIdx + 1);
        }
        if (summary.length() > 50) {
            return summary.substring(0, 47) + "...";
        }
        return summary;
    }
}
