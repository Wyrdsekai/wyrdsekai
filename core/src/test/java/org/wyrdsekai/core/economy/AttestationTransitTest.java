package org.wyrdsekai.core.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.TransitReputation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: AttestationService serializes reputation for cross-zone transit.
 */
class AttestationTransitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        AttestationService.init();
    }

    @Test
    void empty_reputation_serializes() throws Exception {
        var rep = AttestationService.get().serializeForTransit("did:key:new", "alpha");
        assertEquals("did:key:new", rep.entityDid());
        assertEquals("alpha", rep.homeZone());
        assertEquals(0, rep.stewardCount());
        assertEquals("tourist", rep.permissionTier());

        // Should be serializable
        var json = mapper.writeValueAsString(rep);
        var restored = mapper.readValue(json,
            TransitReputation.class);
        assertEquals(rep, restored);
    }

    @Test
    void reputation_includes_endorsements() {
        var agentDid = "did:key:alice";
        AttestationService.get().stewardEndorse(
            "did:key:steward", agentDid, "Good work", 0.9);
        AttestationService.get().peerEndorse(
            "did:key:bob", agentDid, "Helpful", 0.7);

        var rep = AttestationService.get().serializeForTransit(agentDid, "alpha");
        assertEquals(1, rep.stewardCount());
        assertEquals(1, rep.peerCount());
        assertEquals("alpha", rep.homeZone());
        assertTrue(rep.compositeScore() > 0);
    }

    @Test
    void reputation_tier_reflects_score() {
        var agentDid = "did:key:veteran";
        // Multiple strong endorsements → trusted tier
        AttestationService.get().stewardEndorse("did:key:s1", agentDid, "excellent", 1.0);
        AttestationService.get().stewardEndorse("did:key:s2", agentDid, "reliable", 1.0);
        for (int i = 0; i < 10; i++) {
            AttestationService.get().peerEndorse("did:key:p" + i, agentDid, "great", 0.9);
            AttestationService.get().recordTaskOutcome(agentDid, true);
            AttestationService.get().recordTransaction(agentDid, true);
        }

        var rep = AttestationService.get().serializeForTransit(agentDid, "alpha");
        assertTrue(rep.compositeScore() > 0.4,
            "Score should be elevated: " + rep.compositeScore());
        assertTrue(rep.stewardCount() >= 2);
        assertTrue(rep.peerCount() >= 10);
    }
}
