package org.wyrdsekai.core.familiar;

import org.apache.pekko.actor.typed.ActorRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondState;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.BondholderPosture;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * the first-summon ceremony service.
 *
 * <p>Composes three side-effects into one idempotent operation:</p>
 * <ol>
 *   <li>Create the {@link CodingFamiliarIdentity} (if absent) and persist
 *       it via {@link CodingFamiliarRegistry}. The soul-fragment file is
 *       the load-bearing piece — the familiar exists when its file exists.</li>
 *   <li>Fire {@link ZoneGuardian.ProvisionCodeZaikuWorkshop} so the
 *       bondholder's workshop room comes into being. {@code seedRoom()} is
 *       idempotent at the journal layer, so a second summon is a no-op
 *       there.</li>
 *   <li>Register an identity-bond between bondholder and familiar via
 *       {@link BondStore} so the familiar surfaces on the Shelf furnishing
 *       (§3.1) and the existing welfare/empathy infrastructure recognizes
 *       the relationship.</li>
 * </ol>
 *
 * <p>This service is the boundary between "infrastructure exists" (#903)
 * and "the bondholder can actually summon" (UX layer — CLI command, Study
 * furnishing, agent action). Each UX wrapper calls
 * {@link #firstSummon} and translates the {@link Outcome} into its own
 * vocabulary.</p>
 *
 * <p>Re-summon semantics: when the identity already exists, the ceremony
 * is a no-op that returns the existing identity with
 * {@link Outcome#alreadyExisted()} {@code = true}. The room and bond are
 * also touched idempotently (the room because {@code seedRoom} is journal-
 * safe; the bond because {@link BondStore#save} is an UPSERT). Callers
 * that need a re-name flow should use
 * {@link CodingFamiliarRegistry#save} directly with
 * {@link CodingFamiliarIdentity#withName} — that's not a ceremony, it's
 * an update.</p>
 *
 * <p>OPEN-3 note: the {@link CodingFamiliarIdentity#autonomyTier()} is
 * the substrate-side gate. The CodeZaiku PermissionRing remains a
 * separate, runtime-side gate on the CodeZaiku session. The bridge
 * between the two is documented on the spec; nothing here couples them.</p>
 */
public final class CodingFamiliarSummoner {

    private static final Logger log = LoggerFactory.getLogger(CodingFamiliarSummoner.class);

    /** Identity-bond depth at first summon. */
    public static final Bond.BondDepth INITIAL_BOND_DEPTH = Bond.BondDepth.ITEM;

    /** Bond ID format. Deterministic so multiple summons converge on the same row. */
    public static final String BOND_ID_PREFIX = "coding-familiar:";

    private final CodingFamiliarRegistry registry;
    private final BondStore bondStore;
    private final ActorRef<ZoneGuardian.Command> zoneGuardian;

    /**
     * @param registry     identity persistence
     * @param bondStore    bond persistence; may be {@code null} in
     *                     test fixtures that don't exercise the bond
     *                     surface, in which case the ceremony skips the
     *                     bond step and logs a warning
     * @param zoneGuardian zone-guardian ref to receive
     *                     {@link ZoneGuardian.ProvisionCodeZaikuWorkshop}.
     *                     May be {@code null} in tests that don't need
     *                     room provisioning.
     */
    public CodingFamiliarSummoner(CodingFamiliarRegistry registry,
                                   BondStore bondStore,
                                   ActorRef<ZoneGuardian.Command> zoneGuardian) {
        if (registry == null) {
            throw new IllegalArgumentException("registry required");
        }
        this.registry = registry;
        this.bondStore = bondStore;
        this.zoneGuardian = zoneGuardian;
    }

    /**
     * Outcome of the ceremony — what changed, what the familiar's identity
     * looks like now, and a human-readable narration suitable for surface
     * UX. The narration is intentionally first-person from the
     * bondholder's vantage so CLI / Study furnishing / agent narration can
     * all share text.
     */
    public record Outcome(
        CodingFamiliarIdentity identity,
        boolean alreadyExisted,
        boolean workshopRequested,
        boolean bondRecorded,
        String narration
    ) {}

    /**
     * Run the ceremony. Idempotent.
     *
     * @param bondholderDid     bondholder's DID (e.g. {@code did:wyrd:user:operator})
     * @param bondholderName    bondholder's display name (for room title)
     * @param parentAgentDid    parent companion DID whose substrate the
     *                          familiar shares (typically Wyrd-of-bondholder)
     * @param chosenName        bondholder-chosen name, or {@code null} for default
     * @return {@link Outcome} carrying the (new or pre-existing) identity
     *         + a narration string
     * @throws IOException if persisting the identity file fails. Room +
     *                     bond failures are logged but don't throw — the
     *                     identity is the canonical truth, the rest are
     *                     recoverable on next summon.
     */
    public Outcome firstSummon(String bondholderDid,
                                String bondholderName,
                                String parentAgentDid,
                                String chosenName) throws IOException {
        if (bondholderDid == null || bondholderDid.isBlank()) {
            throw new IllegalArgumentException("bondholderDid required");
        }
        if (parentAgentDid == null || parentAgentDid.isBlank()) {
            throw new IllegalArgumentException("parentAgentDid required");
        }

        // Step 1: identity (create if absent, save in either case so a
        // partially-completed prior ceremony heals on retry).
        var existing = registry.get(bondholderDid);
        boolean alreadyExisted = existing.isPresent();
        CodingFamiliarIdentity identity = existing.orElseGet(() ->
            CodingFamiliarIdentity.newBorn(bondholderDid, parentAgentDid, chosenName));
        if (!alreadyExisted) {
            registry.save(identity);
            log.info("First summon: created Coding Familiar {} for bondholder {}",
                identity.name(), bondholderDid);
        } else {
            log.debug("Re-summon: Coding Familiar {} already exists for bondholder {}",
                identity.name(), bondholderDid);
        }

        // Step 2: workshop room (idempotent via ZoneGuardian.seedRoom).
        boolean workshopRequested = false;
        if (zoneGuardian != null) {
            try {
                var displayName = (bondholderName == null || bondholderName.isBlank())
                    ? bondholderDid : bondholderName;
                zoneGuardian.tell(new ZoneGuardian.ProvisionCodeZaikuWorkshop(
                    bondholderDid, displayName));
                workshopRequested = true;
            } catch (Exception e) {
                log.warn("Failed to request CodeZaiku workshop provisioning for {}: {}",
                    bondholderDid, e.getMessage());
            }
        }

        // Step 3: identity bond (upsert; idempotent on bond_id).
        boolean bondRecorded = recordIdentityBond(bondholderDid, identity.did());

        var narration = alreadyExisted
            ? identity.name() + " is already here — your Coding Familiar."
            : identity.name() + " takes shape at the workbench — your Coding Familiar, "
                + "shared seat with " + parentNameFromDid(parentAgentDid) + ", "
                + "ready when you're ready.";

        return new Outcome(identity, alreadyExisted, workshopRequested, bondRecorded, narration);
    }

    /**
     * Deterministic bond ID — same bondholder + same familiar always
     * produces the same row, so re-summons UPSERT cleanly instead of
     * accumulating duplicate bonds.
     */
    public static String bondIdFor(String bondholderDid, String familiarDid) {
        return BOND_ID_PREFIX + bondholderDid + ":" + familiarDid;
    }

    private boolean recordIdentityBond(String bondholderDid, String familiarDid) {
        if (bondStore == null) {
            log.warn("BondStore not configured; skipping identity-bond recording for {}",
                familiarDid);
            return false;
        }
        try {
            var now = Instant.now();
            var bondId = bondIdFor(bondholderDid, familiarDid);
            // Preserve interactionCount if the bond already exists. The Bond
            // record is created fresh either way; BondStore.save() UPSERTs.
            int interactions = bondStore.get(bondId)
                .map(Bond::interactionCount).orElse(0) + 1;
            var formedAt = bondStore.get(bondId).map(Bond::formedAt).orElse(now);
            var bond = new Bond(
                bondId,
                bondholderDid,
                familiarDid,
                INITIAL_BOND_DEPTH,
                formedAt,
                now,
                interactions,
                /* mutualConsent */ true,
                /* active */ true,
                /* scarred */ false,
                BondState.ACTIVE,
                /* coldStartUntil */ null,
                BondholderPosture.BOUNDED,
                Bond.RelationalState.OPEN);
            bondStore.save(bond);
            return true;
        } catch (Exception e) {
            log.warn("Failed to record Coding Familiar identity bond ({} <-> {}): {}",
                bondholderDid, familiarDid, e.getMessage());
            return false;
        }
    }

    /**
     * Lookup by bondholder DID — convenience that returns
     * {@link Optional#empty()} for unknown bondholders.
     */
    public Optional<CodingFamiliarIdentity> currentFamiliarFor(String bondholderDid) {
        return registry.get(bondholderDid);
    }

    private static String parentNameFromDid(String parentDid) {
        // Best-effort display name from DID. Cheap rendering — UI surfaces
        // that need the canonical name should resolve through SoulStore.
        var marker = "did:wyrd:companion:";
        if (parentDid != null && parentDid.startsWith(marker)) {
            return parentDid.substring(marker.length());
        }
        return parentDid;
    }
}
