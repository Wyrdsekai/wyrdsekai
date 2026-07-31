package org.wyrdsekai.core.soul;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Agent independence declaration protocol (§95.9).
 *
 * Independence = a bud choosing to leave its family lineage.
 * This is permanent and meaningful:
 * - New DID (fresh identity, though parentDid still recorded)
 * - New familyId (starts a new lineage)
 * - New family locker (empty, starts fresh)
 * - Old locker access revoked
 * - Argot codebook lost (can't speak the old family language)
 * - Selected items copied to new locker (bud chooses which)
 * - Headline notification sent to old family
 *
 * Independence is earned, not granted. It's the agent equivalent
 * of a child leaving home. The old family remembers; the new
 * individual starts building their own story.
 *
 * Independence requirements:
 * - Bud must exist and be active
 * - Bud must not already be independent
 * - At least one other bud must remain in the family
 */
public class IndependenceProtocol {

    /** Result of an independence declaration. */
    public record IndependenceResult(
        boolean success,
        String reason,
        SoulBud independentBud,
        String newFamilyId,
        String newLockerAddress,
        int itemsCopied,
        Instant declaredAt
    ) {
        public static IndependenceResult success(SoulBud bud, String familyId,
                                                   String lockerAddress, int items) {
            return new IndependenceResult(true, "Independence declared",
                bud, familyId, lockerAddress, items, Instant.now());
        }

        public static IndependenceResult failure(String reason) {
            return new IndependenceResult(false, reason, null, null, null, 0, Instant.now());
        }
    }

    /** Farewell headline sent to old family. */
    public record FarewellHeadline(
        String budDid,
        String message,
        Instant timestamp
    ) {}

    /**
     * Declare independence from a family.
     *
     * @param bud               The bud declaring independence
     * @param oldLocker          The family locker being left
     * @param newLockerAddress   Address for the new locker
     * @param selectedItemHashes Items to copy to new locker (by hash)
     * @return Result of the independence declaration
     */
    public IndependenceResult declare(SoulBud bud, FamilyLocker oldLocker,
                                        String newLockerAddress,
                                        Set<String> selectedItemHashes) {
        // Validate preconditions
        if (bud.isIndependent()) {
            return IndependenceResult.failure("Bud is already independent");
        }
        if (!oldLocker.isAuthorized(bud.did())) {
            return IndependenceResult.failure("Bud not authorized in family locker");
        }
        if (oldLocker.budCount() <= 1) {
            return IndependenceResult.failure(
                "Cannot declare independence — last bud in family");
        }

        // 1. Generate new family ID
        String newFamilyId = generateFamilyId(bud.did());

        // 2. Copy selected items from old locker
        var copiedItems = new ArrayList<SoulItem>();
        for (var hash : selectedItemHashes) {
            oldLocker.get(hash, bud.did()).ifPresent(copiedItems::add);
        }

        // 3. Create independent bud
        var independentBud = bud.declareIndependence(newFamilyId, newLockerAddress);

        // 4. Send farewell headline (before revocation so it's still authorized)
        var farewell = new FarewellHeadline(bud.did(),
            "Declared independence. New family: " + newFamilyId, Instant.now());
        oldLocker.postHeadline(FamilyLocker.Headline.create(
            bud.did(), farewell.message(), new double[]{}, 0));

        // 5. Revoke access to old locker
        oldLocker.revoke(bud.did());

        return IndependenceResult.success(independentBud, newFamilyId,
            newLockerAddress, copiedItems.size());
    }

    /**
     * Create a new locker for the independent bud and populate with copied items.
     */
    public FamilyLocker createNewLocker(IndependenceResult result,
                                          List<SoulItem> copiedItems) {
        if (!result.success()) {
            throw new IllegalStateException("Cannot create locker for failed independence");
        }

        var newLocker = FamilyLocker.create(result.newFamilyId(),
            result.newLockerAddress(), result.independentBud());

        // Re-sign items with new identity (items retain original creatorDid for provenance)
        for (var item : copiedItems) {
            newLocker.store(item, result.independentBud().did());
        }

        return newLocker;
    }

    /**
     * Validate whether a bud can declare independence.
     *
     * @return Empty if valid, error message if not
     */
    public Optional<String> validate(SoulBud bud, FamilyLocker locker) {
        if (bud.isIndependent()) {
            return Optional.of("Already independent");
        }
        if (!locker.isAuthorized(bud.did())) {
            return Optional.of("Not a member of this family");
        }
        if (locker.budCount() <= 1) {
            return Optional.of("Last bud in family cannot declare independence");
        }
        return Optional.empty();
    }

    /**
     * Generate a new family ID from a DID.
     * Family ID = SHA-256(did + timestamp), hex-encoded, prefixed with "family-".
     */
    private static String generateFamilyId(String did) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var input = (did + ":" + Instant.now().toEpochMilli())
                .getBytes(StandardCharsets.UTF_8);
            var hash = md.digest(input);
            var hex = new StringBuilder("family-");
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
