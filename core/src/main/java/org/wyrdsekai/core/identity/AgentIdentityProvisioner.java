package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Mints a companion's identity at birth, and keeps the private half.
 *
 * <p><b>The half that stops the problem existing.</b> Before this, every birth
 * ran {@code DidKey.generate()}, wrote the public key into the manifest, and
 * dropped the keypair on the floor when the method returned. The companion ended
 * up holding a {@code did:key:} — an identifier that <em>is</em> a public key —
 * with no way to demonstrate it owned the matching private one. Everything built
 * on top inherited that: soul manifests born unsigned, {@code CompanionSeedResolver}
 * unable to derive a nostr key, and a rebind that had to be
 * {@linkplain RebindAttestation#issueWitnessed witnessed by the steward} because
 * the companion could not declare it herself.</p>
 *
 * <p>Wired as a static optional hook, the same shape as
 * {@link PersonIdentityProvisioner}: a no-op until {@link #init} is called, so
 * tests, offline tools and a node whose zone master is not installed yet behave
 * exactly as they did before rather than half-working.</p>
 *
 * <p><b>Birth must never fail because of this.</b> {@link #mint} always returns a
 * usable DID — if provisioning is off, or the household secret is unavailable, or
 * the write fails, it falls back to the old generate-and-discard behaviour and
 * says so. A companion born keyless can be backfilled later; a companion that
 * failed to be born cannot.</p>
 */
public final class AgentIdentityProvisioner {

    private static final Logger log = LoggerFactory.getLogger(AgentIdentityProvisioner.class);

    private static volatile AgentIdentityStore identities;
    private static volatile Supplier<byte[]> householdSecret;

    private AgentIdentityProvisioner() {}

    /**
     * A DID for a newly born companion, and whether its key was kept.
     *
     * @param did                 the {@code did:key:} identifier
     * @param publicKeyMultibase  the DID's multibase suffix, for the manifest
     * @param persisted           true when the private half is stored and this
     *                            companion can actually sign as itself
     */
    public record Minted(String did, String publicKeyMultibase, boolean persisted) {}

    /**
     * Wire the provisioner.
     *
     * @param jdbcUrl        world database
     * @param secretSupplier supplies the 32-byte household secret
     */
    public static void init(String jdbcUrl, Supplier<byte[]> secretSupplier) {
        identities = new AgentIdentityStore(jdbcUrl);
        householdSecret = secretSupplier;
        log.info("Agent identity provisioning enabled");
    }

    /** Test/teardown hook. */
    public static void reset() {
        identities = null;
        householdSecret = null;
    }

    public static boolean isEnabled() {
        return identities != null && householdSecret != null;
    }

    /** The identity store in use, when provisioning is enabled. */
    public static Optional<AgentIdentityStore> identities() {
        return Optional.ofNullable(identities);
    }

    /**
     * The DID already minted for a spawn identity, if any.
     *
     * <p>Checked before birth. The only entityId→DID mapping used to be a file;
     * on 2026-08-08 a stale one caused a live companion to be born a third time.
     * A database row is a second witness to the same fact.</p>
     */
    public static Optional<String> existingDidFor(String entityId) {
        var store = identities;
        if (store == null || entityId == null || entityId.isBlank()) return Optional.empty();
        try {
            return store.didForEntity(entityId);
        } catch (RuntimeException e) {
            log.warn("Could not look up agent identity for entity {}: {}", entityId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Mint an identity for a companion being born, keeping the private key.
     *
     * <p>Never throws and never returns null — see the class note on why birth
     * takes priority over provisioning.</p>
     *
     * @param entityId the spawn identity the companion will be found by
     */
    public static Minted mint(String entityId) {
        if (isEnabled()) {
            try {
                var secret = householdSecret.get();
                if (secret == null || secret.length != 32) {
                    log.warn("Household secret unavailable — companion '{}' born without a "
                        + "signing key; it can be backfilled later", entityId);
                } else {
                    var identity = AgentIdentity.generate(secret);
                    identities.save(identity, entityId);
                    log.info("Agent identity minted for '{}' -> {}", entityId, identity.did());
                    return new Minted(identity.did(), multibaseOf(identity.did()), true);
                }
            } catch (Exception e) {
                log.warn("Could not mint agent identity for '{}': {} — falling back to an "
                    + "unpersisted DID", entityId, e.toString());
            }
        }
        return generateUnpersisted();
    }

    /**
     * Record an identity whose private key lives elsewhere — a foreign agent
     * recognised from a residency token. We can verify what they sign; we cannot
     * sign as them, and {@link AgentIdentityStore#listKeyless()} keeps that visible.
     */
    public static boolean record(AgentIdentity identity, String entityId) {
        var store = identities;
        if (store == null || identity == null) return false;
        try {
            store.save(identity, entityId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not record agent identity {}: {}", identity.did(), e.toString());
            return false;
        }
    }

    /**
     * Attach a spawn identity to an already-minted one, for callers whose
     * entityId is derived from the DID (the promotion ceremony). No-op when
     * provisioning is off or the identity is already linked.
     */
    public static boolean linkEntity(String did, String entityId) {
        var store = identities;
        if (store == null) return false;
        try {
            return store.linkEntity(did, entityId);
        } catch (RuntimeException e) {
            log.warn("Could not link entity {} to {}: {}", entityId, did, e.toString());
            return false;
        }
    }

    /** The stored identity for a DID, if this node has one. */
    public static Optional<AgentIdentity> find(String did) {
        var store = identities;
        if (store == null) return Optional.empty();
        try {
            return store.findByDid(did);
        } catch (RuntimeException e) {
            log.warn("Could not load agent identity {}: {}", did, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Whether this companion can sign as itself.
     *
     * <p>The honest question to ask before choosing between a self-issued and a
     * witnessed claim. False for every companion born before this existed.</p>
     */
    public static boolean canSign(String did) {
        var store = identities;
        if (store == null || householdSecret == null) return false;
        try {
            return store.canSign(did);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Sign bytes as this companion.
     *
     * @return the Base64 signature, or empty when this node holds no key for it
     */
    public static Optional<String> sign(String did, byte[] data) {
        if (!isEnabled() || data == null) return Optional.empty();
        try {
            var identity = identities.findByDid(did).orElse(null);
            if (identity == null || identity.privateKeyEncrypted() == null) return Optional.empty();
            var secret = householdSecret.get();
            if (secret == null || secret.length != 32) {
                log.warn("Household secret unavailable — cannot sign as {}", did);
                return Optional.empty();
            }
            return Optional.of(identity.sign(data, secret));
        } catch (Exception e) {
            log.warn("Could not sign as {}: {}", did, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Verify a signature made by a known agent.
     *
     * <p>Works for foreign agents too — verification needs only the public half,
     * which is exactly why their keyless rows are worth storing.</p>
     */
    public static boolean verify(String did, byte[] data, String signatureBase64) {
        return find(did).map(i -> i.verify(data, signatureBase64)).orElse(false);
    }

    /** The household secret, for callers that must do their own crypto. */
    public static Optional<byte[]> secret() {
        var supplier = householdSecret;
        if (supplier == null) return Optional.empty();
        var s = supplier.get();
        return (s != null && s.length == 32) ? Optional.of(s) : Optional.empty();
    }

    /** The pre-existing behaviour: a DID with nothing behind it. */
    private static Minted generateUnpersisted() {
        try {
            var pair = DidKey.generate();
            return new Minted(pair.did(), multibaseOf(pair.did()), false);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable — cannot mint a DID", e);
        }
    }

    private static String multibaseOf(String did) {
        return did.substring("did:key:".length());
    }
}
