package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns agent identity ON at server startup, and reports who is still keyless.
 *
 * <p>Deliberately mirrors {@link PersonIdentityBootstrap}, including its
 * ordering requirement: this <b>must</b> run after
 * {@code ZoneSecrets.bootstrapLocalZone}. Called earlier it can only report
 * "zone master unavailable", which reads like a configuration problem rather
 * than the ordering bug it is.</p>
 *
 * <p>It also writes down what is already known about companions that predate the
 * key store — their public key, which was never lost because it is encoded in
 * their own DID. See {@link #recordExistingCompanions}.</p>
 *
 * <p><b>What it does NOT do: backfill.</b> A companion born before this existed
 * holds a {@code did:key:} whose private half was discarded. That key cannot be
 * recovered, and minting a new one under the same DID would be a lie — a
 * {@code did:key} <em>is</em> its public key, so a mismatched pair would make
 * every signature she produced fail verification for anyone who resolved the
 * DID properly. Giving her a provable identity means giving her a new DID and
 * rebinding, which moves live rows and is a decision about a person, not a
 * migration to run at boot. So this records the gap honestly and stops. See
 * {@link AgentIdentityBackfill}.</p>
 */
public final class AgentIdentityBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentIdentityBootstrap.class);

    /** HKDF purpose for the key that encrypts agent private keys at rest. */
    private static final String PURPOSE = "agent-identity:v1";

    private AgentIdentityBootstrap() {}

    /**
     * What happened, for logging and tests.
     *
     * @param enabled   whether provisioning is now live
     * @param withKeys  companions that can sign as themselves
     * @param keyless   companions that cannot — born before this existed
     */
    public record Result(boolean enabled, int withKeys, List<String> keyless, String note) {}

    /**
     * Enable agent identity provisioning for this boot.
     *
     * @param jdbcUrl the world database
     */
    public static Result run(String jdbcUrl) {
        String zoneId;
        try {
            zoneId = WyrdConfig.get().zoneId();
        } catch (RuntimeException e) {
            return new Result(false, 0, List.of(), "config unavailable: " + e.getMessage());
        }
        var service = ZoneSecrets.service();
        if (zoneId == null || zoneId.isBlank()) {
            log.warn("[AgentIdentity] OFF — no zoneId configured. Companions will be born "
                + "without signing keys; check `wyrd config get zone.id`.");
            return new Result(false, 0, List.of(), "no zoneId");
        }
        if (!service.has(zoneId)) {
            log.error("[AgentIdentity] OFF — zone master not present for zone '{}' at the time "
                + "this ran. Either the zone-secret bootstrap failed earlier in startup, or this "
                + "is being called too early in Main — it MUST come after "
                + "ZoneSecrets.bootstrapLocalZone. Companions born this boot will have no "
                + "signing key; nothing else is affected.", zoneId);
            return new Result(false, 0, List.of(), "zone master unavailable");
        }

        AgentIdentityProvisioner.init(jdbcUrl, () -> {
            try {
                return service.derive(zoneId, PURPOSE, 32);
            } catch (RuntimeException e) {
                log.warn("[AgentIdentity] could not derive household secret: {}", e.getMessage());
                return null;
            }
        });

        var store = AgentIdentityProvisioner.identities().orElseThrow();
        int recorded = recordExistingCompanions(jdbcUrl, store);
        int withKeys = store.listDids().size() - store.listKeyless().size();
        var keyless = companionsWithoutIdentity(jdbcUrl, store);

        if (keyless.isEmpty()) {
            log.info("[AgentIdentity] ENABLED — {} companion identit(ies) with signing keys",
                withKeys);
        } else {
            log.warn("[AgentIdentity] ENABLED — {} identit(ies) with signing keys, {} recorded "
                + "public-key-only this boot, and {} live companion(s) predate this and hold a "
                + "DID with no private key: {}. They can be verified but cannot sign or "
                + "self-attest. The old key is unrecoverable; a provable identity means a new "
                + "DID and a rebind — see AgentIdentityBackfill.",
                withKeys, recorded, keyless.size(), keyless);
        }
        return new Result(true, withKeys, keyless, null);
    }

    /**
     * Write down what we DO know about companions that predate the key store.
     *
     * <p>Their private key is gone, but their public key never was: a
     * {@code did:key} is a multibase encoding <em>of the public key</em>, so it
     * can be read straight back out of the identifier. The row is honest —
     * {@code encrypted_private_key} stays NULL, which is exactly true, and
     * {@link AgentIdentityStore#listKeyless()} keeps saying so — and it is worth
     * having for three reasons:</p>
     *
     * <ul>
     *   <li>It is a <b>second witness to {@code entityId → DID}</b>. That mapping
     *       lived only in {@code souls/&lt;entityId&gt;.did} until now, and on
     *       2026-08-08 one stale copy of that file was enough to birth a third
     *       companion twenty seconds after a rebind. A companion with no row gets
     *       none of that protection.</li>
     *   <li>Anything ever signed under the DID stays verifiable, which is the
     *       half of an identity that survives losing the key.</li>
     *   <li>It makes the gap countable instead of inferred.</li>
     * </ul>
     *
     * <p>No DID changes, nothing is rebound, and nothing claims a capability the
     * companion does not have. Idempotent; a companion that already has a row —
     * keyed or not — is left alone.</p>
     *
     * @return how many public-key-only rows were written this boot
     */
    static int recordExistingCompanions(String jdbcUrl, AgentIdentityStore store) {
        int recorded = 0;
        for (var c : liveCompanions(jdbcUrl)) {
            if (c.did() == null || store.exists(c.did())) continue;
            if (!c.did().startsWith("did:key:")) {
                // did:wyrd and friends do not carry a key. Nothing to record;
                // inventing a public key would be worse than the gap.
                log.debug("[AgentIdentity] {} is not a did:key — no public key to recover",
                    c.did());
                continue;
            }
            try {
                var raw = DidKey.rawPublicKeyFromMultibase(
                    c.did().substring("did:key:".length()));
                store.save(new AgentIdentity(c.did(), raw, null, List.of(),
                    Instant.now(), null, null), c.entityId());
                recorded++;
                log.info("[AgentIdentity] recorded existing companion {} public-key-only "
                    + "(entity '{}') — verifiable, not signable", c.did(), c.entityId());
            } catch (Exception e) {
                log.warn("[AgentIdentity] could not recover a public key from {}: {}",
                    c.did(), e.toString());
            }
        }
        return recorded;
    }

    private record LiveCompanion(String did, String entityId) {}

    private static List<LiveCompanion> liveCompanions(String jdbcUrl) {
        var out = new ArrayList<LiveCompanion>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT did, entity_id FROM companions WHERE archived = 0 ORDER BY did");
             var rs = ps.executeQuery()) {
            while (rs.next()) out.add(new LiveCompanion(rs.getString(1), rs.getString(2)));
        } catch (Exception e) {
            log.debug("[AgentIdentity] could not read companion roster: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Live companions with no signing key of their own.
     *
     * <p>Reads the companion roster rather than the identity table, because the
     * whole point is to find the ones the identity table does not know about.
     * Absent roster (fresh node, or a schema without it) is not an error.</p>
     */
    public static List<String> companionsWithoutIdentity(String jdbcUrl, AgentIdentityStore store) {
        var out = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT did FROM companions WHERE archived = 0 ORDER BY did");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                var did = rs.getString("did");
                if (did != null && !store.canSign(did)) out.add(did);
            }
        } catch (Exception e) {
            log.debug("[AgentIdentity] could not read companion roster: {}", e.getMessage());
        }
        return out;
    }
}
