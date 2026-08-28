package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Mints a person identity at account-creation time.
 *
 * <p><b>This is the half that stops the problem existing.</b> Every install
 * before this minted a UUID for the person and nothing else, so there was never
 * an identity to resolve — which is why callers that needed an owner invented
 * one (a Unix username for Study content, {@code 'local-user'} on mobile). Once
 * {@code register()} mints a real identity, a fresh install can never enter
 * that state, independently of any migration of existing installs.</p>
 *
 * <p>Wired as a static optional hook, matching {@code ResidencyStore.grantLocal}
 * — it no-ops until {@link #init} is called, so {@code AuthService} keeps its
 * constructor and every existing caller and test is unaffected.</p>
 */
public final class PersonIdentityProvisioner {

    private static final Logger log = LoggerFactory.getLogger(PersonIdentityProvisioner.class);

    private static volatile PersonIdentityStore identities;
    private static volatile AccountStore accounts;
    private static volatile PersonIdentityResolver resolver;
    private static volatile Supplier<byte[]> householdSecret;

    private PersonIdentityProvisioner() {}

    /**
     * Wire the provisioner. Until this is called every entry point is a no-op,
     * so an un-migrated node behaves exactly as before rather than half-working.
     *
     * @param jdbcUrl         world database
     * @param secretSupplier  supplies the 32-byte household secret
     */
    public static void init(String jdbcUrl, Supplier<byte[]> secretSupplier) {
        identities = new PersonIdentityStore(jdbcUrl);
        accounts = new AccountStore(jdbcUrl);
        resolver = new PersonIdentityResolver(jdbcUrl);
        householdSecret = secretSupplier;
        log.info("Person identity provisioning enabled");
    }

    /** Test/teardown hook. */
    public static void reset() {
        identities = null;
        accounts = null;
        resolver = null;
        householdSecret = null;
    }

    public static boolean isEnabled() {
        return identities != null && householdSecret != null;
    }

    /**
     * Mint a person for a newly created local credential, and bind the two.
     *
     * <p>Failure here must never take down account creation — a person who
     * cannot log in is worse than one whose identity is provisioned late, and
     * the migration can mint retroactively. Returns empty and logs instead.</p>
     *
     * @param userId      the local credential id ({@code users.id})
     * @param displayName human-readable name for the person record
     * @return the minted person DID, or empty if provisioning is off or failed
     */
    public static Optional<String> provision(String userId, String displayName) {
        if (!isEnabled()) return Optional.empty();
        try {
            var secret = householdSecret.get();
            if (secret == null || secret.length != 32) {
                log.warn("Household secret unavailable — person identity not minted for {}", userId);
                return Optional.empty();
            }

            var identity = PersonIdentity.generate(secret);
            identities.save(identity);

            // The profile half — portable across nodes and devices.
            accounts.save(PlayerAccount.withDid(identity.did(),
                displayName != null && !displayName.isBlank() ? displayName : userId));

            // The credential half — local to this machine, pointing at the person.
            resolver.linkUserToPerson(userId, identity.did());

            log.info("Person provisioned for local account {} -> {}", userId, identity.did());
            return Optional.of(identity.did());
        } catch (Exception e) {
            log.warn("Could not provision person identity for {}: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Mint a person for an account that predates this mechanism, without
     * disturbing one that already has an identity. Used by the migration.
     *
     * @return the existing or newly minted person DID
     */
    public static Optional<String> provisionIfMissing(String userIdOrName, String displayName) {
        if (!isEnabled()) return Optional.empty();
        var existing = resolver.resolve(userIdOrName);
        if (existing.isPresent()) return existing;
        return provision(userIdOrName, displayName);
    }

    /** The resolver in use, when provisioning is enabled. */
    public static Optional<PersonIdentityResolver> resolver() {
        return Optional.ofNullable(resolver);
    }

    /** The identity store in use, when provisioning is enabled. */
    public static Optional<PersonIdentityStore> identities() {
        return Optional.ofNullable(identities);
    }

    /**
     * The household secret behind person keys, for callers that must sign as a
     * person — witnessing a rebind, for one.
     *
     * <p>Exposed rather than re-derived on purpose. Deriving it elsewhere means
     * calling {@code ZoneSecrets.bootstrapLocalZone} by hand, and a wrong
     * {@code nodeId} there originates a NEW master instead of finding the wrapped
     * one — which makes every person's private key and every content envelope on
     * the node unreadable.</p>
     */
    public static Optional<byte[]> secret() {
        var supplier = householdSecret;
        if (supplier == null) return Optional.empty();
        var s = supplier.get();
        return (s != null && s.length == 32) ? Optional.of(s) : Optional.empty();
    }
}
