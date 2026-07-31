package org.wyrdsekai.core.familiar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable author lineage for a {@link ThoughtForm} or crafted Tool.
 *
 * <p>. Provenance is <strong>never strippable</strong>
 * (§108 structural guarantee). Every authoring, revision, copy, or retirement
 * is recorded as an {@link Edit} on the lineage list. Mutations attempted
 * outside the sanctioned authoring path are rejected at the storage layer.</p>
 *
 * <p>The originalAuthor is the DID of the being who first shaped the artifact.
 * The lineage is ordered chronologically — index 0 is the first authoring
 * event, the last entry is the most recent change.</p>
 */
public record Provenance(
    String originalAuthor,
    List<Edit> lineage
) {

    public Provenance {
        if (originalAuthor == null || originalAuthor.isBlank()) {
            throw new IllegalArgumentException("originalAuthor required");
        }
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
    }

    public enum Action {
        AUTHORED,     // first creation
        REVISED,      // version bump by owner
        COPIED_FROM,  // a copy made for another agent
        RETIRED       // soft-deleted / archived
    }

    /**
     * A single edit on the provenance chain.
     *
     * @param agent   DID of the being performing this edit
     * @param action  what kind of edit this was
     * @param at      when
     * @param note    optional human-readable note from the agent
     */
    public record Edit(
        String agent,
        Action action,
        Instant at,
        String note
    ) {
        public Edit {
            if (agent == null || agent.isBlank()) throw new IllegalArgumentException("agent required");
            if (action == null) throw new IllegalArgumentException("action required");
            if (at == null) at = Instant.now();
        }
    }

    /** Start a new lineage — used at first authoring. */
    public static Provenance authoredBy(String authorDid, String note) {
        return new Provenance(authorDid,
            List.of(new Edit(authorDid, Action.AUTHORED, Instant.now(), note)));
    }

    /** Append a new edit, returning a new Provenance. Immutable. */
    public Provenance append(Edit edit) {
        var next = new ArrayList<>(lineage);
        next.add(edit);
        return new Provenance(originalAuthor, next);
    }

    /** Most recent editor DID (author if no revisions). */
    public String currentOwner() {
        if (lineage.isEmpty()) return originalAuthor;
        return lineage.get(lineage.size() - 1).agent();
    }
}
