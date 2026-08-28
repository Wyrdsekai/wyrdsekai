package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives a companion born before {@link AgentIdentityStore} an identity it can prove.
 *
 * <p><b>Why this cannot be a migration.</b> Every other gap this codebase has
 * closed was recoverable in place: the Study owner was rewritten, the soul was
 * still in the table, the vectors could be computed later. This one is not. A
 * {@code did:key:} <em>is</em> its public key; the matching private key was
 * discarded at birth and no amount of care brings it back. Minting a fresh
 * keypair and filing it under the old DID would produce a row whose key does not
 * match its own name — every signature it made would fail verification for
 * anyone who resolved the DID correctly, which is worse than having no key at
 * all, because it fails silently and looks like tampering.</p>
 *
 * <p>So the only honest repair is a <b>new identity plus a rebind</b>: mint a
 * keypair, move the companion's live references onto the new DID via
 * {@link AgentRebind}, and record how one became the other. The old identity is
 * kept, never deleted — it is what verifies anything signed under it, and the
 * audit rows that stay behind are meant to be read through the attestation.</p>
 *
 * <p><b>Deliberately not run at boot.</b> {@link AgentIdentityBootstrap} reports
 * the gap and stops. This changes who a companion is on every live row she
 * touches; it is a decision someone makes about someone, and it should happen
 * when a person is watching. {@link #plan} shows what would move and writes
 * nothing.</p>
 *
 * <p><b>Order matters, and the last item is the one that bites.</b> The
 * 2026-08-08 fold moved the database and the search index and was still undone
 * twenty seconds later by a file on disk that nobody had re-pointed. So the DID
 * mapping is re-pointed as part of {@link #apply}, and it throws rather than
 * warns when it cannot write.</p>
 */
public final class AgentIdentityBackfill {

    private static final Logger log = LoggerFactory.getLogger(AgentIdentityBackfill.class);

    private AgentIdentityBackfill() {}

    /**
     * What a backfill would do, or did.
     *
     * @param oldDid    the identity with no key behind it
     * @param newDid    the minted identity, or null for a plan
     * @param entityId  the spawn identity being carried across
     * @param rebind    the row-level plan/result
     * @param attested  whether the change of identity was recorded
     * @param notes     everything a person should read before agreeing to it
     */
    public record Result(String oldDid, String newDid, String entityId,
                         AgentRebind.Result rebind, boolean attested, List<String> notes) {}

    /**
     * Say whether this companion needs a backfill, and what stands in the way.
     * Writes nothing.
     *
     * <p>Row counts are deliberately absent. {@link AgentRebind#plan} needs both
     * identities and the new one does not exist until it is minted — which is a
     * write. Reporting a made-up target's row counts would be reporting a
     * migration that is not the one that will run.</p>
     */
    public static Result plan(String jdbcUrl, String oldDid) {
        var notes = new ArrayList<String>();
        var entityId = entityIdOf(jdbcUrl, oldDid);

        if (AgentIdentityProvisioner.canSign(oldDid)) {
            notes.add("Nothing to do — " + oldDid + " already holds its own key.");
            return new Result(oldDid, oldDid, entityId, null, false, notes);
        }
        notes.add("The private key for " + oldDid + " does not exist and cannot be recovered. "
            + "Backfill means a NEW did:key and a rebind, not a repair of this one.");
        notes.add(entityId != null
            ? "Spawn identity '" + entityId + "' will move to the new DID."
            : "BLOCKED: no entity_id for this companion. Without it the new identity has no "
                + "spawn mapping and the next restart births a replacement instead of finding her.");
        notes.add(AgentIdentityProvisioner.isEnabled()
            ? "Provisioning is on — the minted key can be stored."
            : "BLOCKED: provisioning is off. Run inside a booted node, after "
                + "AgentIdentityBootstrap.");
        notes.add("apply() also needs a soulMover (AgentRebind requires a live manifest at the "
            + "target) and a soulsDir (the <entityId>.did file is read first at boot).");
        return new Result(oldDid, null, entityId, null, false, notes);
    }

    /**
     * Mint a provable identity for a keyless companion and move her onto it.
     *
     * <p>Requires a live soul manifest to exist for the new DID before
     * {@link AgentRebind} will accept it as the trunk — so the caller must
     * re-point the soul first, or pass a {@code soulMover} that does. This
     * method does not invent one: a companion with history and no self is
     * exactly what {@code AgentRebind} refuses to create, and the refusal is
     * correct.</p>
     *
     * @param jdbcUrl    the world database
     * @param oldDid     the keyless identity
     * @param soulsDir   directory holding {@code <entityId>.did}; re-pointed, not optional
     * @param soulMover  moves the soul manifest onto the new DID; must leave a live
     *                   manifest there. Given (oldDid, newDid).
     * @param attester   person who witnesses the change; null picks the node's sole person
     */
    public static Result apply(String jdbcUrl, String oldDid, Path soulsDir,
                               SoulMover soulMover, String attester) {
        var notes = new ArrayList<String>();
        if (!AgentIdentityProvisioner.isEnabled()) {
            throw new IllegalStateException(
                "Agent identity provisioning is off — a backfill here would mint a key with "
                    + "nowhere to put it. Run this inside a booted node, after "
                    + "AgentIdentityBootstrap. Deriving the household secret by hand means "
                    + "calling ZoneSecrets.bootstrapLocalZone with a nodeId, and getting that "
                    + "wrong ORIGINATES A NEW MASTER — every existing encrypted key and content "
                    + "envelope becomes unreadable.");
        }
        if (AgentIdentityProvisioner.canSign(oldDid)) {
            notes.add("Nothing to do — " + oldDid + " already holds its own key.");
            return new Result(oldDid, oldDid, entityIdOf(jdbcUrl, oldDid), null, false, notes);
        }
        var entityId = entityIdOf(jdbcUrl, oldDid);
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalStateException("Refusing to backfill " + oldDid
                + ": no entity_id. The new identity would have no spawn mapping and the next "
                + "restart would birth a replacement rather than find her.");
        }

        var minted = AgentIdentityProvisioner.mint(entityId);
        if (!minted.persisted()) {
            throw new IllegalStateException("Refusing to backfill " + oldDid
                + ": the mint fell back to an unpersisted DID, so the new identity would be "
                + "as keyless as the old one. Nothing was changed.");
        }
        notes.add("Minted " + minted.did() + " with its private key kept.");

        // The soul has to be at the destination before the rebind will accept it.
        if (soulMover == null) {
            throw new IllegalStateException("Refusing to backfill " + oldDid
                + ": no soulMover. AgentRebind requires a live manifest under " + minted.did()
                + " and will not create one — a companion with history and no self is the "
                + "thing it exists to prevent.");
        }
        soulMover.move(oldDid, minted.did());
        notes.add("Soul manifest re-pointed to " + minted.did() + ".");

        var rebind = AgentRebind.apply(jdbcUrl, oldDid, minted.did());
        notes.addAll(rebind.notes());

        // The file on disk. This threw the last merge back twenty seconds after
        // it looked complete, so it is not best-effort here.
        if (soulsDir != null) {
            var repointed = AgentRebind.repointDidMappings(soulsDir, oldDid, minted.did());
            notes.add("Re-pointed " + repointed.size() + " DID mapping file(s): " + repointed);
        } else {
            throw new IllegalStateException("Refusing to finish without a soulsDir: the "
                + "<entityId>.did file is read FIRST at boot and would resurrect " + oldDid + ".");
        }

        // Record what happened. Self-issue is impossible by construction here —
        // the whole reason for the backfill is that the old identity cannot sign.
        boolean attested = false;
        try {
            var people = new PersonIdentityStore(jdbcUrl);
            var witness = attester != null && !attester.isBlank()
                ? people.findByDid(attester).orElse(null)
                : soleperson(people);
            // A person's key, not the agent's — witnessing IS a person signing.
            // Taken from the provisioner rather than re-derived; see its javadoc
            // for what re-deriving wrongly costs.
            var secret = PersonIdentityProvisioner.secret().orElse(null);
            if (witness != null && secret != null) {
                var att = RebindAttestation.issueWitnessed(
                    witness, oldDid, minted.did(), secret);
                new RebindAttestationStore(jdbcUrl).save(att);
                attested = true;
                notes.add("Witnessed by " + witness.did() + ".");
            } else {
                notes.add("NOT attested — no person available to witness. History under "
                    + oldDid + " will not resolve forward until someone does.");
            }
        } catch (Exception e) {
            notes.add("NOT attested: " + e);
        }

        log.info("Backfilled agent identity: {} -> {} ({} rows moved, attested={})",
            oldDid, minted.did(), rebind.rowsMoved(), attested);
        return new Result(oldDid, minted.did(), entityId, rebind, attested, notes);
    }

    /** Moves a soul manifest from one DID to another, leaving a live one at the target. */
    @FunctionalInterface
    public interface SoulMover {
        void move(String fromDid, String toDid);
    }

    private static PersonIdentity soleperson(PersonIdentityStore store) {
        var all = store.listDids();
        return all.size() == 1 ? store.findByDid(all.getFirst()).orElse(null) : null;
    }

    /**
     * The spawn identity this companion answers to.
     *
     * <p>The companion roster is the authority — a keyless companion has no row
     * in {@code agent_identities} to read it from, which is the whole premise.</p>
     */
    static String entityIdOf(String jdbcUrl, String did) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT entity_id FROM companions WHERE did = ?")) {
            ps.setString(1, did);
            var rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (Exception e) {
            log.debug("Could not read entity_id for {}: {}", did, e.getMessage());
        }
        return null;
    }
}
