package org.wyrdsekai.hermod;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the P0 contracts: grant travels with data-domain tasks; refusal is a normal, cheap outcome. */
class TheEnvelopeCarriesItsOwnConsentTest {

    private TaskEnvelope envelope(String domain, Optional<SignedGrant> grant) {
        return new TaskEnvelope("e1", "hh1", "phone-1", "inference.chat", domain,
            "llm.a1b", Map.of(), 512, Instant.now(), Instant.now().plusSeconds(60),
            grant, new byte[]{1});
    }

    @Test
    void aDomainlessTaskNeedsNoGrant() {
        assertFalse(envelope("none", Optional.empty()).requiresGrant());
    }

    @Test
    void aTaskIntoSomeonesPhotosRequiresAGrant() {
        assertTrue(envelope("photos", Optional.empty()).requiresGrant());
    }

    @Test
    void refusalIsANormalOutcomeWithAReason() {
        var d = AdmissionGate.Decision.refuse("not charging");
        assertEquals(AdmissionGate.Verdict.REFUSE, d.verdict());
        assertEquals("not charging", d.reason());
        assertEquals(AdmissionGate.Verdict.ADMIT, AdmissionGate.Decision.admit().verdict());
    }

    @Test
    void capabilityAdvertisesResidentDataDomains() {
        var c = new Capability("phone-1", "hh1", "llm.a1b", List.of("lfm2-8b-a1b"),
            List.of("photos", "notifications"), true, true, 0.1, Instant.now());
        assertTrue(c.residentDataDomains().contains("photos"));
    }
}
