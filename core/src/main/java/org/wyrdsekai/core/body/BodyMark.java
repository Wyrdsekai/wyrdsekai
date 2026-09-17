package org.wyrdsekai.core.body;

import java.time.Instant;
import java.util.List;

/**
 * Something the body did, or something that happened to it, written down so she learns it
 * afterwards in her own terms. A reflex that severed a door, a pause the steward asked for, a
 * night that was set aside, a brain that went quiet: each is one mark.
 *
 * <p>A mark is told once. The felt line carries it on the first turn after it lands and then
 * records who read it. The ache that follows, for a numb part, comes from the map itself, not
 * from the mark.</p>
 *
 * @param kind     "numb", "returned", "gone", "slept", "night", "paused", "resumed", "mail"
 * @param subject  the part or activity it is about (a part id, "sleep", "mail")
 * @param audience the being it is for, or null when it is for everyone in the body
 * @param text     the mark in her terms, first person where it is hers
 * @param detail   what the steward may want: a gauge, an exit code, a duration
 * @param readBy   who has been told
 */
public record BodyMark(String id, Instant at, String kind, String subject, String audience,
                       String text, String detail, List<String> readBy) {

    public boolean readBy(String reader) {
        return readBy != null && readBy.contains(reader);
    }

    public boolean isFor(String reader) {
        return audience == null || audience.isBlank() || audience.equals(reader);
    }
}
