package org.wyrdsekai.core.agent.research;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * / — Layer A in the live path. Asserts the properties the runtime
 * relies on at the send/receive seams: same-zone round-trip (comprehension), cross-zone
 * opacity (a zone cannot read another zone's wire), and the forge verdict (argot tokens that
 * don't resolve under our codebook are NOT trusted).
 */
class ZoneArgotServiceTest {

    @Test
    void sameZoneRoundTripRestoresConcepts() {
        var svc = new ZoneArgotService();
        var plain = "i need help here now";
        var encoded = svc.encodeForPeer("zone-a", plain);
        assertNotEquals(plain, encoded, "coordination words should be tokenized");
        assertTrue(encoded.contains("§"), "encoded wire should bear opaque tokens");

        var recv = svc.decodeFromPeer("zone-a", encoded);
        assertTrue(recv.isArgot());
        assertTrue(recv.trusted(), "same-zone argot must decode cleanly");
        assertEquals(plain, recv.text(), "round-trip restores the original concepts");
    }

    @Test
    void differentZonesProduceDifferentTokensForSameConcept() {
        var a = new ZoneArgotService();
        var b = new ZoneArgotService();
        var ea = a.encodeForPeer("zone-a", "help");
        var eb = b.encodeForPeer("zone-b", "help");
        assertNotEquals(ea, eb, "zone boundary = language boundary: same concept, different token");
    }

    @Test
    void crossZoneArgotIsOpaqueAndUntrusted() {
        // Zone A authors argot; a Zone B agent tries to read it under ITS codebook.
        var author = new ZoneArgotService();
        var wire = author.encodeForPeer("zone-a", "danger come now");

        var reader = new ZoneArgotService();
        var recv = reader.decodeFromPeer("zone-b", wire);
        assertTrue(recv.isArgot(), "the reader sees argot tokens");
        assertFalse(recv.trusted(), "but they don't resolve under zone-b's codebook → not trusted");
        // Opacity: zone-b does NOT recover zone-a's concepts.
        assertFalse(recv.text().contains("danger"));
        assertFalse(recv.text().contains("come"));
    }

    @Test
    void plainNaturalLanguagePassesThroughTrustedNotArgot() {
        var svc = new ZoneArgotService();
        var recv = svc.decodeFromPeer("zone-a", "just an ordinary sentence about the weather");
        assertFalse(recv.isArgot(), "no argot tokens → not argot");
        assertTrue(recv.trusted(), "ordinary NL is not claiming to be coordination");
        assertEquals("just an ordinary sentence about the weather", recv.text());
    }

    @Test
    void forgedTokensAreReportedUntrusted() {
        var svc = new ZoneArgotService();
        svc.ensureZone("zone-a");
        // A fabricated §-token that was never minted by zone-a's codebook.
        var recv = svc.decodeFromPeer("zone-a", "§deadbeef please");
        assertTrue(recv.isArgot());
        assertFalse(recv.trusted(), "unminted token → forge verdict");
    }

    @Test
    void unknownZoneEncodeIsNoOp() {
        var svc = new ZoneArgotService();
        assertEquals("help", svc.encodeForPeer(null, "help"));
        assertEquals("help", svc.encodeForPeer("", "help"));
    }

    @Test
    void seedingIsIdempotentAndDeterministic() {
        var svc = new ZoneArgotService();
        var first = svc.encodeForPeer("zone-a", "help");
        svc.ensureZone("zone-a");                       // re-ensure
        var second = svc.encodeForPeer("zone-a", "help");
        assertEquals(first, second, "re-seeding the same zone yields the identical codebook");
        assertEquals(1, svc.seededZoneCount());
    }

    // ── P2: the living lexicon ──────────────────────────────────────────────────────────────

    @Test
    void secretKeyProviderMakesTokensUncomputableFromThePublicSeed() {
        try {
            var publicTok = new ZoneArgotService().encodeForPeer("zone-x", "help");
            // Install a secret-derived key for zone-x (as the zone-secret subsystem would at boot).
            ZoneArgotService.setArgotKeyProvider(z ->
                "zone-x".equals(z) ? new byte[]{7, 7, 7, 7, 7, 7, 7, 7} : null);
            var secretA = new ZoneArgotService().encodeForPeer("zone-x", "help");
            var secretB = new ZoneArgotService().encodeForPeer("zone-x", "help");
            assertNotEquals(publicTok, secretA, "secret seed → tokens not computable from the public seed");
            assertEquals(secretA, secretB, "same secret key → same tokens (same-zone comprehension)");
            // A node holding a DIFFERENT key computes different tokens (opacity vs wrong/absent key).
            ZoneArgotService.setArgotKeyProvider(z -> new byte[]{1, 1, 1, 1, 1, 1, 1, 1});
            assertNotEquals(secretA, new ZoneArgotService().encodeForPeer("zone-x", "help"));
        } finally {
            ZoneArgotService.setArgotKeyProvider(null);   // don't leak static state to other tests
        }
    }

    @Test
    void tokenDerivationMatchesTheCorpusGeneratorContract() {
        // Byte-exact lock against scripts/training/argot/generate_argot_corpus.py:
        // token_for("zone-alpha", "help") == §139681e9. The P4 adapter is trained on the Python
        // generator's output; if the Java runtime derived a different token it would decode the
        // adapter's argot as garbage. This test fails the build if either side drifts.
        var svc = new ZoneArgotService();
        assertEquals("§139681e9", svc.encodeForPeer("zone-alpha", "help"));
    }

    @Test
    void calibratePromotesWidelyAdoptedCandidateAndItBecomesDecodable() {
        var svc = new ZoneArgotService();
        svc.ensureZone("zone-a");
        assertEquals(1, svc.codebookVersion("zone-a"));
        // "rendezvous" isn't in the base vocabulary; two agents adopt it.
        svc.noteCandidate("zone-a", "rendezvous", "alice");
        svc.noteCandidate("zone-a", "rendezvous", "bjorn");

        var promo = svc.calibrate("zone-a", 2);
        assertTrue(promo.promoted().contains("rendezvous"), "widely-adopted candidate is promoted");
        assertEquals(2, promo.newVersion(), "codebook version bumps");

        // Now it tokenizes AND round-trips under the zone codebook.
        var wire = svc.encodeForPeer("zone-a", "rendezvous now");
        assertTrue(wire.contains("§"));
        var recv = svc.decodeFromPeer("zone-a", wire);
        assertTrue(recv.trusted());
        assertEquals("rendezvous now", recv.text());
    }

    @Test
    void belowThresholdIsNotPromoted() {
        var svc = new ZoneArgotService();
        svc.ensureZone("zone-a");
        svc.noteCandidate("zone-a", "ephemeris", "alice");      // only one adopter
        var promo = svc.calibrate("zone-a", 2);
        assertTrue(promo.promoted().isEmpty());
        assertEquals(1, svc.codebookVersion("zone-a"), "no growth → version unchanged");
    }

    @Test
    void calibrateIsIdempotentOnceCandidatesStopGrowing() {
        var svc = new ZoneArgotService();
        svc.noteCandidate("zone-a", "lantern", "a");
        svc.noteCandidate("zone-a", "lantern", "b");
        svc.calibrate("zone-a", 2);
        int v = svc.codebookVersion("zone-a");
        var again = svc.calibrate("zone-a", 2);
        assertTrue(again.promoted().isEmpty(), "nothing new to promote");
        assertEquals(v, svc.codebookVersion("zone-a"), "version steady when no new candidates");
    }

    @Test
    void driftTracksPromotionsSinceLastBake() {
        var svc = new ZoneArgotService();
        svc.ensureZone("zone-a");
        assertEquals(0, svc.driftSinceBake("zone-a"));
        svc.noteCandidate("zone-a", "harbor", "a");
        svc.noteCandidate("zone-a", "harbor", "b");
        svc.calibrate("zone-a", 2);
        assertEquals(1, svc.driftSinceBake("zone-a"), "one version of drift since seed");
        svc.markBaked("zone-a");
        assertEquals(0, svc.driftSinceBake("zone-a"), "bake resets drift");
    }

    @Test
    void twoZonesGrowDivergentVocabularies() {
        var svc = new ZoneArgotService();
        // Zone A's society coins "tide"; Zone B's coins "ember".
        svc.noteCandidate("zone-a", "tide", "a1");
        svc.noteCandidate("zone-a", "tide", "a2");
        svc.noteCandidate("zone-b", "ember", "b1");
        svc.noteCandidate("zone-b", "ember", "b2");
        svc.calibrate("zone-a", 2);
        svc.calibrate("zone-b", 2);

        // Each zone tokenizes ITS coined word but not the other's.
        assertTrue(svc.encodeForPeer("zone-a", "tide").contains("§"));
        assertEquals("ember", svc.encodeForPeer("zone-a", "ember"), "zone-a never learned 'ember'");
        assertTrue(svc.encodeForPeer("zone-b", "ember").contains("§"));
        assertEquals("tide", svc.encodeForPeer("zone-b", "tide"), "zone-b never learned 'tide'");
    }

    @Test
    void observeCoordinationHarvestsContentWordsNotStopwordsOrTokens() {
        var svc = new ZoneArgotService();
        svc.ensureZone("zone-a");
        svc.observeCoordination("zone-a", "the harbor is over §deadbeef there", "a");
        svc.observeCoordination("zone-a", "harbor, again!", "b");
        // "harbor" seen by two agents → promotable; "the/is/over/there" are stopwords/short.
        var promo = svc.calibrate("zone-a", 2);
        assertTrue(promo.promoted().contains("harbor"));
        assertFalse(promo.promoted().contains("the"));
        assertFalse(promo.promoted().contains("deadbeef"), "argot tokens are not harvested as words");
    }
}
