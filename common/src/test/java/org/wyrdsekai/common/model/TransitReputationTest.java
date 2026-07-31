package org.wyrdsekai.common.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransitReputationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void empty_defaults_correctly() {
        var rep = TransitReputation.empty("did:key:abc", "alpha");
        assertEquals("did:key:abc", rep.entityDid());
        assertEquals("alpha", rep.homeZone());
        assertEquals(0, rep.ageDays());
        assertEquals(0.0, rep.compositeScore());
    }

    @Test
    void permission_tier_based_on_score() {
        assertEquals("tourist", TransitReputation.empty("a", "z").permissionTier());
        var known = new TransitReputation("a", 10, 0, 1, 0, 0.3, "z");
        assertEquals("known", known.permissionTier());
        var verified = new TransitReputation("a", 30, 1, 2, 0, 0.6, "z");
        assertEquals("verified", verified.permissionTier());
        var trusted = new TransitReputation("a", 100, 3, 5, 1, 0.9, "z");
        assertEquals("trusted", trusted.permissionTier());
    }

    @Test
    void serialization_roundtrip() throws Exception {
        var rep = new TransitReputation("did:key:abc", 45, 1, 3, 0, 0.72, "alpha");
        var json = mapper.writeValueAsString(rep);
        var restored = mapper.readValue(json, TransitReputation.class);
        assertEquals(rep, restored);
    }

    @Test
    void deserialization_handles_missing_fields() throws Exception {
        var json = """
            {"entityDid":"did:key:abc","homeZone":"alpha"}
            """;
        var rep = mapper.readValue(json, TransitReputation.class);
        assertEquals("did:key:abc", rep.entityDid());
        assertEquals("alpha", rep.homeZone());
        assertEquals(0, rep.ageDays());
    }
}
