package org.wyrdsekai.core.agent.interiority;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * typed chronicle row, separate from the
 * narrative {@link ChronicleService.Chronicle} record.
 *
 * <p>Where {@code Chronicle} renders a testimony for the bondholder
 * to read, {@code ChronicleEntry} is the structured trace of a single
 * notable event the agent processed during a sleep batch: a recipe
 * ran, a substrate-pattern fired, a Forge consolidation happened. The
 * Study Chronicle furnishing reads both and renders them together —
 * narrative on top, structured rows below.</p>
 *
 * <p> Track-C C5: {@link Kind#RECIPE_RUN} carries the
 * per-recipe outcome from {@code completeSleep}'s recipe-Forge block.
 * The {@code data} map holds the structured fields (recipe id, trigger
 * source, gate outcomes, primary metric, deploy/rollback decision,
 * cadence tier, next-fire estimate). Free-form keys so future kinds
 * don't need a schema migration.</p>
 */
public record ChronicleEntry(
        String agentDid,
        Instant ts,
        Kind kind,
        String summary,
        Map<String, Object> data) {

    public ChronicleEntry {
        if (agentDid == null || agentDid.isBlank())
            throw new IllegalArgumentException("agentDid required");
        if (ts == null) ts = Instant.now();
        if (kind == null) kind = Kind.NOTE;
        if (data == null) data = Map.of();
        else data = Map.copyOf(data);
    }

    /** Kinds of typed events. Add liberally; downstream renders by string. */
    public enum Kind {
        /** Free-form note. Default when caller doesn't specify. */
        NOTE,
        /** C5: one completed recipe run. */
        RECIPE_RUN,
        /** Substrate finding (sustained suppression/dissociation, etc). */
        SUBSTRATE_PATTERN,
        /** Forge consolidation summary. */
        FORGE_PASS
    }

    /** Render to a flat JSON-friendly map for transport + furnishing display. */
    public Map<String, Object> toMap() {
        var out = new LinkedHashMap<String, Object>();
        out.put("agentDid", agentDid);
        out.put("ts", ts.toString());
        out.put("kind", kind.name());
        out.put("summary", summary);
        out.put("data", data);
        return out;
    }
}
