package org.wyrdsekai.core.economy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.TransitReputation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages endorsements and attestations for agent reputation (§98.6).
 *
 * <p>Endorsement flows:
 * <ul>
 *   <li>Steward endorsement — granted on agent graduation (tier 1+), highest trust weight</li>
 *   <li>Peer endorsement — granted via request_agent protocol, moderate trust weight</li>
 *   <li>External attestation — from Attestix or other services, configurable weight</li>
 * </ul>
 *
 * <p>Thread-safe: backed by ConcurrentHashMap. Singleton initialized by Main.java.</p>
 */
public class AttestationService {

    private static final Logger log = LoggerFactory.getLogger(AttestationService.class);

    /** Global singleton. */
    private static volatile AttestationService instance;

    /** agentDid → AgentReputation */
    private final Map<String, AgentReputation> reputations = new ConcurrentHashMap<>();

    public static AttestationService init() {
        instance = new AttestationService();
        return instance;
    }

    public static AttestationService get() {
        return instance;
    }

    /**
     * Get or create reputation for an agent.
     */
    public AgentReputation getReputation(String agentDid) {
        return reputations.computeIfAbsent(agentDid,
            did -> new AgentReputation(did, Instant.now()));
    }

    /**
     * Steward endorses an agent. Called when:
     * - Agent reaches tier 1 (first meaningful interaction)
     * - Agent completes a steward-assigned task
     * - Steward explicitly endorses via household management
     *
     * @param stewardDid the steward's DID
     * @param agentDid   the agent being endorsed
     * @param message    endorsement reason
     * @param score      endorsement score (0.0-1.0)
     */
    public void stewardEndorse(String stewardDid, String agentDid,
                                String message, double score) {
        var rep = getReputation(agentDid);
        rep.addEndorsement(new AgentReputation.Endorsement(
            stewardDid, AgentReputation.EndorserType.STEWARD,
            message, clamp(score), Instant.now()));
        log.info("Steward '{}' endorsed agent '{}': {} (score={})",
            stewardDid, agentDid, message, score);
    }

    /**
     * Peer agent endorses another agent. Called after successful
     * request_agent interactions or collaborative tasks.
     *
     * @param fromAgentDid endorsing agent
     * @param toAgentDid   agent being endorsed
     * @param message      endorsement reason
     * @param score        endorsement score (0.0-1.0)
     */
    public void peerEndorse(String fromAgentDid, String toAgentDid,
                             String message, double score) {
        var rep = getReputation(toAgentDid);
        rep.addEndorsement(new AgentReputation.Endorsement(
            fromAgentDid, AgentReputation.EndorserType.PEER_AGENT,
            message, clamp(score), Instant.now()));
        log.info("Agent '{}' endorsed agent '{}': {} (score={})",
            fromAgentDid, toAgentDid, message, score);
    }

    /**
     * External attestation service provides a score.
     *
     * @param serviceDid service identifier
     * @param agentDid   agent being attested
     * @param score      attestation score (0.0-1.0)
     */
    public void externalAttestation(String serviceDid, String agentDid, double score) {
        var rep = getReputation(agentDid);
        rep.addEndorsement(new AgentReputation.Endorsement(
            serviceDid, AgentReputation.EndorserType.ATTESTATION_SERVICE,
            "External attestation", clamp(score), Instant.now()));
        log.info("External attestation for agent '{}' from '{}': score={}",
            agentDid, serviceDid, score);
    }

    /**
     * Record task completion outcome for an agent.
     */
    public void recordTaskOutcome(String agentDid, boolean success) {
        getReputation(agentDid).recordTaskCompletion(success);
    }

    /**
     * Record transaction outcome for an agent.
     */
    public void recordTransaction(String agentDid, boolean success) {
        getReputation(agentDid).recordTransaction(success);
    }

    /**
     * Check if an agent meets a reputation threshold.
     */
    public boolean meetsThreshold(String agentDid, double threshold) {
        return getReputation(agentDid).computeScore().meetsThreshold(threshold);
    }

    /**
     * Get the composite reputation score for an agent.
     */
    public AgentReputation.ReputationScore score(String agentDid) {
        return getReputation(agentDid).computeScore();
    }

    /** Number of tracked agents. */
    public int agentCount() {
        return reputations.size();
    }

    /**
     * Serialize an entity's reputation as a TransitReputation snapshot
     * to include in the session.open payload for cross-zone transit.
     */
    public TransitReputation serializeForTransit(
            String entityDid, String homeZoneId) {
        var rep = reputations.get(entityDid);
        if (rep == null) {
            return TransitReputation.empty(entityDid, homeZoneId);
        }
        var score = rep.computeScore();
        int steward = 0, peer = 0, external = 0;
        for (var e : rep.endorsements()) {
            switch (e.endorserType()) {
                case STEWARD -> steward++;
                case PEER_AGENT -> peer++;
                case ATTESTATION_SERVICE -> external++;
            }
        }
        long ageDays = Duration.between(rep.createdAt(), Instant.now()).toDays();
        return new TransitReputation(
            entityDid, ageDays, steward, peer, external, score.overall(), homeZoneId);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
