package org.wyrdsekai.core.story;

import java.time.Instant;
import java.util.List;

/**
 * D.0 — a beat is the smallest dramatic unit in a scene.
 *
 * <p>Beats close on one of five canonical triggers ({@link BeatTrigger}).
 * The anchor is a pure-text factual rendering of the beat's events — no
 * LLM call; concatenation of event observations in order.</p>
 *
 * @param id          UUID
 * @param sceneId     parent scene id
 * @param trigger     which condition fired the beat close
 * @param rangeStart  inclusive start instant
 * @param rangeEnd    inclusive end instant
 * @param eventIds    ids of WorldEvents inside this beat (for journal recall)
 * @param anchor      pure-text rendering: "Masumi settled into the worn
 *                    leather chair." or "They spoke about the OSS push."
 */
public record Beat(
    String id,
    String sceneId,
    BeatTrigger trigger,
    Instant rangeStart,
    Instant rangeEnd,
    List<String> eventIds,
    String anchor
) {
    public Beat {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Beat id required");
        if (trigger == null) throw new IllegalArgumentException("trigger required");
        if (eventIds == null) eventIds = List.of();
        else eventIds = List.copyOf(eventIds);
        if (anchor == null) anchor = "";
    }
}
