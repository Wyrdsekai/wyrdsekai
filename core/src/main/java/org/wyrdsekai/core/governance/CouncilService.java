package org.wyrdsekai.core.governance;

import org.wyrdsekai.common.i18n.I18n;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Council governance service (§34).
 * Manages proposals, voting, and outcomes for the Council Chamber.
 * In-memory for M0; JDBC persistence deferred to M1.
 */
public class CouncilService {

    /** Proposal types with different voting requirements. */
    public enum ProposalType {
        STANDARD,       // simple majority
        REMOVAL,        // supermajority (2/3)
        BANISHMENT,     // supermajority (2/3)
        TITHE_CHANGE,   // supermajority (2/3)
        POLICY          // simple majority
    }

    public enum ProposalStatus {
        DISCUSSION, VOTING, PASSED, FAILED, EXPIRED
    }

    public record Proposal(
        String id,
        String title,
        String description,
        ProposalType type,
        ProposalStatus status,
        String proposer,
        Instant createdAt,
        Instant votingEndsAt,
        Map<String, Boolean> votes  // entityId → approve(true)/reject(false)
    ) {
        public int approvals() {
            return (int) votes.values().stream().filter(v -> v).count();
        }

        public int rejections() {
            return (int) votes.values().stream().filter(v -> !v).count();
        }

        public int totalVotes() {
            return votes.size();
        }

        public boolean requiresSupermajority() {
            return type == ProposalType.REMOVAL
                || type == ProposalType.BANISHMENT
                || type == ProposalType.TITHE_CHANGE;
        }

        public String describe() {
            return String.format("[%s] %s (%s) — %s\n  %s: %s | %s: %d %s, %d %s",
                id, title, type, status,
                I18n.get("council.proposal.by"), proposer,
                I18n.get("council.proposal.votes"),
                approvals(), I18n.get("council.proposal.approve"),
                rejections(), I18n.get("council.proposal.reject"));
        }
    }

    public record VoteResult(boolean accepted, String message) {}

    /** Global singleton instance. */
    private static volatile CouncilService instance;

    /** Initialize global instance. Called by Main.java at startup. */
    public static CouncilService init() {
        instance = new CouncilService();
        return instance;
    }

    /** Initialize global instance with persistence. */
    public static CouncilService init(CouncilPersistence persistence) {
        instance = new CouncilService(persistence);
        return instance;
    }

    /** Get global instance (null if not initialized). */
    public static CouncilService get() {
        return instance;
    }

    private final CouncilPersistence persistence; // nullable
    private final Map<String, Proposal> proposals = new ConcurrentHashMap<>();
    private int nextId = 1;
    private static final Duration DEFAULT_DISCUSSION = Duration.ofMinutes(30);
    private static final Duration DEFAULT_VOTING = Duration.ofHours(24);

    public CouncilService() { this(null); }

    public CouncilService(CouncilPersistence persistence) {
        this.persistence = persistence;
    }

    /** Submit a new proposal. Starts in DISCUSSION status. */
    public Proposal submit(String title, String description, ProposalType type, String proposer) {
        var id = "prop-" + nextId++;
        var now = Instant.now();
        var proposal = new Proposal(id, title, description, type, ProposalStatus.DISCUSSION,
            proposer, now, now.plus(DEFAULT_DISCUSSION).plus(DEFAULT_VOTING),
            Map.of());
        proposals.put(id, proposal);
        if (persistence != null) persistence.saveProposal(proposal);
        return proposal;
    }

    /** Move a proposal from DISCUSSION to VOTING. */
    public Optional<Proposal> openVoting(String proposalId) {
        var p = proposals.get(proposalId);
        if (p == null || p.status() != ProposalStatus.DISCUSSION) return Optional.empty();
        var updated = new Proposal(p.id(), p.title(), p.description(), p.type(),
            ProposalStatus.VOTING, p.proposer(), p.createdAt(),
            Instant.now().plus(DEFAULT_VOTING), p.votes());
        proposals.put(proposalId, updated);
        if (persistence != null) persistence.saveProposal(updated);
        return Optional.of(updated);
    }

    /** Cast a vote on a proposal. */
    public VoteResult vote(String proposalId, String entityId, boolean approve) {
        var p = proposals.get(proposalId);
        if (p == null) return new VoteResult(false, "Proposal not found");
        if (p.status() != ProposalStatus.VOTING) {
            return new VoteResult(false, "Proposal is not in voting phase");
        }
        if (p.proposer().equals(entityId)) {
            return new VoteResult(false, "Proposer cannot vote on their own proposal");
        }
        if (p.votes().containsKey(entityId)) {
            return new VoteResult(false, "Already voted on this proposal");
        }

        var newVotes = new HashMap<>(p.votes());
        newVotes.put(entityId, approve);
        var updated = new Proposal(p.id(), p.title(), p.description(), p.type(),
            p.status(), p.proposer(), p.createdAt(), p.votingEndsAt(),
            Map.copyOf(newVotes));
        proposals.put(proposalId, updated);
        if (persistence != null) persistence.saveProposal(updated);
        return new VoteResult(true, approve ? "Vote: approve" : "Vote: reject");
    }

    /** Tally votes and resolve a proposal. */
    public Optional<Proposal> tally(String proposalId) {
        var p = proposals.get(proposalId);
        if (p == null || p.status() != ProposalStatus.VOTING) return Optional.empty();
        if (p.totalVotes() == 0) {
            var expired = withStatus(p, ProposalStatus.EXPIRED);
            proposals.put(proposalId, expired);
            return Optional.of(expired);
        }

        boolean passed;
        if (p.requiresSupermajority()) {
            // Supermajority: 2/3 of votes must approve
            passed = p.approvals() >= Math.ceil(p.totalVotes() * 2.0 / 3.0);
        } else {
            // Simple majority
            passed = p.approvals() > p.rejections();
        }

        var resolved = withStatus(p, passed ? ProposalStatus.PASSED : ProposalStatus.FAILED);
        proposals.put(proposalId, resolved);
        if (persistence != null) persistence.saveProposal(resolved);
        return Optional.of(resolved);
    }

    /** Get a proposal by ID. */
    public Optional<Proposal> get(String proposalId) {
        return Optional.ofNullable(proposals.get(proposalId));
    }

    /** List all active proposals (DISCUSSION or VOTING). */
    public List<Proposal> activeProposals() {
        return proposals.values().stream()
            .filter(p -> p.status() == ProposalStatus.DISCUSSION
                || p.status() == ProposalStatus.VOTING)
            .sorted(Comparator.comparing(Proposal::createdAt).reversed())
            .toList();
    }

    /** List all proposals. */
    public List<Proposal> allProposals() {
        return proposals.values().stream()
            .sorted(Comparator.comparing(Proposal::createdAt).reversed())
            .toList();
    }

    /** Total proposal count. */
    public int proposalCount() {
        return proposals.size();
    }

    /** Human-readable summary. */
    public String describe() {
        if (proposals.isEmpty()) return I18n.get("council.no_proposals");
        var sb = new StringBuilder("=== ").append(I18n.get("council.title")).append(" ===\n\n");
        for (var p : activeProposals()) {
            sb.append(p.describe()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private Proposal withStatus(Proposal p, ProposalStatus status) {
        return new Proposal(p.id(), p.title(), p.description(), p.type(),
            status, p.proposer(), p.createdAt(), p.votingEndsAt(), p.votes());
    }
}
