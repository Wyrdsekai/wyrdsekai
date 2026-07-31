package org.wyrdsekai.core.room;

import org.wyrdsekai.common.event.WorldEvent;

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
 */
public final class RoomMemoryPolicy {

    private final int hotSize;
    private final int warmSize;
    private final int compactedSize;

    private final List<WorldEvent.Said> hotBuffer;
    private final List<String> warmBuffer;     // summarized text
    private final List<String> compactedBuffer; // key facts

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
     * Add a new Said event to the hot buffer.
     * Oldest hot events cascade to warm, oldest warm to compacted.
     */
    public void add(WorldEvent.Said event) {
        hotBuffer.add(event);

        // Cascade: hot overflow → warm
        while (hotBuffer.size() > hotSize) {
            var evicted = hotBuffer.removeFirst();
            warmBuffer.add(summarize(evicted));
        }

        // Cascade: warm overflow → compacted
        while (warmBuffer.size() > warmSize) {
            var evicted = warmBuffer.removeFirst();
            compactedBuffer.add(compact(evicted));
        }

        // Compacted overflow: oldest facts evicted
        while (compactedBuffer.size() > compactedSize) {
            compactedBuffer.removeFirst();
        }
    }

    /** Get hot buffer events (full text, most recent). */
    public List<WorldEvent.Said> hotEvents() {
        return Collections.unmodifiableList(hotBuffer);
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
        if (compactedBuffer.isEmpty() && warmBuffer.isEmpty()) {
            return null; // No memory beyond hot buffer
        }

        var sb = new StringBuilder();

        if (!compactedBuffer.isEmpty()) {
            sb.append("[Earlier context] ");
            sb.append(String.join("; ", compactedBuffer));
            sb.append("\n");
        }

        if (!warmBuffer.isEmpty()) {
            sb.append("[Recent history] ");
            sb.append(String.join(" | ", warmBuffer));
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Handle a traffic spike: compact warm buffer more aggressively.
     * Called when events arrive faster than normal (e.g., >5 events/second).
     */
    public void handleSpike() {
        // Merge pairs of warm summaries to halve the warm buffer
        if (warmBuffer.size() > 4) {
            var merged = new ArrayList<String>();
            for (int i = 0; i < warmBuffer.size(); i += 2) {
                if (i + 1 < warmBuffer.size()) {
                    merged.add(warmBuffer.get(i) + "; " + warmBuffer.get(i + 1));
                } else {
                    merged.add(warmBuffer.get(i));
                }
            }
            warmBuffer.clear();
            warmBuffer.addAll(merged);
        }
    }

    /** Clear all memory tiers. */
    public void clear() {
        hotBuffer.clear();
        warmBuffer.clear();
        compactedBuffer.clear();
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
