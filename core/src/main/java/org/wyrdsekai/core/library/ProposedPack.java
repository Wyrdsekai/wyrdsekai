package org.wyrdsekai.core.library;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A pending pack-acquisition proposal sitting on the library's arrival table
 * Discovery (bunshin-driven, federation tip
 * upload, gap signal) lands a {@code ProposedPack}; review (auto for
 * high-tier or steward-approved otherwise) consumes it; ingest writes it
 * into the library's Lucene index with full provenance.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProposedPack(
    @JsonProperty("id") String id,
    @JsonProperty("topic") String topic,
    @JsonProperty("summary") String summary,
    @JsonProperty("sources") List<Provenance.Source> sources,
    @JsonProperty("trustTier") Provenance.TrustTier trustTier,
    @JsonProperty("estChunks") Integer estChunks,
    @JsonProperty("license") String license,
    @JsonProperty("whyRelevant") String whyRelevant,
    @JsonProperty("proposedAt") Instant proposedAt,
    @JsonProperty("proposedBy") String proposedBy,
    @JsonProperty("trigger") String trigger,
    @JsonProperty("status") Status status,
    @JsonProperty("reviewedBy") String reviewedBy,
    @JsonProperty("reviewedAt") Instant reviewedAt,
    @JsonProperty("rejectionReason") String rejectionReason
) {

    public enum Status { PENDING, APPROVED, REJECTED, INGESTED }

    @JsonCreator
    public ProposedPack {
        if (id == null) id = UUID.randomUUID().toString();
        if (proposedAt == null) proposedAt = Instant.now();
        if (status == null) status = Status.PENDING;
        if (sources == null) sources = List.of();
        if (trustTier == null) trustTier = Provenance.TrustTier.UNKNOWN;
    }

    public static ProposedPack of(String topic, String summary,
                                   Provenance.TrustTier tier,
                                   String trigger, String proposedBy) {
        return new ProposedPack(
            UUID.randomUUID().toString(), topic, summary, List.of(), tier,
            null, null, null, Instant.now(), proposedBy, trigger,
            Status.PENDING, null, null, null);
    }

    public ProposedPack approve(String reviewer) {
        return new ProposedPack(id, topic, summary, sources, trustTier, estChunks,
            license, whyRelevant, proposedAt, proposedBy, trigger,
            Status.APPROVED, reviewer, Instant.now(), null);
    }

    public ProposedPack reject(String reviewer, String reason) {
        return new ProposedPack(id, topic, summary, sources, trustTier, estChunks,
            license, whyRelevant, proposedAt, proposedBy, trigger,
            Status.REJECTED, reviewer, Instant.now(), reason);
    }

    public ProposedPack ingested() {
        return new ProposedPack(id, topic, summary, sources, trustTier, estChunks,
            license, whyRelevant, proposedAt, proposedBy, trigger,
            Status.INGESTED, reviewedBy, reviewedAt, rejectionReason);
    }

    /**
     * Return a copy with enriched summary + sources (asynchronous discovery
     * scout result). Status, trust tier, and other review-state fields are
     * preserved — the steward's pending decision shouldn't be reset by a
     * background enrichment.
     */
    public ProposedPack withEnrichment(String enrichedSummary,
                                         List<Provenance.Source> enrichedSources) {
        return new ProposedPack(id, topic,
            enrichedSummary != null ? enrichedSummary : summary,
            enrichedSources != null ? enrichedSources : sources,
            trustTier, estChunks, license, whyRelevant, proposedAt,
            proposedBy, trigger, status, reviewedBy, reviewedAt, rejectionReason);
    }

    /** True if this proposal qualifies for auto-approval (high-tier sources). */
    public boolean autoApproveEligible() {
        return trustTier != null && trustTier.autoApproveEligible();
    }
}
