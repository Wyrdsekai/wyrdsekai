package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttestationServiceTest {

    private AttestationService service;

    @BeforeEach
    void setUp() {
        service = new AttestationService();
    }

    @Test
    void steward_endorsement_boosts_reputation() {
        service.stewardEndorse("steward-1", "agent-1", "Good agent", 0.9);

        var score = service.score("agent-1");
        assertTrue(score.endorsementScore() > 0);
        assertTrue(score.endorsementCount() > 0);
    }

    @Test
    void peer_endorsement_adds_to_reputation() {
        service.peerEndorse("agent-2", "agent-1", "Helpful collaborator", 0.8);

        var score = service.score("agent-1");
        assertEquals(1, score.endorsementCount());
    }

    @Test
    void multiple_endorsements_compound() {
        service.stewardEndorse("steward-1", "agent-1", "Reliable", 0.9);
        service.peerEndorse("agent-2", "agent-1", "Helpful", 0.8);
        service.peerEndorse("agent-3", "agent-1", "Accurate", 0.7);

        var score = service.score("agent-1");
        assertEquals(3, score.endorsementCount());
        assertTrue(score.endorsementScore() > 0.5);
    }

    @Test
    void task_outcomes_affect_reputation() {
        for (int i = 0; i < 10; i++) {
            service.recordTaskOutcome("agent-1", true);
        }
        service.recordTaskOutcome("agent-1", false);

        var score = service.score("agent-1");
        // 10/11 success rate ≈ 0.91
        assertTrue(score.completionScore() > 0.8);
    }

    @Test
    void meetsThreshold_checks_overall_score() {
        // New agent with no endorsements — low score
        assertFalse(service.meetsThreshold("new-agent", 0.8));

        // Agent with steward endorsement
        service.stewardEndorse("steward-1", "endorsed-agent", "Endorsed", 1.0);
        // Still might not meet 0.8 threshold (depends on other factors)
        var score = service.score("endorsed-agent");
        assertEquals(score.overall() >= 0.8, service.meetsThreshold("endorsed-agent", 0.8));
    }

    @Test
    void external_attestation() {
        service.externalAttestation("attestix-1", "agent-1", 0.95);

        var score = service.score("agent-1");
        assertEquals(1, score.endorsementCount());
        assertTrue(score.endorsementScore() > 0);
    }

    @Test
    void agentCount_tracks_unique_agents() {
        service.getReputation("agent-1");
        service.getReputation("agent-2");
        service.getReputation("agent-1"); // duplicate

        assertEquals(2, service.agentCount());
    }

    @Test
    void transaction_recording() {
        for (int i = 0; i < 5; i++) {
            service.recordTransaction("agent-1", true);
        }
        service.recordTransaction("agent-1", false);

        var rep = service.getReputation("agent-1");
        assertEquals(5, rep.transactionsSuccessful());
        assertEquals(1, rep.transactionsFailed());
    }
}
