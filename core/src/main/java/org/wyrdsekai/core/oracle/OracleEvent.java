package org.wyrdsekai.core.oracle;

import java.time.Instant;

/**
 * An event to send to the oracle-core prediction engine.
 * Converted from WorldEvent, AgentEvent, or any other source.
 */
public record OracleEvent(
    Instant timestamp,
    String source,      // "room_event", "email", "rss", "system", "search", "code"
    String eventType,   // "said", "search", "received", "commit", "article"
    String content,     // text payload
    String entityId,    // who caused it
    String roomId,      // where it happened
    Double numericValue // optional numeric (CPU %, steps, etc.)
) {
    public OracleEvent(Instant timestamp, String source, String eventType, String content) {
        this(timestamp, source, eventType, content, "", "", null);
    }

    public OracleEvent(Instant timestamp, String source, String eventType,
                       String content, String entityId, String roomId) {
        this(timestamp, source, eventType, content, entityId, roomId, null);
    }
}
