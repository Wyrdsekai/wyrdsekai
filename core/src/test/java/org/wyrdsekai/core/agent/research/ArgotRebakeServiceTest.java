package org.wyrdsekai.core.agent.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (#1182) — the re-bake decision/prepare/complete loop and its security invariants.
 * Proves the LIVING-LANGUAGE evolution loop closes: a promoted term drifts the codebook → a re-bake
 * is warranted → its promoted concepts flow to the corpus params → on success the version is marked
 * baked (drift resets). And that the derived key never lands in recipe params, lives only in a 0600
 * keyfile, and is shredded on completion.
 */
class ArgotRebakeServiceTest {

    /** Drive a zone to a drifted state by promoting one emergent term. */
    private static ZoneArgotService driftedZone(String zone, String term) {
        var svc = new ZoneArgotService();
        svc.ensureZone(zone);                       // version 1, baked default 1 → drift 0
        svc.noteCandidate(zone, term, "agent-a");
        svc.noteCandidate(zone, term, "agent-b");   // 2 adopters
        var promo = svc.calibrate(zone, 2);         // promote → version 2
        assertTrue(promo.promoted().contains(term), "term should promote");
        assertEquals(1, svc.driftSinceBake(zone), "one version of drift since bake");
        return svc;
    }

    @Test
    void driftBelowThresholdDoesNotRebake() throws Exception {
        var svc = new ZoneArgotService();
        svc.ensureZone("zone-x");                   // drift 0
        assertFalse(ArgotRebakeService.shouldRebake(svc, "zone-x", 1));
        var plan = ArgotRebakeService.prepare(svc, "zone-x", 1, z -> new byte[32], Path.of("/tmp"));
        assertFalse(plan.shouldRebake());
        assertNull(plan.keyFile());
    }

    @Test
    void publicSeedZoneSkipsRebakeEvenWhenDrifted(@TempDir Path dir) throws Exception {
        var svc = driftedZone("zone-pub", "beacon");
        assertTrue(ArgotRebakeService.shouldRebake(svc, "zone-pub", 1));
        // No secret master installed → deriver returns null → a bake over public tokens adds nothing.
        var plan = ArgotRebakeService.prepare(svc, "zone-pub", 1, z -> null, dir);
        assertFalse(plan.shouldRebake(), "public-seed zone must not enqueue a re-bake");
        assertNull(plan.keyFile());
        assertEquals(0, Files.list(dir).count(), "no keyfile written when skipping");
    }

    @Test
    void preparedPlanCarriesPromotedConceptsButNeverTheKey(@TempDir Path dir) throws Exception {
        var svc = driftedZone("zone-alpha", "beacon");
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i + 1);
        String keyHex = HexFormat.of().formatHex(key);

        var plan = ArgotRebakeService.prepare(svc, "zone-alpha", 1, z -> key.clone(), dir);

        assertTrue(plan.shouldRebake());
        assertEquals(1, plan.drift());
        assertTrue(plan.promotedConcepts().contains("beacon"), "grown term flows to the corpus");
        // Params carry the zone, the keyFILE PATH, and the promoted concepts — but NOT the key.
        assertEquals("zone-alpha", plan.params().get("zone_id"));
        assertEquals(plan.keyFile().toString(), plan.params().get("argot_key_file"));
        assertTrue(((String) plan.params().get("promoted_concepts")).contains("beacon"));
        assertFalse(plan.params().toString().contains(keyHex),
            "the derived key must NEVER appear in recipe params (persists to world.db)");

        // The key lives only in the 0600 keyfile, content == hex(key).
        assertEquals(keyHex, Files.readString(plan.keyFile()).trim());
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(plan.keyFile());
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
                "keyfile must be 0600");
        } catch (UnsupportedOperationException nonPosix) {
            // non-POSIX FS — permission assertion not applicable
        }
    }

    @Test
    void completeOnSuccessMarksBakedAndShredsKey(@TempDir Path dir) throws Exception {
        var svc = driftedZone("zone-alpha", "beacon");
        var plan = ArgotRebakeService.prepare(svc, "zone-alpha", 1, z -> new byte[]{1, 2, 3, 4}, dir);
        assertTrue(Files.exists(plan.keyFile()));

        ArgotRebakeService.complete(svc, "zone-alpha", true, plan.keyFile());

        assertEquals(0, svc.driftSinceBake("zone-alpha"), "success marks the new version baked");
        assertFalse(Files.exists(plan.keyFile()), "keyfile shredded on completion");
        // And a fresh decision no longer wants a re-bake.
        assertFalse(ArgotRebakeService.shouldRebake(svc, "zone-alpha", 1));
    }

    @Test
    void completeOnFailureKeepsDriftButStillShredsKey(@TempDir Path dir) throws Exception {
        var svc = driftedZone("zone-alpha", "beacon");
        var plan = ArgotRebakeService.prepare(svc, "zone-alpha", 1, z -> new byte[]{9, 9, 9, 9}, dir);

        ArgotRebakeService.complete(svc, "zone-alpha", false, plan.keyFile());

        assertEquals(1, svc.driftSinceBake("zone-alpha"), "failed bake leaves drift so it re-fires");
        assertFalse(Files.exists(plan.keyFile()), "keyfile shredded even on failure (no key left on disk)");
    }

    @Test
    void completeAtCapturedVersionLeavesDriftWhenCodebookGrewDuringBake(@TempDir Path dir) throws Exception {
        // P6 correctness: the adapter is scoped to the version at ENQUEUE; if a term promotes while the
        // (~30-min) bake runs, marking the CURRENT version baked would silently swallow it. The
        // version-aware complete() records only the captured version, so drift to the new term survives.
        var svc = driftedZone("zone-alpha", "beacon");        // codebook v2, baked 1 → drift 1
        int bakeVersion = svc.codebookVersion("zone-alpha");  // 2 — what the adapter is scoped to
        var plan = ArgotRebakeService.prepare(svc, "zone-alpha", 1, z -> new byte[]{7}, dir);

        // A new term promotes DURING the bake → codebook v3.
        svc.noteCandidate("zone-alpha", "rendezvous", "a");
        svc.noteCandidate("zone-alpha", "rendezvous", "b");
        svc.calibrate("zone-alpha", 2);
        assertEquals(3, svc.codebookVersion("zone-alpha"));

        // Bake of the v2 adapter completes. Version-aware complete records v2, not v3.
        ArgotRebakeService.complete(svc, "zone-alpha", true, plan.keyFile(), bakeVersion);
        assertEquals(1, svc.driftSinceBake("zone-alpha"),
            "the term promoted during the bake is NOT counted as baked — drift survives, re-fires");
        assertFalse(Files.exists(plan.keyFile()), "keyfile shredded");

        // Contrast: the version-unaware complete() would have over-claimed (drift 0).
        ArgotRebakeService.complete(svc, "zone-alpha", true, null);   // marks current (v3)
        assertEquals(0, svc.driftSinceBake("zone-alpha"));
    }

    @Test
    void evolutionLoopConvergesOverTwoPromotions(@TempDir Path dir) throws Exception {
        // term1 promoted → bake → baked. Then term2 promoted → drift again → bake again.
        var svc = driftedZone("zone-evo", "beacon");
        var p1 = ArgotRebakeService.prepare(svc, "zone-evo", 1, z -> new byte[]{1}, dir);
        assertTrue(p1.shouldRebake());
        ArgotRebakeService.complete(svc, "zone-evo", true, p1.keyFile());
        assertEquals(0, svc.driftSinceBake("zone-evo"));

        svc.noteCandidate("zone-evo", "rendezvous", "a");
        svc.noteCandidate("zone-evo", "rendezvous", "b");
        svc.calibrate("zone-evo", 2);
        assertEquals(1, svc.driftSinceBake("zone-evo"), "second promotion re-drifts");

        var p2 = ArgotRebakeService.prepare(svc, "zone-evo", 1, z -> new byte[]{2}, dir);
        assertTrue(p2.shouldRebake());
        assertTrue(p2.promotedConcepts().contains("rendezvous"));
        assertTrue(p2.promotedConcepts().contains("beacon"), "grown codebook keeps prior terms too");
        ArgotRebakeService.complete(svc, "zone-evo", true, p2.keyFile());
        assertEquals(0, svc.driftSinceBake("zone-evo"));
    }
}
