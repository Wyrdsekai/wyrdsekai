package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: agent starts with no reputation, earns endorsements
 * and completes tasks, and the reputation score progresses through tiers.
 *
 * Verifies the full pipeline: endorsement → AgentReputation → computeScore →
 * threshold checks that would feed into CompanionActor.computeAgentTier().
 */
class ReputationTierProgressionTest {

    @Test
    void new_agent_starts_with_low_reputation() {
        var rep = new AgentReputation("new-agent", Instant.now());
        var score = rep.computeScore();

        // New agent: age=0 (low), no transactions (0.5 default), no tasks (0.5 default), no endorsements (0.0)
        // overall = 0.0*0.15 + 0.5*0.25 + 0.5*0.30 + 0.0*0.30 = 0.275
        assertTrue(score.overall() < 0.4, "New agent should have low reputation: " + score.overall());
        assertFalse(score.meetsThreshold(0.3), "New agent should not meet tier 1 threshold");
    }

    @Test
    void steward_endorsement_boosts_past_tier1_threshold() {
        var rep = new AgentReputation("endorsed-agent", Instant.now());

        // Steward endorsement with high score
        rep.addEndorsement(new AgentReputation.Endorsement(
            "steward-1", AgentReputation.EndorserType.STEWARD,
            "Graduated from probation", 0.9, Instant.now()));

        var score = rep.computeScore();
        // Endorsement weight: 0.9 * 2.0 (steward) / 2.0 = 0.9
        // overall = 0.0*0.15 + 0.5*0.25 + 0.5*0.30 + 0.9*0.30 = 0.545
        assertTrue(score.overall() >= 0.3,
            "Steward-endorsed agent should meet tier 1 threshold: " + score.overall());
    }

    @Test
    void task_completion_builds_reputation_over_time() {
        var rep = new AgentReputation("worker-agent", Instant.now());

        // Complete 20 tasks successfully, 2 failures
        for (int i = 0; i < 20; i++) rep.recordTaskCompletion(true);
        for (int i = 0; i < 2; i++) rep.recordTaskCompletion(false);

        var score = rep.computeScore();
        // 20/22 ≈ 0.909 completion rate
        assertTrue(score.completionScore() > 0.8,
            "High task completion should give high score: " + score.completionScore());
    }

    @Test
    void full_progression_to_high_reputation() {
        var rep = new AgentReputation("senior-agent", Instant.now());

        // Steward endorsement
        rep.addEndorsement(new AgentReputation.Endorsement(
            "steward-1", AgentReputation.EndorserType.STEWARD,
            "Trusted member", 1.0, Instant.now()));

        // Peer endorsements
        rep.addEndorsement(new AgentReputation.Endorsement(
            "agent-peer-1", AgentReputation.EndorserType.PEER_AGENT,
            "Helpful collaborator", 0.9, Instant.now()));
        rep.addEndorsement(new AgentReputation.Endorsement(
            "agent-peer-2", AgentReputation.EndorserType.PEER_AGENT,
            "Accurate analysis", 0.8, Instant.now()));

        // Many successful tasks
        for (int i = 0; i < 30; i++) rep.recordTaskCompletion(true);
        rep.recordTaskCompletion(false); // one failure

        // Many successful transactions
        for (int i = 0; i < 10; i++) rep.recordTransaction(true);

        var score = rep.computeScore();
        // Should have high reputation across all dimensions
        assertTrue(score.overall() >= 0.6,
            "Full progression should yield high reputation: " + score.overall());
        assertTrue(score.completionScore() > 0.9);
        assertTrue(score.transactionScore() > 0.9);
        assertTrue(score.endorsementScore() > 0.7);
        assertEquals(3, score.endorsementCount());
    }

    @Test
    void attestation_service_tracks_progression() {
        var service = new AttestationService();

        // Phase 1: New agent — low reputation
        var initial = service.score("progressing-agent");
        assertFalse(initial.meetsThreshold(0.4));

        // Phase 2: Steward endorses
        service.stewardEndorse("steward-1", "progressing-agent",
            "Completed first task", 0.8);
        var afterEndorsement = service.score("progressing-agent");
        assertTrue(afterEndorsement.overall() > initial.overall(),
            "Score should increase after endorsement");

        // Phase 3: Complete tasks
        for (int i = 0; i < 10; i++) {
            service.recordTaskOutcome("progressing-agent", true);
        }
        var afterTasks = service.score("progressing-agent");
        assertTrue(afterTasks.overall() > afterEndorsement.overall(),
            "Score should increase with task completions");

        // Phase 4: Peer endorsement
        service.peerEndorse("peer-agent", "progressing-agent",
            "Great work on library search", 0.9);
        var afterPeer = service.score("progressing-agent");
        assertTrue(afterPeer.overall() > afterTasks.overall(),
            "Score should increase with peer endorsement");
        assertEquals(2, afterPeer.endorsementCount());
    }

    @Test
    void failure_rate_degrades_reputation() {
        var rep = new AgentReputation("failing-agent", Instant.now());

        // Agent fails most tasks
        for (int i = 0; i < 3; i++) rep.recordTaskCompletion(true);
        for (int i = 0; i < 7; i++) rep.recordTaskCompletion(false);

        var score = rep.computeScore();
        // 3/10 = 0.30 completion rate
        assertTrue(score.completionScore() < 0.4,
            "High failure rate should degrade completion score: " + score.completionScore());
    }

    @Test
    void endorser_type_weights_matter() {
        // Steward endorsement worth 2x, peer 1x, external 0.5x, attestation 1.5x
        var stewardOnly = new AgentReputation("s-agent", Instant.now());
        stewardOnly.addEndorsement(new AgentReputation.Endorsement(
            "s1", AgentReputation.EndorserType.STEWARD, "ok", 0.8, Instant.now()));

        var peerOnly = new AgentReputation("p-agent", Instant.now());
        peerOnly.addEndorsement(new AgentReputation.Endorsement(
            "p1", AgentReputation.EndorserType.PEER_AGENT, "ok", 0.8, Instant.now()));

        var externalOnly = new AgentReputation("e-agent", Instant.now());
        externalOnly.addEndorsement(new AgentReputation.Endorsement(
            "e1", AgentReputation.EndorserType.EXTERNAL_AGENT, "ok", 0.8, Instant.now()));

        // Same score (0.8), different weights → different endorsement scores
        // But since each has exactly 1 endorsement, the weighted average is just 0.8
        // The weight matters when mixing types
        var mixed = new AgentReputation("m-agent", Instant.now());
        mixed.addEndorsement(new AgentReputation.Endorsement(
            "s1", AgentReputation.EndorserType.STEWARD, "ok", 1.0, Instant.now()));
        mixed.addEndorsement(new AgentReputation.Endorsement(
            "e1", AgentReputation.EndorserType.EXTERNAL_AGENT, "ok", 0.2, Instant.now()));

        var mixedScore = mixed.computeScore();
        // Weighted: (1.0*2.0 + 0.2*0.5) / (2.0 + 0.5) = 2.1/2.5 = 0.84
        assertTrue(mixedScore.endorsementScore() > 0.7,
            "Steward endorsement should outweigh low external: " + mixedScore.endorsementScore());
    }
}
