package org.wyrdsekai.core.library;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Provenance metadata attached to every knowledge chunk
 *
 * <p>Captures: where the chunk came from, who fetched it, who approved it,
 * what trust tier it sits in, and whether it arrived via federation. Citations
 * work all the way down — the citation rendered to the user can resolve back
 * to a source name + URL via this record.</p>
 *
 * <p>All fields are nullable. Existing chunks (the bundled 5 packs) ship with
 * provenance {@code null}, which the {@link TrustTier#UNKNOWN} default handles
 * gracefully. Backfill is a separate migration; this record just defines the
 * shape.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Provenance(
    @JsonProperty("source") Source source,
    @JsonProperty("trustTier") TrustTier trustTier,
    @JsonProperty("license") String license,
    @JsonProperty("fetchedAt") Instant fetchedAt,
    @JsonProperty("fetchedBy") String fetchedBy,
    @JsonProperty("approvedBy") String approvedBy,
    @JsonProperty("approvedAt") Instant approvedAt,
    @JsonProperty("via") String via,
    @JsonProperty("federatedFrom") String federatedFrom
) {

    @JsonCreator
    public Provenance {
        if (trustTier == null) trustTier = TrustTier.UNKNOWN;
    }

    /**
     * Trust tiers. Drive the auto-approve gate:
     * high-tier sources auto-approve, others require steward review.
     */
    public enum TrustTier {
        PAPER,        // arXiv, peer-reviewed, recognized journals — auto-approve
        WIKI,         // Wikipedia, established reference — auto-approve
        BOOK,         // published, ISBN-bearing — auto-approve if licensable
        BLOG,         // personal blogs, Medium, Substack — steward review
        FORUM,        // StackExchange, Reddit, mailing list archives — review
        PERSONAL,     // uploaded files, household-private — auto-approve if uploader trusted
        FEDERATED,    // inherits source pack's tier from peer library
        UNKNOWN;      // uncategorized source — review

        public boolean autoApproveEligible() {
            return this == PAPER || this == WIKI || this == BOOK || this == PERSONAL;
        }
    }

    /** Where the chunk came from. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(
        @JsonProperty("kind") String kind,
        @JsonProperty("ref") String ref,
        @JsonProperty("url") String url,
        @JsonProperty("title") String title,
        @JsonProperty("authors") List<String> authors,
        @JsonProperty("year") Integer year
    ) {
        @JsonCreator
        public Source {
            if (authors == null) authors = List.of();
        }
    }

    /** Default provenance for chunks without metadata: UNKNOWN tier. */
    public static Provenance unknown() {
        return new Provenance(null, TrustTier.UNKNOWN, null, null, null, null, null, null, null);
    }
}
