package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * family cryptophasia is LIVE: Tier-1 headlines are encoded with the family
 * {@link ArgotCodebook} before they rest in the {@link FamilyLocker} and decoded on read, and the
 * codebook grows from significant items shared during sleep sync. Was inert before this phase
 * ({@link BudSyncService} took a codebook but only read its version).
 */
class FamilyArgotCryptophasiaTest {

    /** A locker whose original bud is authorized (postHeadline / sleepSync require authorization). */
    private static FamilyLocker authorizedLocker(String familyId, String budDid) {
        var bud = SoulBud.original(budDid, "z6MkTest", familyId, "local", "node-1", "model-1");
        return FamilyLocker.create(familyId, "local", bud);
    }

    @Test
    void headlineRoundTripsThroughCryptophasiaAndRestsEncoded() {
        var locker = authorizedLocker("fam-1", "did:bud:a");
        // The family has coined shorthand for two recurring concepts.
        locker.updateFamilyArgot(locker.familyArgot()
            .withContextCodes(Map.of("gardening", ":G", "tired", ":T")));
        var sync = new BudSyncService(locker);

        sync.postHeadline("did:bud:a", "tired after gardening", new double[]{0.7}, 5);

        // At rest in the locker, the summary is the compressed/opaque form — not plain text.
        var raw = locker.allHeadlines().values().iterator().next().summary();
        assertTrue(raw.contains(":T") && raw.contains(":G"), "rests as codes");
        assertFalse(raw.contains("tired"), "the plain concept does not rest in the clear");

        // A family bud reading it gets the concepts back.
        var decoded = sync.readHeadlines("did:bud:a").values().iterator().next().summary();
        assertEquals("tired after gardening", decoded);
    }

    @Test
    void outsiderCannotDecodeAnotherFamilysHeadline() {
        var familyA = new FamilyLocker("fam-A", "local");
        familyA.updateFamilyArgot(familyA.familyArgot().withContextCodes(Map.of("secret", ":X")));
        var wire = familyA.familyArgot().encodeText("secret plan");   // "§/:X plan"
        assertFalse(wire.contains("secret"));

        // A different family — no such code — cannot recover the concept.
        var familyB = new FamilyLocker("fam-B", "local");
        var seenByB = familyB.familyArgot().decodeText(wire);
        assertFalse(seenByB.contains("secret"), "opaque across family boundaries");
        assertTrue(seenByB.contains(":X"), "outsider sees only the code");
    }

    @Test
    void sleepSyncGrowsTheFamilyCodebookFromSharedItems() {
        var locker = authorizedLocker("fam-1", "did:bud:a");
        var sync = new BudSyncService(locker);
        int before = locker.familyArgot().totalCodes();

        var items = List.of(
            SoulItem.create("memory", "alice-bond", "a deep friendship with alice", "did:bud:a", 0.8),
            SoulItem.create("memory", "garden-ritual", "the morning watering routine", "did:bud:a", 0.9));

        var result = sync.sleepSync("did:bud:a", items, List.of(), locker.familyArgot());

        assertTrue(result.codebookUpdates() > 0, "shared significant items mint item codes");
        assertTrue(locker.familyArgot().totalCodes() > before, "the family codebook grew");
        // The minted code decodes back to the item label (cryptophasia compression of that concept).
        var encoded = locker.familyArgot().encodeText("alice-bond");
        assertTrue(encoded.startsWith("#"), "a coined item code");
        assertEquals("alice-bond", locker.familyArgot().decodeText(encoded));
    }

    @Test
    void lowSignificanceItemsDoNotGrowTheCodebook() {
        var locker = authorizedLocker("fam-1", "did:bud:a");
        var sync = new BudSyncService(locker);
        int before = locker.familyArgot().totalCodes();
        // Below the 0.5 significance bar → learnFromItems skips them.
        var trivia = List.of(
            SoulItem.create("memory", "passing-thought", "nothing much", "did:bud:a", 0.2));
        var result = sync.sleepSync("did:bud:a", trivia, List.of(), locker.familyArgot());
        assertEquals(0, result.codebookUpdates());
        assertEquals(before, locker.familyArgot().totalCodes());
    }
}
