package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FederationSubjectsTest {

    private static final String FP = "z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

    // ── legacy form ───────────────────────────────────────────────────

    @Test void legacyGate_concatenatesZoneIdAndAction() {
        assertEquals("federation.beta.gate.propose",
            FederationSubjects.legacyGate("beta", "propose"));
        assertEquals("federation.beta.gate.accept",
            FederationSubjects.legacyGate("beta", "accept"));
        assertEquals("federation.beta.gate.manifest",
            FederationSubjects.legacyGate("beta", "manifest"));
    }

    @Test void legacyGatePattern_usesWildcard() {
        assertEquals("federation.beta.gate.>",
            FederationSubjects.legacyGatePattern("beta"));
    }

    @Test void legacyTell() {
        assertEquals("federation.beta.tell",
            FederationSubjects.legacyTell("beta"));
    }

    // ── canonical form ────────────────────────────────────────────────

    @Test void canonicalGate_embedsFingerprintAndLabel() {
        var addr = new ZoneAddress(FP, "kitchen");
        assertEquals("federation." + FP + ".kitchen.gate.propose",
            FederationSubjects.canonicalGate(addr, "propose"));
    }

    @Test void canonicalGatePattern() {
        var addr = new ZoneAddress(FP, "kitchen");
        assertEquals("federation." + FP + ".kitchen.gate.>",
            FederationSubjects.canonicalGatePattern(addr));
    }

    @Test void canonicalTell() {
        var addr = new ZoneAddress(FP, "kitchen");
        assertEquals("federation." + FP + ".kitchen.tell",
            FederationSubjects.canonicalTell(addr));
    }

    // ── format contract ───────────────────────────────────────────────

    @Test void allSubjectsStartWithFederationPrefix() {
        var addr = new ZoneAddress(FP, "kitchen");
        assertTrue(FederationSubjects.legacyGate("beta", "propose")
            .startsWith(FederationSubjects.FEDERATION_PREFIX));
        assertTrue(FederationSubjects.canonicalGate(addr, "propose")
            .startsWith(FederationSubjects.FEDERATION_PREFIX));
        assertTrue(FederationSubjects.legacyTell("beta")
            .startsWith(FederationSubjects.FEDERATION_PREFIX));
        assertTrue(FederationSubjects.canonicalTell(addr)
            .startsWith(FederationSubjects.FEDERATION_PREFIX));
    }

    @Test void legacyAndCanonicalAreDistinct() {
        // A receiver dual-subscribed to both forms must see distinct
        // patterns, so NATS can register them as separate subscriptions.
        var addr = new ZoneAddress(FP, "kitchen");
        var legacy = FederationSubjects.legacyGatePattern("kitchen");
        var canonical = FederationSubjects.canonicalGatePattern(addr);
        assertNotEquals(legacy, canonical);
    }

    @Test void isFederationSubject_matchesBothForms() {
        assertTrue(FederationSubjects.isFederationSubject("federation.beta.gate.propose"));
        assertTrue(FederationSubjects.isFederationSubject(
            "federation." + FP + ".kitchen.gate.propose"));
        assertTrue(FederationSubjects.isFederationSubject("federation.inference.beta.complete"));
        assertFalse(FederationSubjects.isFederationSubject("between.beta.gate.propose"));
        assertFalse(FederationSubjects.isFederationSubject(""));
        assertFalse(FederationSubjects.isFederationSubject(null));
    }

    // ── NATS safety ───────────────────────────────────────────────────

    @Test void subjectsContainNoInvalidCharacters() {
        // NATS subjects must be alphanumerics + `.` + `_` + `-`. Labels are
        // validated to be charset-safe by ZoneLabels; fingerprints are
        // base58btc (alphanumerics). Spot-check that the output contains no
        // spaces, slashes, etc.
        var addr = new ZoneAddress(FP, "bob-studio");
        var subject = FederationSubjects.canonicalGate(addr, "propose");
        assertFalse(subject.contains(" "));
        assertFalse(subject.contains("/"));
        assertFalse(subject.contains("*"));
        assertFalse(subject.contains(">"));
    }
}
